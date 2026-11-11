package com.traceproof.model.enums;

import lombok.Getter;

/**
 * 影像编辑动作枚举
 * <p>
 * 在线协同编辑时，编辑者执行的动作会通过 WebSocket 广播给同一张影像的其他用户。
 *
 * @author lsj
 */
@Getter
public enum EvidenceEditActionEnum {

    ZOOM_IN("放大操作", "ZOOM_IN"),
    ZOOM_OUT("缩小操作", "ZOOM_OUT"),
    ROTATE_LEFT("左旋操作", "ROTATE_LEFT"),
    ROTATE_RIGHT("右旋操作", "ROTATE_RIGHT");

    private final String text;
    private final String value;

    EvidenceEditActionEnum(String text, String value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值，不存在时返回 null
     */
    public static EvidenceEditActionEnum getEnumByValue(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        for (EvidenceEditActionEnum actionEnum : EvidenceEditActionEnum.values()) {
            if (actionEnum.value.equals(value)) {
                return actionEnum;
            }
        }
        return null;
    }
}
