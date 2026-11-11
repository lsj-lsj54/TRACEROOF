package com.traceproof.common;

import cn.dev33.satoken.exception.NotLoginException;
import cn.dev33.satoken.exception.NotPermissionException;
import cn.dev33.satoken.exception.NotRoleException;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

/**
 * 全局异常处理
 * <p>
 * 注意：同一个异常类型只能有一个 {@code @ExceptionHandler} 方法。
 * 重复注册会在 Spring 解析处理器时抛出
 * {@code IllegalStateException: Ambiguous @ExceptionHandler method mapped for [...]}，
 * 表现为该异常一抛就 500，而不是返回统一响应。
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    /**
     * 处理业务异常
     *
     * @param e 业务异常
     * @return 统一响应，包含业务异常的错误码和信息
     */
    @ExceptionHandler(BusinessException.class)
    public BaseResponse<?> businessExceptionHandler(BusinessException e) {
        log.error("BusinessException", e);
        return ResultUtils.error(e.getCode(), e.getMessage());
    }

    /**
     * 处理 Sa-Token 未登录异常（未携带 token / token 无效 / 已过期 / 被顶下线）
     * <p>
     * 返回固定文案而不是 {@code e.getMessage()}：原始信息形如
     * 「未提供token」「token已过期」，属于框架内部措辞，不适合直接抛给前端展示。
     *
     * @param e 未登录异常
     * @return 统一响应，40100
     */
    @ExceptionHandler(NotLoginException.class)
    public BaseResponse<?> notLoginExceptionHandler(NotLoginException e) {
        log.warn("NotLoginException: type={}, message={}", e.getType(), e.getMessage());
        return ResultUtils.error(ErrorCode.NOT_LOGIN_ERROR, "未登录或登录已过期");
    }

    /**
     * 处理 Sa-Token 无权限异常（权限码校验失败）
     *
     * @param e 无权限异常
     * @return 统一响应，40101
     */
    @ExceptionHandler(NotPermissionException.class)
    public BaseResponse<?> notPermissionExceptionHandler(NotPermissionException e) {
        log.warn("NotPermissionException: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR, "无权限");
    }

    /**
     * 处理 Sa-Token 无角色异常（全局角色校验失败）
     *
     * @param e 无角色异常
     * @return 统一响应，40101
     */
    @ExceptionHandler(NotRoleException.class)
    public BaseResponse<?> notRoleExceptionHandler(NotRoleException e) {
        log.warn("NotRoleException: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.NO_AUTH_ERROR, "无权限");
    }

    /**
     * 处理上传文件过大的异常
     * <p>
     * 这类异常原本会被下面的 RuntimeException 分支吞掉，只返回笼统的「系统错误」，
     * 让人误以为是后端挂了。这里单独处理，把体积限制直接告知前端。
     * 相关配置见 application.yml 的 spring.servlet.multipart。
     *
     * @param e 上传体积超限异常
     * @return 统一响应，说明是文件过大
     */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public BaseResponse<?> maxUploadSizeExceptionHandler(MaxUploadSizeExceededException e) {
        log.error("MaxUploadSizeExceededException: {}", e.getMessage());
        return ResultUtils.error(ErrorCode.PARAMS_ERROR, "上传文件过大，请压缩后重试");
    }

    /**
     * 处理 Redis 连接失败异常（降级兜底）
     * <p>
     * Sa-Token 会话与列表缓存都依赖 Redis，Redis 不可用时若放任异常上抛，
     * 会被最下面的 RuntimeException 分支吞成笼统的「系统错误」。这里单独拦截，
     * 返回明确的服务不可用提示，方便用户与运维感知 Redis 状态。
     *
     * @param e Redis 连接失败异常
     * @return 统一响应，50300
     */
    @ExceptionHandler(RedisConnectionFailureException.class)
    public BaseResponse<?> redisConnectionFailureExceptionHandler(RedisConnectionFailureException e) {
        log.error("Redis 连接失败，会话与缓存暂不可用", e);
        return ResultUtils.error(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试");
    }

    /**
     * 处理数据访问异常的兜底分支
     * <p>
     * 这里必须区分来源：{@link DataAccessException} 同时是 Redis 异常和
     * MyBatis 转换后的 SQL 异常（唯一键冲突、连接失败等）的父类。
     * 只有确实源自 Redis 时才报 50300，否则一律按系统错误返回——
     * 把数据库约束冲突说成「服务暂时不可用」，会把人引去排查 Redis。
     *
     * @param e 数据访问异常
     * @return 统一响应
     */
    @ExceptionHandler(DataAccessException.class)
    public BaseResponse<?> dataAccessExceptionHandler(DataAccessException e) {
        if (isRedisRelated(e)) {
            log.error("Redis 访问异常，会话与缓存暂不可用", e);
            return ResultUtils.error(ErrorCode.SERVICE_UNAVAILABLE, "服务暂时不可用，请稍后重试");
        }
        log.error("数据访问异常", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    /**
     * 处理运行时异常
     *
     * @param e 运行时异常
     * @return 统一响应，统一返回系统错误
     */
    @ExceptionHandler(RuntimeException.class)
    public BaseResponse<?> runtimeExceptionHandler(RuntimeException e) {
        log.error("RuntimeException", e);
        return ResultUtils.error(ErrorCode.SYSTEM_ERROR, "系统错误");
    }

    /**
     * 沿 cause 链判断异常是否由 Redis 客户端抛出
     * <p>
     * 用包名前缀判断而不是比较具体类型：Lettuce / Jedis / Spring Data Redis
     * 各自有一串异常类，逐一定义既易漏又易随版本变化。
     */
    private static boolean isRedisRelated(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            String className = cause.getClass().getName();
            if (className.startsWith("org.springframework.data.redis")
                    || className.startsWith("io.lettuce")
                    || className.startsWith("redis.clients")) {
                return true;
            }
            if (cause.getCause() == cause) {
                break;
            }
        }
        return false;
    }

}
