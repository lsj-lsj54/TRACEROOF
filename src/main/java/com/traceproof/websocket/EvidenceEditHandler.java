package com.traceproof.websocket;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.exceptions.ExceptionUtil;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceproof.model.dto.websocket.EvidenceEditRequestMessage;
import com.traceproof.model.dto.websocket.EvidenceEditResponseMessage;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.EvidenceEditActionEnum;
import com.traceproof.model.enums.EvidenceEditMessageTypeEnum;
import com.traceproof.model.vo.UserVO;
import com.traceproof.service.UserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 影像编辑 WebSocket 处理器
 * <p>
 * 职责：
 * <ul>
 *     <li>维护每张影像的「当前编辑者」与「已连接会话」；</li>
 *     <li>把编辑动作广播给同一张影像的其他在线用户；</li>
 *     <li>保存最新绝对视图快照，供中途加入的用户直接同步状态。</li>
 * </ul>
 * 消息进入后不是直接处理，而是丢给 Disruptor 的 RingBuffer 异步消费（见
 * {@link EvidenceEditEventProducer} / {@link EvidenceEditEventWorkHandler}），
 * 真正落地的编辑逻辑由前端把动作应用到影像上。
 *
 * @author lsj
 */
@Slf4j
@Component
public class EvidenceEditHandler extends TextWebSocketHandler {

    /** 固定数量的分段锁，协调初始快照与实时广播，且不会随影像数增长 */
    private static final int VIEW_LOCK_STRIPES = 64;
    private final Object[] viewLocks = new Object[VIEW_LOCK_STRIPES];

    public EvidenceEditHandler() {
        for (int i = 0; i < VIEW_LOCK_STRIPES; i++) {
            viewLocks[i] = new Object();
        }
    }

    /**
     * 每张影像的编辑状态，key: evidenceId, value: 当前正在编辑的用户 ID
     */
    private final Map<Long, Long> evidenceEditingUsers = new ConcurrentHashMap<>();

    /**
     * 保存所有连接的会话，key: evidenceId, value: 用户会话集合
     */
    private final Map<Long, Set<WebSocketSession>> evidenceSessions = new ConcurrentHashMap<>();

    /** 最新绝对视图状态；每张影像只保留一条，不使用会无限增长的增量历史 */
    private final Map<Long, TextMessage> evidenceViewSnapshots = new ConcurrentHashMap<>();

    @Resource
    private UserService userService;

    /**
     * 使用 Spring 注入的 ObjectMapper 序列化，与 REST 接口保持一致的 Long 精度处理
     * （雪花 id 会序列化成字符串，避免前端 JSON.parse 丢失精度）。
     */
    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private EvidenceEditEventProducer evidenceEditEventProducer;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        User user = (User) session.getAttributes().get("user");
        Long evidenceId = (Long) session.getAttributes().get("evidenceId");
        // 同一影像的初始快照和实时动作串行化：先给新会话发最新快照，再把它加入广播集合。
        synchronized (viewLock(evidenceId)) {
            TextMessage snapshot = evidenceViewSnapshots.get(evidenceId);
            if (snapshot != null) {
                sendSafely(session, snapshot);
            }
            Set<WebSocketSession> sessionSet = evidenceSessions.computeIfAbsent(evidenceId,
                    k -> ConcurrentHashMap.newKeySet());
            sessionSet.add(session);
        }

