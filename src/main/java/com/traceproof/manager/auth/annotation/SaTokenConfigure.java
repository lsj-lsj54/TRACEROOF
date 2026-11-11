package com.traceproof.manager.auth.annotation;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.strategy.SaAnnotationStrategy;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import javax.annotation.PostConstruct;

/**
 * Sa-Token 配置：注册注解鉴权拦截器 + 开启注解别名合并
 */
@Configuration
public class SaTokenConfigure implements WebMvcConfigurer {

    /**
     * 注册 Sa-Token 拦截器，打开注解式鉴权功能
     * <p>
     * 只有注册了它，Controller 上的 {@code @SaCheckPermission} / {@code @SaArchiveCheckPermission}
     * 才会被解析执行；拦截器本身不做「必须登录」的全局校验，公开接口不受影响。
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");
    }

    /**
     * 重写 Sa-Token 的注解解析策略，开启注解合并（{@code @AliasFor}）
     * <p>
     * Sa-Token 默认只做 {@code element.getAnnotation(...)}，既不解析元注解、也不合并别名，
     * 于是 {@code @SaArchiveCheckPermission(value = "xxx")} 里的 value 会丢失，
     * 校验退化成空权限数组直接放行。换成 Spring 的 {@code getMergedAnnotation}
     * 才能把别名值合并到 {@code @SaCheckPermission} 上。
     * <p>
     * 注意：1.39 起注解相关策略从 {@code SaStrategy} 拆到了 {@link SaAnnotationStrategy}，
     * {@code SaStrategy.instance} 上已经没有 {@code getAnnotation} 字段，覆盖错了会直接编译不过。
     */
    @PostConstruct
    public void rewriteSaStrategy() {
        SaAnnotationStrategy.instance.getAnnotation = AnnotatedElementUtils::getMergedAnnotation;
    }
}
