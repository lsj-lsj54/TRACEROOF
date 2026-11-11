package com.traceproof.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口限流注解：配合 {@code RateLimitAspect} 使用
 * <p>
 * 挂在 Controller 方法上，基于 Redisson 的令牌桶（RRateLimiter）实现分布式限流，
 * 超过阈值直接抛出 429（{@code TOO_MANY_REQUESTS}）。
 *
 * @author lsj
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {

    /**
     * 限流维度 key。留空时退化为「方法全名」。
     * 例如上传接口写 "upload"，则所有用户共享一个上传限流桶。
     */
    String key() default "";

    /**
     * 在 windowSeconds 内允许的次数
     */
    long rate() default 10;

    /**
     * 时间窗口，单位秒
     */
    long windowSeconds() default 1;
}
