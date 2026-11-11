package com.traceproof.controller.aop;

import cn.hutool.core.util.StrUtil;
import com.traceproof.annotation.RateLimit;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 限流切面：拦截 {@link RateLimit} 标注的方法
 * <p>
 * 用 Redisson 的 RRateLimiter（令牌桶）做分布式限流——Redis 里共享同一个桶，
 * 多实例部署时限流阈值是「全集群」生效，而不是每台机器各自限流。
 *
 * @author lsj
 */
@Aspect
@Component
@Slf4j
public class RateLimitAspect {

    @Resource
    private RedissonClient redissonClient;

    /** 已经初始化过速率的 key，避免每个请求都重复调用 trySetRate 触发 Redis 往返 */
    private final Set<String> initializedKeys = ConcurrentHashMap.newKeySet();

    @Around("@annotation(rateLimit)")
    public Object doInterceptor(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 限流维度：注解指定 key 用指定 key（如 "upload"），否则退化为「类名.方法名」
        String key = rateLimit.key();
        if (StrUtil.isBlank(key)) {
            key = joinPoint.getSignature().getDeclaringTypeName() + "." + joinPoint.getSignature().getName();
        }
        String limiterKey = "rate:limit:" + key;
        RRateLimiter limiter = redissonClient.getRateLimiter(limiterKey);
        try {
            // 首次调用初始化令牌桶速率；RateType.OVERALL 表示全局限流、不区分客户端
            if (initializedKeys.add(limiterKey)) {
                limiter.trySetRate(RateType.OVERALL, rateLimit.rate(), rateLimit.windowSeconds(), RateIntervalUnit.SECONDS);
            }
            // 拿不到令牌说明超出阈值，直接拒绝，不再进入业务逻辑
            if (!limiter.tryAcquire()) {
                throw new BusinessException(ErrorCode.TOO_MANY_REQUESTS);
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            // Redis 不可用时限流降级为放行，不让限流器变成单点故障
            log.error("限流器不可用，降级放行，key = {}", limiterKey, e);
        }
        return joinPoint.proceed();
    }
}
