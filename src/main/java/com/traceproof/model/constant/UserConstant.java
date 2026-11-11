package com.traceproof.model.constant;

public interface UserConstant {

    //  region 权限

    /**
     * 默认角色
     */
    String DEFAULT_ROLE = "user";

    /**
     * 管理员角色
     */
    String ADMIN_ROLE = "admin";

    // endregion

    /**
     * 默认密码（管理员创建用户时使用）
     */
    String DEFAULT_PASSWORD = "123456";

    /**
     * 密码最小长度
     * <p>
     * 注册与登录必须共用这一个值：注册时校验 < 5 位、登录时校验 < 6 位，
     * 会导致 5 位密码「注册成功但登录永远提示密码错误」。
     */
    int MIN_PASSWORD_LENGTH = 5;

    /**
     * 昵称最大长度
     */
    int MAX_USER_NAME_LENGTH = 20;

    /**
     * 注册时未填写昵称的默认前缀，最终为「用户 + 6 位随机数」
     */
    String DEFAULT_USER_NAME_PREFIX = "用户";

    String USER_LOGIN_STATE="user_login_state";

}
