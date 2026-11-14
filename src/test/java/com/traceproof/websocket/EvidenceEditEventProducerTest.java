package com.traceproof.websocket;

import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.Sequence;
import com.lmax.disruptor.dsl.Disruptor;
import com.traceproof.model.dto.websocket.EvidenceEditRequestMessage;
import com.traceproof.model.entity.User;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EvidenceEditEventProducerTest {

    @Test
    @SuppressWarnings("unchecked")
    void publishShouldPopulateAndPublishEvent() throws Exception {
        Disruptor<EvidenceEditEvent> disruptor = mock(Disruptor.class);
        RingBuffer<EvidenceEditEvent> ringBuffer = RingBuffer.createSingleProducer(EvidenceEditEvent::new, 1);
        when(disruptor.getRingBuffer()).thenReturn(ringBuffer);
        EvidenceEditEventProducer producer = producer(disruptor);

        EvidenceEditRequestMessage request = new EvidenceEditRequestMessage();
        User user = new User();
        boolean accepted = producer.publishEvent(request, null, user, 99L);

        assertTrue(accepted);
        EvidenceEditEvent event = ringBuffer.get(0L);
        assertSame(request, event.getEvidenceEditRequestMessage());
        assertSame(user, event.getUser());
        assertSame(99L, event.getEvidenceId());
    }

    @Test
    @SuppressWarnings("unchecked")
    void fullQueueShouldFailFastWithoutBlocking() throws Exception {
        Disruptor<EvidenceEditEvent> disruptor = mock(Disruptor.class);
        RingBuffer<EvidenceEditEvent> ringBuffer = RingBuffer.createSingleProducer(EvidenceEditEvent::new, 2);
        ringBuffer.addGatingSequences(new Sequence(-1));
        when(disruptor.getRingBuffer()).thenReturn(ringBuffer);
        // 模拟停滞的消费者：两个槽位都占用后下一次 tryNext 必须立即失败。
        for (int i = 0; i < 2; i++) {
            long sequence = ringBuffer.next();
            ringBuffer.publish(sequence);
        }
        EvidenceEditEventProducer producer = producer(disruptor);

        boolean accepted = producer.publishEvent(new EvidenceEditRequestMessage(), null, new User(), 99L);

        assertFalse(accepted);
    }

    private EvidenceEditEventProducer producer(Disruptor<EvidenceEditEvent> disruptor) throws Exception {
        EvidenceEditEventProducer producer = new EvidenceEditEventProducer();
        Field field = EvidenceEditEventProducer.class.getDeclaredField("evidenceEditEventDisruptor");
        field.setAccessible(true);
        field.set(producer, disruptor);
        return producer;
    }
}
