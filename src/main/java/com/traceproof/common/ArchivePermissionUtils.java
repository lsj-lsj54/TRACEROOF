package com.traceproof.common;

import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.ArchiveUser;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.ArchiveRoleEnum;
import com.traceproof.model.enums.ArchiveTypeEnum;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;

import java.util.Arrays;
import java.util.Objects;

/**
 * 档案库权限校验工具类（数据级校验：这个档案库的归属是谁、这个用户在里面是什么角色）。
 * <p>
 * 与 Controller 上的注解鉴权分工：
 * <ul>
 *     <li>注解 {@code @SaArchiveCheckPermission} 在进入方法前，按请求里的 archiveId / evidenceId
 *     算出「当前用户在这个档案库里的角色权限码」，拦住越权调用；</li>
 *     <li>本工具类在业务层按数据库里的真实归属做二次校验，用于 service 内部复用、
 *     无法挂注解的入口（分页查询、分析、上传落库后等），也防止注解上下文解析不到时的漏判。</li>
 * </ul>
 * 规则：
 * <ul>
 *     <li>私有档案库：只有档案库创建者或系统管理员能看、能改；</li>
 *     <li>团队档案库：成员即可查看，admin / editor 角色的成员可操作。</li>
 * </ul>
 *
 * @author lsj
 */
public class ArchivePermissionUtils {

    private ArchivePermissionUtils() {
    }

    /**
     * 校验用户是否有操作该档案库的权限（仅档案库创建者或管理员）
     * <p>
     * 三参数版本保留给不区分档案库类型的调用方，默认按「私有档案库」规则校验。
     */
    public static void checkArchiveAuth(Archive archive, User loginUser, UserService userService) {
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        // 仅档案库创建者或管理员可操作
        if (!isCreator(archive, loginUser) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该档案库");
        }
    }

    /**
     * 校验用户是否有操作该档案库的权限（识别团队档案库）
     * <p>
     * 私有档案库规则不变；团队档案库中，角色为 admin / editor 的成员也可以操作。
     */
    public static void checkArchiveAuth(Archive archive, User loginUser, UserService userService,
                                      ArchiveUserService archiveUserService) {
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        // 档案库创建者或系统管理员直接放行
        if (isCreator(archive, loginUser) || userService.isAdmin(loginUser)) {
            return;
        }
        // 团队档案库：admin / editor 成员可操作
        if (isTeamArchive(archive)
                && hasAnyRole(archiveUserService, archive.getId(), loginUser.getId(),
                ArchiveRoleEnum.ADMIN.getValue(), ArchiveRoleEnum.EDITOR.getValue())) {
            return;
        }
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "无权限访问该档案库");
    }

    /**
     * 校验用户是否可以查看该档案库（仅档案库创建者或管理员）
     */
    public static void checkArchiveViewAuth(Archive archive, User loginUser, UserService userService) {
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        if (!isCreator(archive, loginUser) && !userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "不能查看别人的档案库");
        }
    }

    /**
     * 校验用户是否可以查看该档案库（识别团队档案库）
     * <p>
     * 团队档案库的所有成员（含 viewer）都可以查看档案库及其中的影像。
     */
    public static void checkArchiveViewAuth(Archive archive, User loginUser, UserService userService,
                                          ArchiveUserService archiveUserService) {
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        if (isCreator(archive, loginUser) || userService.isAdmin(loginUser)) {
            return;
        }
        // 团队档案库：任意角色的成员均可查看
        if (isTeamArchive(archive)
                && hasAnyRole(archiveUserService, archive.getId(), loginUser.getId(),
                ArchiveRoleEnum.VIEWER.getValue(), ArchiveRoleEnum.EDITOR.getValue(), ArchiveRoleEnum.ADMIN.getValue())) {
            return;
        }
        throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "不能查看别人的档案库");
    }

    /**
     * 当前登录用户是否为该档案库创建者
     */
    private static boolean isCreator(Archive archive, User loginUser) {
        // 直接比对档案库归属用户 id，最快也最可靠
        return loginUser.getId().equals(archive.getUserId());
    }

    /**
     * 是否为团队档案库
     */
    private static boolean isTeamArchive(Archive archive) {
        return Objects.equals(ArchiveTypeEnum.TEAM.getValue(), archive.getArchiveType());
    }

    /**
     * 用户在该档案库中的角色是否命中给定角色之一
     * <p>
     * 直接查 archive_user 表，不走 Sa-Token 权限码：这里需要的是「数据库里的真实角色」，
     * 而权限码依赖请求上下文（体里的 archiveId），在 service 内部不一定能取到。
     *
     * @param archiveUserService 档案库成员服务
     * @param archiveId          档案库 id
     * @param userId           用户 id
     * @param roles            允许的角色 value（viewer / editor / admin）
     */
    private static boolean hasAnyRole(ArchiveUserService archiveUserService, Long archiveId, Long userId, String... roles) {
        ArchiveUser archiveUser = archiveUserService.getArchiveUser(archiveId, userId);
        return archiveUser != null && Arrays.asList(roles).contains(archiveUser.getArchiveRole());
    }
}
