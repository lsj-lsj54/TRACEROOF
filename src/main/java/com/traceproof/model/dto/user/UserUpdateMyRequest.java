package com.traceproof.model.dto.user;

import lombok.Data;

import java.io.Serializable;

/**
 * 当前登录用户修改自己资料的请求
 * <p>
 * 只暴露普通用户有权修改的字段（昵称）。角色、账号等字段必须走管理员接口，
 * 否则前端传一个 userRole 就能把自己提权成管理员。
 *
 * @author lsj
 */
@Data
public class UserUpdateMyRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 用户昵称
     */
    private String userName;
}
