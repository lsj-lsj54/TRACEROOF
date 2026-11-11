package com.traceproof.model.dto.websocket;

import com.traceproof.model.vo.UserVO;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 影像编辑 WebSocket 响应消息
 * <p>
 * 服务端通过 WebSocket 推送该结构体，消息类型见 {@code EvidenceEditMessageTypeEnum}。
 *
 * @author lsj
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EvidenceEditResponseMessage {

    /**
     * 消息类型，例如 "INFO", "ERROR", "ENTER_EDIT", "EXIT_EDIT", "EDIT_ACTION"
     */
    private String type;

    /**
     * 信息
     */
    private String message;

    /**
     * 执行的编辑动作
     */
    private String editAction;

    /**
     * 用户信息（脱敏）
     */
    private UserVO user;
}
