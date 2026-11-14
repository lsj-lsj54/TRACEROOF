package com.traceproof.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.traceproof.model.dto.websocket.EvidenceEditRequestMessage;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.EvidenceEditActionEnum;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EvidenceEditHandlerTest {

    @Test
    void latestAbsoluteSnapshotShouldReplacePreviousState() throws Exception {
        EvidenceEditHandler handler = newHandler();
        EvidenceEditRequestMessage request = actionRequest(1.1, 90.0);
        EvidenceEditHandler.EvidenceEditContext context = context(handler, request, 100L);
        handler.handleEnterEditMessage(context);
        handler.handleEditActionMessage(context);

        request.setScale(1.2);
        request.setRotation(180.0);
        handler.handleEditActionMessage(context);

        Map<Long, TextMessage> snapshots = getSnapshotMap(handler);
        String payload = snapshots.get(100L).getPayload();
        assertTrue(payload.contains("\"scale\":1.2"), payload);
        assertTrue(payload.contains("\"rotation\":180.0"), payload);

        handler.handleExitEditMessage(context);
        assertTrue(snapshots.isEmpty(), "会话结束后快照也应清理");
    }

    @Test
    void legacyActionShouldBeRejectedWithoutOverwritingSnapshot() throws Exception {
        EvidenceEditHandler handler = newHandler();
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        EvidenceEditRequestMessage request = actionRequest(1.1, 90.0);
        EvidenceEditHandler.EvidenceEditContext context = context(handler, request, session, 100L);
        handler.handleEnterEditMessage(context);
        handler.handleEditActionMessage(context);
        String before = getSnapshotMap(handler).get(100L).getPayload();

        request.setScale(null);
        request.setRotation(null);
        handler.handleEditActionMessage(context);

        ArgumentCaptor<TextMessage> error = ArgumentCaptor.forClass(TextMessage.class);
        verify(session).sendMessage(error.capture());
        assertTrue(error.getValue().getPayload().contains("版本过旧"));
        assertEquals(before, getSnapshotMap(handler).get(100L).getPayload());
    }

    @Test
    void newConnectionShouldReceiveSnapshotBeforeJoinBroadcast() throws Exception {
        EvidenceEditHandler handler = newHandler();
        EvidenceEditRequestMessage request = actionRequest(1.3, 270.0);
        EvidenceEditHandler.EvidenceEditContext context = context(handler, request, 100L);
        handler.handleEnterEditMessage(context);
        handler.handleEditActionMessage(context);

        User newcomer = user(2L, "新用户");
        WebSocketSession session = mock(WebSocketSession.class);
        Map<String, Object> attributes = new HashMap<>();
        attributes.put("user", newcomer);
        attributes.put("evidenceId", 100L);
        when(session.getAttributes()).thenReturn(attributes);
        when(session.isOpen()).thenReturn(true);

        handler.afterConnectionEstablished(session);

        ArgumentCaptor<TextMessage> messages = ArgumentCaptor.forClass(TextMessage.class);
        InOrder order = inOrder(session);
        order.verify(session).sendMessage(messages.capture());
        order.verify(session).sendMessage(messages.capture());
        assertEquals(2, messages.getAllValues().size());
        assertTrue(messages.getAllValues().get(0).getPayload().contains("\"scale\":1.3"));
        assertTrue(messages.getAllValues().get(1).getPayload().contains("加入编辑"));
    }

    private EvidenceEditHandler newHandler() throws Exception {
        EvidenceEditHandler handler = new EvidenceEditHandler();
        setField(handler, "objectMapper", new ObjectMapper());
        return handler;
    }

    private EvidenceEditRequestMessage actionRequest(double scale, double rotation) {
        EvidenceEditRequestMessage request = new EvidenceEditRequestMessage();
        request.setEditAction(EvidenceEditActionEnum.ROTATE_RIGHT.getValue());
        request.setScale(scale);
        request.setRotation(rotation);
        return request;
    }

    private EvidenceEditHandler.EvidenceEditContext context(EvidenceEditHandler handler,
                                                            EvidenceEditRequestMessage request,
                                                            long evidenceId) {
        return context(handler, request, null, evidenceId);
    }

    private EvidenceEditHandler.EvidenceEditContext context(EvidenceEditHandler handler,
                                                            EvidenceEditRequestMessage request,
                                                            WebSocketSession session,
                                                            long evidenceId) {
        return new EvidenceEditHandler.EvidenceEditContext(request, session, user(1L, "编辑者"), evidenceId);
    }

    private User user(long id, String name) {
        User user = new User();
        user.setId(id);
        user.setUserName(name);
        return user;
    }

    @SuppressWarnings("unchecked")
    private Map<Long, TextMessage> getSnapshotMap(EvidenceEditHandler handler) throws Exception {
        Field field = EvidenceEditHandler.class.getDeclaredField("evidenceViewSnapshots");
        field.setAccessible(true);
        return (Map<Long, TextMessage>) field.get(handler);
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = EvidenceEditHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
