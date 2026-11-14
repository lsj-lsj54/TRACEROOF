package com.traceproof.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lmax.disruptor.WorkHandler;
import com.traceproof.model.dto.websocket.EvidenceEditRequestMessage;
import com.traceproof.model.dto.websocket.EvidenceEditResponseMessage;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.EvidenceEditMessageTypeEnum;
import com.traceproof.model.vo.UserVO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.Resource;

/**
 * 影像编辑事件消费者，作为 Disruptor 的 WorkHandler 处理 {@link EvidenceEditEvent}
 * <p>
 * 把事件按消息类型分发到 {@link EvidenceEditHandler} 的对应处理方法。
 *
 * @author lsj
 */
@Slf4j
@Component
public class EvidenceEditEventWorkHandler implements WorkHandler<EvidenceEditEvent> {

    /**
     * 加 @Lazy 打破循环依赖：
     * EvidenceEditHandler -> EvidenceEditEventProducer -> Disruptor -> 本类 -> EvidenceEditHandler
     */
    @Resource
    @Lazy
    private EvidenceEditHandler evidenceEditHandler;

    @Resource
    private ObjectMapper objectMapper;

    @Override
    public void onEvent(EvidenceEditEvent event) throws Exception {
        try {
            EvidenceEditRequestMessage evidenceEditRequestMessage = event.getEvidenceEditRequestMessage();
            WebSocketSession session = event.getSession();
            User user = event.getUser();
            Long evidenceId = event.getEvidenceId();
            // 获取消息类别
            String type = evidenceEditRequestMessage.getType();
            EvidenceEditMessageTypeEnum evidenceEditMessageTypeEnum = EvidenceEditMessageTypeEnum.getEnumByValue(type);
            if (evidenceEditMessageTypeEnum == null) {
                sendErrorMessage(session, user, "消息类型错误");
                return;
            }
            EvidenceEditHandler.EvidenceEditContext evidenceEditContext =
                    new EvidenceEditHandler.EvidenceEditContext(evidenceEditRequestMessage, session, user, evidenceId);
            // 调用对应的消息处理方法
            switch (evidenceEditMessageTypeEnum) {
                case ENTER_EDIT:
                    evidenceEditHandler.handleEnterEditMessage(evidenceEditContext);
                    break;
                case EDIT_ACTION:
                    evidenceEditHandler.handleEditActionMessage(evidenceEditContext);
                    break;
                case EXIT_EDIT:
                    evidenceEditHandler.handleExitEditMessage(evidenceEditContext);
                    break;
                default:
                    sendErrorMessage(session, user, "消息类型错误");
            }
        } finally {
            event.clear();
        }
    }

    /**
     * 向客户端发送错误消息
     */
    private void sendErrorMessage(WebSocketSession session, User user, String message) throws Exception {
        if (session == null || !session.isOpen()) {
            return;
        }
        EvidenceEditResponseMessage evidenceEditResponseMessage = new EvidenceEditResponseMessage();
        evidenceEditResponseMessage.setType(EvidenceEditMessageTypeEnum.ERROR.getValue());
        evidenceEditResponseMessage.setMessage(message);
        evidenceEditResponseMessage.setUser(UserVO.entityToVo(user));
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(evidenceEditResponseMessage)));
    }
}