        // 广播「加入编辑」通知
        EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
        evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.INFO.getValue());
        evidenceEditResponseMessage.setMessage(String.format("%s加入编辑", user.getUserName()));
        evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
        broadcastToEvidence(evidenceId, getTextMessage(evidenceEditResponseMessage));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 将消息解析为 EvidenceEditRequestMessage，并交给 Disruptor 异步处理
        EvidenceEditRequestMessage evidenceEditRequestMessage = JSONUtil.toBean(message.getPayload(), EvidenceEditRequestMessage.class);
        Map<String, Object> attributes = session.getAttributes();
        User user = (User) attributes.get("user");
        Long evidenceId = (Long) attributes.get("evidenceId");
        boolean accepted = evidenceEditEventProducer.publishEvent(evidenceEditRequestMessage, session, user, evidenceId);
        if (!accepted && session.isOpen()) {
            EvidenceEditResponseMessage response = new EvidenceEditResponseMessage();
            response.setType(EvidenceEditMessageTypeEnum.ERROR.getValue());
            response.setMessage("操作过于频繁，请稍后重试");
            response.setUser(UserVO.entityToVo(user));
            sendSafely(session, getTextMessage(response));
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        User user = (User) session.getAttributes().get("user");
        Long evidenceId = (Long) session.getAttributes().get("evidenceId");
        if (evidenceId == null) {
            return;
        }
        try {
            // 断开的用户是当前编辑者时，释放编辑锁并广播退出
            if (user != null && evidenceEditingUsers.remove(evidenceId, user.getId())) {
                EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
                evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.EXIT_EDIT.getValue());
                evidenceEditResponseMessage.setMessage(String.format("%s退出编辑影像", user.getUserName()));
                evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
                broadcastToEvidence(evidenceId, getTextMessage(evidenceEditResponseMessage));
            }
        } catch (Exception e) {
            log.error("EvidenceEditHandler#afterConnectionClosed {}", ExceptionUtil.getRootCauseMessage(e));
        } finally {
            Set<WebSocketSession> sessionSet = evidenceSessions.get(evidenceId);
            if (sessionSet != null) {
                sessionSet.remove(session);
            }
            cleanupIfNoSession(evidenceId);
        }
    }

    /**
     * 处理进入编辑的消息：没有用户正在编辑时，当前用户成为编辑者；否则拒绝。
     */
    public void handleEnterEditMessage(EvidenceEditContext evidenceEditContext) throws Exception {
        User user = evidenceEditContext.getUser();
        Long evidenceId = evidenceEditContext.getEvidenceId();
        // putIfAbsent 原子地抢占编辑权，避免两个用户同时进入编辑
        Long previous = evidenceEditingUsers.putIfAbsent(evidenceId, user.getId());
        if (previous == null) {
            // 成功成为编辑者，广播进入编辑
            EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
            evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.ENTER_EDIT.getValue());
            evidenceEditResponseMessage.setMessage(String.format("%s开始编辑影像", user.getUserName()));
            evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
            broadcastToEvidence(evidenceId, getTextMessage(evidenceEditResponseMessage));
        } else if (!previous.equals(user.getId())) {
            // 已有其他用户正在编辑，通知当前用户
            handleEditErrorMessage(user, evidenceId, evidenceEditContext.getSession());
        }
    }

    /**
     * 处理编辑动作：只有当前编辑者才能执行，并把动作广播给其他用户。
     */
    public void handleEditActionMessage(EvidenceEditContext evidenceEditContext) throws Exception {
        EvidenceEditRequestMessage requestMessage = evidenceEditContext.getRequestMessage();
        WebSocketSession session = evidenceEditContext.getSession();
        User user = evidenceEditContext.getUser();
        Long evidenceId = evidenceEditContext.getEvidenceId();
        String editAction = requestMessage.getEditAction();
        EvidenceEditActionEnum actionEnum = EvidenceEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            return;
        }
        if (!hasValidSnapshot(requestMessage)) {
            EvidenceEditResponseMessage error = new EvidenceEditResponseMessage();
            error.setType(EvidenceEditMessageTypeEnum.ERROR.getValue());
            error.setMessage("客户端版本过旧或视图状态无效，请刷新页面后重试");
            error.setUser(UserVO.entityToVo(user));
            sendSafely(session, getTextMessage(error));
            return;
        }
        synchronized (viewLock(evidenceId)) {
            Long editingUserId = evidenceEditingUsers.get(evidenceId);
            if (editingUserId == null || !editingUserId.equals(user.getId())) {
                return;
            }
            EvidenceEditResponseMessage response = new EvidenceEditResponseMessage();
            response.setType(EvidenceEditMessageTypeEnum.EDIT_ACTION.getValue());
            response.setMessage(String.format("%s执行%s", user.getUserName(), actionEnum.getText()));
            response.setEditAction(editAction);
            response.setUser(UserVO.entityToVo(user));
            response.setScale(requestMessage.getScale());
            response.setRotation(requestMessage.getRotation());
            TextMessage snapshot = getTextMessage(response);

            // 先更新快照再广播；新连接在同一个影像锁内只能看到完整的新状态。
            evidenceViewSnapshots.put(evidenceId, snapshot);
            broadcastToEvidence(evidenceId, snapshot, session);
        }
    }

    /** 新消息必须同时提供有限的绝对缩放与旋转值。 */
    private boolean hasValidSnapshot(EvidenceEditRequestMessage message) {
        Double scale = message.getScale();
        Double rotation = message.getRotation();
        return scale != null && rotation != null
                && Double.isFinite(scale) && Double.isFinite(rotation)
                && scale >= 0.05 && scale <= 20 && Math.abs(rotation) <= 1_000_000;
    }

    /**
     * 处理退出编辑的消息：释放编辑锁，广播退出通知，并移除会话。
     */
    public void handleExitEditMessage(EvidenceEditContext evidenceEditContext) {
        User user = evidenceEditContext.getUser();
        Long evidenceId = evidenceEditContext.getEvidenceId();
        try {
            // 仅当前编辑者退出时才广播并释放编辑锁
            if (evidenceEditingUsers.remove(evidenceId, user.getId())) {
                EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
                evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.EXIT_EDIT.getValue());
                evidenceEditResponseMessage.setMessage(String.format("%s退出编辑影像", user.getUserName()));
                evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
                broadcastToEvidence(evidenceId, getTextMessage(evidenceEditResponseMessage));
            }
            Set<WebSocketSession> sessionSet = evidenceSessions.get(evidenceId);
            if (sessionSet != null) {
                sessionSet.remove(evidenceEditContext.getSession());
            }
        } catch (Exception e) {
            log.error("EvidenceEditHandler#handleExitEditMessage {}", ExceptionUtil.getRootCauseMessage(e));
        } finally {
            cleanupIfNoSession(evidenceId);
        }
    }

    /**
     * 发送「已有其他用户正在编辑」的错误提示
     */
    private void handleEditErrorMessage(User user, Long evidenceId, WebSocketSession sendSession) throws Exception {
        Long editUserId = evidenceEditingUsers.get(evidenceId);
        User editUser = editUserId == null ? null : userService.getById(editUserId);
        EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
        evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.ERROR.getValue());
        evidenceEditResponseMessage.setMessage(String.format("操作失败 %s 正在操作",
                editUser == null ? "其他用户" : editUser.getUserName()));
        evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
        sendSafely(sendSession, getTextMessage(evidenceEditResponseMessage));
    }

    /**
     * 广播给同一张影像的所有会话
     */
    private void broadcastToEvidence(Long evidenceId, TextMessage textMessage, WebSocketSession excludeSession) throws Exception {
        Set<WebSocketSession> sessionSet = evidenceSessions.get(evidenceId);
        if (CollUtil.isNotEmpty(sessionSet)) {
            for (WebSocketSession session : sessionSet) {
                // 排除掉的 session 不发送
                if (excludeSession != null && excludeSession.equals(session)) {
                    continue;
                }
                sendSafely(session, textMessage);
            }
        }
    }

    /**
     * 全部广播
     */
    private void broadcastToEvidence(Long evidenceId, TextMessage textMessage) throws Exception {
        broadcastToEvidence(evidenceId, textMessage, null);
    }

    private Object viewLock(Long evidenceId) {
        return viewLocks[Math.floorMod(evidenceId.hashCode(), VIEW_LOCK_STRIPES)];
    }

    /** 同一 Session 的所有出站消息串行发送，避免容器接收线程与 Disruptor 线程并发写入。 */
    private void sendSafely(WebSocketSession session, TextMessage message) throws IOException {
        if (session == null) {
            return;
        }
        synchronized (session) {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        }
    }

    /**
     * 序列化响应消息为 TextMessage
     */
    private TextMessage getTextMessage(EvidenceEditResponseMessage evidenceEditResponseMessage) throws JsonProcessingException {
        return new TextMessage(objectMapper.writeValueAsString(evidenceEditResponseMessage));
    }

    /**
     * 对应影像没人连接时清理编辑状态，防止内存泄露
     */
    private void cleanupIfNoSession(Long evidenceId) {
        synchronized (viewLock(evidenceId)) {
            Set<WebSocketSession> sessionSet = evidenceSessions.get(evidenceId);
            if (sessionSet == null || sessionSet.isEmpty()) {
                evidenceSessions.remove(evidenceId);
                evidenceViewSnapshots.remove(evidenceId);
                evidenceEditingUsers.remove(evidenceId);
            }
        }
    }

    /**
     * 影像编辑上下文，把请求消息、会话、用户、影像 id 打包传递
     */
    @Data
    @AllArgsConstructor
    public static class EvidenceEditContext {
        private EvidenceEditRequestMessage requestMessage;
        private WebSocketSession session;
        private User user;
        private Long evidenceId;
    }
}
