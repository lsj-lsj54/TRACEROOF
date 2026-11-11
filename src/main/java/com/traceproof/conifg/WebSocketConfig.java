package com.traceproof.conifg;

import com.traceproof.websocket.EvidenceEditHandler;
import com.traceproof.websocket.WsHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

import javax.annotation.Resource;

/**
 * WebSocket 配置：注册影像在线编辑的处理器与握手拦截器
 * <p>
 * 访问路径为 {@code /ws/evidence/edit}，加上应用 context-path（/api）后，
 * 前端连接地址为 {@code ws://host:8666/api/ws/evidence/edit?evidenceId=xxx}。
 *
 * @author lsj
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    @Resource
    private EvidenceEditHandler evidenceEditHandler;

    @Resource
    private WsHandshakeInterceptor wsHandshakeInterceptor;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(evidenceEditHandler, "/ws/evidence/edit")
                .addInterceptors(wsHandshakeInterceptor)
                // 与 CorsConfig 一致：本地开发放行所有来源，生产按需收紧
                .setAllowedOriginPatterns("*");
    }
}
