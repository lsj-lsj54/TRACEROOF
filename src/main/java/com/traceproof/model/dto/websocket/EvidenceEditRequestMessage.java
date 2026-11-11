package com.traceproof.model.dto.websocket;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 影像编辑 WebSocket 请求消息
 * <p>
 * 前端通过 WebSocket 连接发送该结构体，消息类型见 {@code EvidenceEditMessageTypeEnum}：
 * ENTER_EDIT / EXIT_EDIT / EDIT_ACTION。
 *
 * @author lsj
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceEditRequestMessage {

    /**
     * 消息类型，例如 "ENTER_EDIT", "EXIT_EDIT", "EDIT_ACTION"
     */
    private String type;

    /**
     * 执行的编辑动作，例如 "ZOOM_IN", "ROTATE_RIGHT"，见 {@code EvidenceEditActionEnum}
     */
    private String editAction;
}
