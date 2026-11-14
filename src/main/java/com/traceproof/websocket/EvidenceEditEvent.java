package com.traceproof.websocket;

import com.traceproof.model.dto.websocket.EvidenceEditRequestMessage;
import com.traceproof.model.entity.User;
import lombok.Data;
import org.springframework.web.socket.WebSocketSession;

/**
 * 影像编辑事件，作为 Disruptor RingBuffer 中传递的数据对象
 *
 * @author lsj
 */
@Data
public class EvidenceEditEvent {

    /**
     * 消息
     */
    private EvidenceEditRequestMessage evidenceEditRequestMessage;

    /**
     * 当前用户的 session
     */
    private WebSocketSession session;

    /**
     * 当前用户
     */
    private User user;

    /**
     * 影像 id
     */
    private Long evidenceId;

    /**
     * 消费完成后释放 RingBuffer 槽位中的对象引用
     */
    public void clear() {
        this.evidenceEditRequestMessage = null;
        this.session = null;
        this.user = null;
        this.evidenceId = null;
    }
}
