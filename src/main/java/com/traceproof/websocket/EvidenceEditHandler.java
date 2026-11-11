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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 影像编辑 WebSocket 处理器
 * <p>
 * 职责：
 * <ul>
 *     <li>维护每张影像的「当前编辑者」与「已连接会话」；</li>
 *     <li>把编辑动作广播给同一张影像的其他在线用户；</li>
 *     <li>记录编辑历史，供中途加入的用户同步到最新状态。</li>
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

    /**
     * 每张影像的编辑状态，key: evidenceId, value: 当前正在编辑的用户 ID
     */
    private final Map<Long, Long> evidenceEditingUsers = new ConcurrentHashMap<>();

    /**
     * 保存所有连接的会话，key: evidenceId, value: 用户会话集合
     */
    private final Map<Long, Set<WebSocketSession>> evidenceSessions = new ConcurrentHashMap<>();

    /**
     * 某张影像的编辑记录，key: evidenceId, value: 编辑记录，
     * 用于某个用户中途加入编辑的时候看到最新的编辑记录
     */
    private final Map<Long, List<TextMessage>> evidenceEditRecodes = new ConcurrentHashMap<>();

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
        // 保存会话到集合中
        Set<WebSocketSession> sessionSet = evidenceSessions.computeIfAbsent(evidenceId, k -> ConcurrentHashMap.newKeySet());
        sessionSet.add(session);

        // 广播「加入编辑」通知
        EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
        evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.INFO.getValue());
        evidenceEditResponseMessage.setMessage(String.format("%s加入编辑", user.getUserName()));
        evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
        broadcastToEvidence(evidenceId, getTextMessage(evidenceEditResponseMessage));

        // 把历史编辑记录发给新加入的用户，保证其看到最新的编辑结果
        broadcastToOneUser(evidenceId, evidenceEditRecodes.get(evidenceId), session);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        // 将消息解析为 EvidenceEditRequestMessage，并交给 Disruptor 异步处理
        EvidenceEditRequestMessage evidenceEditRequestMessage = JSONUtil.toBean(message.getPayload(), EvidenceEditRequestMessage.class);
        Map<String, Object> attributes = session.getAttributes();
        User user = (User) attributes.get("user");
        Long evidenceId = (Long) attributes.get("evidenceId");
        evidenceEditEventProducer.publishEvent(evidenceEditRequestMessage, session, user, evidenceId);
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
        Long editingUserId = evidenceEditingUsers.get(evidenceId);
        String editAction = requestMessage.getEditAction();
        EvidenceEditActionEnum actionEnum = EvidenceEditActionEnum.getEnumByValue(editAction);
        if (actionEnum == null) {
            return;
        }
        // 确认是当前编辑者
        if (editingUserId != null && editingUserId.equals(user.getId())) {
            EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
            evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.EDIT_ACTION.getValue());
            evidenceEditResponseMessage.setMessage(String.format("%s执行%s", user.getUserName(), actionEnum.getText()));
            evidenceEditResponseMessage.setEditAction(editAction);
            evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
            // 广播给除了当前客户端之外的其他用户，否则会造成重复编辑
            broadcastToEvidence(evidenceId, getTextMessage(evidenceEditResponseMessage), session);

            // 记录编辑动作，供中途加入的用户同步
            EvidenceEditResponseMessage record = new EvidenceEditResponseMessage();
            record.setType(EvidenceEditMessageTypeEnum.EDIT_ACTION.getValue());
            record.setEditAction(editAction);
            List<TextMessage> list = evidenceEditRecodes.computeIfAbsent(evidenceId, k -> new CopyOnWriteArrayList<>());
            list.add(getTextMessage(record));
        }
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
        if (sendSession != null && sendSession.isOpen()) {
            sendSession.sendMessage(getTextMessage(evidenceEditResponseMessage));
        }
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
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        }
    }

    /**
     * 全部广播
     */
    private void broadcastToEvidence(Long evidenceId, TextMessage textMessage) throws Exception {
        broadcastToEvidence(evidenceId, textMessage, null);
    }

    /**
     * 给指定用户发送历史消息
     */
    private void broadcastToOneUser(Long evidenceId, List<TextMessage> textMessages, WebSocketSession sendSession) throws Exception {
        if (CollUtil.isEmpty(textMessages) || sendSession == null || !sendSession.isOpen()) {
            return;
        }
        for (TextMessage textMessage : textMessages) {
            sendSession.sendMessage(textMessage);
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
        Set<WebSocketSession> sessionSet = evidenceSessions.get(evidenceId);
        if (sessionSet == null || sessionSet.isEmpty()) {
            evidenceSessions.remove(evidenceId);
            evidenceEditRecodes.remove(evidenceId);
            evidenceEditingUsers.remove(evidenceId);
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
