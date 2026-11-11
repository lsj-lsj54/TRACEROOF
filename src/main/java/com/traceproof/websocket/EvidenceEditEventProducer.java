package com.traceproof.websocket;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.dsl.Disruptor;
import com.traceproof.model.dto.websocket.EvidenceEditRequestMessage;
import com.traceproof.model.entity.User;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketSession;

import javax.annotation.PreDestroy;
import javax.annotation.Resource;

/**
 * 影像编辑事件生产者，把 WebSocket 收到的消息发布到 Disruptor RingBuffer
 *
 * @author lsj
 */
@Slf4j
@Component
public class EvidenceEditEventProducer {

    @Resource
    private Disruptor<EvidenceEditEvent> evidenceEditEventDisruptor;

    public void publishEvent(EvidenceEditRequestMessage evidenceEditRequestMessage, WebSocketSession session, User user, Long evidenceId) {
        RingBuffer<EvidenceEditEvent> ringBuffer = evidenceEditEventDisruptor.getRingBuffer();
        // 获取可以生成的位置
        long next = ringBuffer.next();
        try {
            EvidenceEditEvent evidenceEditEvent = ringBuffer.get(next);
            evidenceEditEvent.setEvidenceEditRequestMessage(evidenceEditRequestMessage);
            evidenceEditEvent.setSession(session);
            evidenceEditEvent.setUser(user);
            evidenceEditEvent.setEvidenceId(evidenceId);
        } finally {
            // 发布事件（无论中间是否出错都要 publish，避免 RingBuffer 序号被占用后不释放）
            ringBuffer.publish(next);
        }
    }

    /**
     * 优雅停机
     */
    @PreDestroy
    public void close() {
        evidenceEditEventDisruptor.shutdown();
    }
}
