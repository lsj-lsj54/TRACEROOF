package com.traceproof.model.dto.user;

import lombok.Data;

import java.io.Serializable;

@Data
public class UserRegisterRequest implements Serializable {

    private static final long serialVersionUID = 3191241716120793L;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 用户昵称（可不填，不填时由后端生成「用户 + 随机数」）
     */
    private String userName;

    /**
     * 密码
     */
    private String userPassword;

    /**
     * 确认密码
     */
    private String checkPassword;
}
