package com.traceproof.model.enums;

import lombok.Getter;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 档案库类型枚举
 * <p>
 * 私有档案库：仅创建者可见可用；团队档案库：创建者可邀请其他用户加入，
 * 成员按档案库角色（viewer/editor/admin）获得不同权限。
 *
 * @author lsj
 */
@Getter
public enum ArchiveTypeEnum {

    PRIVATE("私有", 0),
    TEAM("团队", 1);

    private final String text;

    private final int value;

    ArchiveTypeEnum(String text, int value) {
        this.text = text;
        this.value = value;
    }

    /**
     * 根据 value 获取枚举
     *
     * @param value 枚举值的 value
     * @return 枚举值，不存在时返回 null
     */
    public static ArchiveTypeEnum getEnumByValue(Integer value) {
        if (value == null) {
            return null;
        }
        for (ArchiveTypeEnum anEnum : ArchiveTypeEnum.values()) {
            if (anEnum.value == value) {
                return anEnum;
            }
        }
        return null;
    }

    /**
     * 获取所有枚举的文本列表
     *
     * @return 文本列表
     */
    public static List<String> getAllTexts() {
        return Arrays.stream(ArchiveTypeEnum.values())
                .map(ArchiveTypeEnum::getText)
                .collect(Collectors.toList());
    }

    /**
     * 获取所有枚举的值列表
     *
     * @return 值列表
     */
    public static List<Integer> getAllValues() {
        return Arrays.stream(ArchiveTypeEnum.values())
                .map(ArchiveTypeEnum::getValue)
                .collect(Collectors.toList());
    }
}
