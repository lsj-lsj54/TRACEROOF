package com.traceproof.conifg;

import com.lmax.disruptor.dsl.Disruptor;
import com.traceproof.websocket.EvidenceEditEvent;
import com.traceproof.websocket.EvidenceEditEventWorkHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.annotation.Resource;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 影像编辑事件 Disruptor 配置
 * <p>
 * 用一个 RingBuffer + WorkerPool 消费 WebSocket 编辑消息，把消息处理从
 * WebSocket 的收发线程里解耦出来，避免高频编辑动作阻塞连接线程。
 *
 * @author lsj
 */
@Configuration
public class EvidenceEditEventDisruptorConfig {

    @Resource
    private EvidenceEditEventWorkHandler evidenceEditEventWorkHandler;

    @Bean("evidenceEditEventDisruptor")
    public Disruptor<EvidenceEditEvent> evidenceEditEventDisruptor() {
        // ringBuffer 的大小，必须是 2 的 N 次方
        int bufferSize = 1024 * 256;
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger index = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r, "evidenceEditEventDisruptor-" + index.getAndIncrement());
                thread.setDaemon(false);
                return thread;
            }
        };
        Disruptor<EvidenceEditEvent> disruptor = new Disruptor<>(
                EvidenceEditEvent::new,
                bufferSize,
                threadFactory
        );
        // 设置消费者（WorkerPool 允许多个 worker 并发消费，EvidenceEditEventWorkHandler 内部按影像 id 隔离状态）
        disruptor.handleEventsWithWorkerPool(evidenceEditEventWorkHandler);
        // 开启 disruptor
        disruptor.start();
        return disruptor;
    }
}
