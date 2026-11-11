package com.traceproof.model.enums;

import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import lombok.Getter;

/**
 * 影像上传方式
 *
 * @author lsj
 */
@Getter
public enum FileUploadEnum {

    /** 本地文件上传 */
    FILE("file"),

    /** 通过 URL 上传 */
    URL("url");

    /**
     * 本地文件上传的 bean 名 / 类型标识
     */
    public static final String FILE_TYPE = "file";

    /**
     * URL 上传的 bean 名 / 类型标识
     */
    public static final String URL_TYPE = "url";

    private final String type;

    FileUploadEnum(String type) {
        this.type = type;
    }

    /**
     * 根据类型标识获取枚举
     *
     * @param type 类型标识，file / url
     * @return 对应枚举
     */
    public static FileUploadEnum getEnumByType(String type) {
        for (FileUploadEnum fileUploadEnum : FileUploadEnum.values()) {
            if (fileUploadEnum.getType().equals(type)) {
                return fileUploadEnum;
            }
        }
        throw new BusinessException(ErrorCode.PARAMS_ERROR, "不支持的上传类型：" + type);
    }

    /**
     * 根据类型标识获取枚举，取不到返回 null
     *
     * @param type 类型标识
     * @return 对应枚举，不存在时返回 null
     */
    public static FileUploadEnum getEnumByTypeOrNull(String type) {
        for (FileUploadEnum fileUploadEnum : FileUploadEnum.values()) {
            if (fileUploadEnum.getType().equals(type)) {
                return fileUploadEnum;
            }
        }
        return null;
    }

    /**
     * 校验类型标识是否受支持
     *
     * @param type 类型标识
     * @return 校验通过时原样返回
     */
    public static String getType(String type) {
        return getEnumByType(type).getType();
    }
}
