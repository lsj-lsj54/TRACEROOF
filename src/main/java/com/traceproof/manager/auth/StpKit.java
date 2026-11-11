package com.traceproof.manager.auth;

import cn.dev33.satoken.stp.StpLogic;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.stereotype.Component;

/**
 * StpLogic 门面类，管理项目中所有的 StpLogic 账号体系
 * 添加 @Component 注解的目的是确保静态属性 DEFAULT 和 ARCHIVE 被初始化
 */
@Component
public class StpKit {

    public static final String ARCHIVE_TYPE = "archive";

    /**
     * 默认原生会话对象，项目中目前没使用到
     */
    public static final StpLogic DEFAULT = StpUtil.stpLogic;

    /**
     * Archive 会话对象，管理 Archive 表所有账号的登录、权限认证
     */
    public static final StpLogic ARCHIVE = new StpLogic(ARCHIVE_TYPE);
}
