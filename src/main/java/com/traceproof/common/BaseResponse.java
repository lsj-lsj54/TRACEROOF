package com.traceproof.common;

import com.traceproof.exception.ErrorCode;
import lombok.Data;

import java.io.Serializable;

@Data
public class BaseResponse<T> implements Serializable {

    private int code;

    private T data;

    private String message;

    /**
     * 通过状态码、数据、信息构造响应
     *
     * @param code    状态码
     * @param data    数据
     * @param message 信息
     */
    public BaseResponse(int code, T data, String message) {
        this.code = code;
        this.data = data;
        this.message = message;
    }

    /**
     * 通过状态码和数据构造响应
     *
     * @param code 状态码
     * @param data 数据
     */
    public BaseResponse(int code, T data) {
        this(code, data, "");
    }

    /**
     * 通过错误码枚举构造响应
     *
     * @param errorCode 错误码枚举
     */
    public BaseResponse(ErrorCode errorCode) {
        this(errorCode.getCode(), null, errorCode.getMessage());
    }
}

