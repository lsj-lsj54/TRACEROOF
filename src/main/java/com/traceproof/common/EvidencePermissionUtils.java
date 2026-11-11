package com.traceproof.common;

import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;

/**
 * 影像权限校验工具类
 * <p>
 * 影像模块的删除、编辑、重新上传都要判断「能不能动这张图」，
 * 该逻辑原先在多个方法中重复出现，这里统一提取为公共方法，避免重复代码。
 * <p>
 * 权限规则：
 * <ul>
 *     <li>管理员：全部放行</li>
 *     <li>公共档案库的影像（archiveId 为空）：仅上传者本人可操作</li>
 *     <li>私有档案库的影像（archiveId 不为空）：仅该档案库的创建者可操作，别人档案库里的影像一律拒绝</li>
 *     <li>团队档案库的影像：档案库中 admin / editor 角色的成员也可操作</li>
 * </ul>
 *
 * @author lsj
 */
public class EvidencePermissionUtils {

    private EvidencePermissionUtils() {
    }

    /**
     * 校验用户是否有操作该影像的权限
     *
     * @param evidence          evidence 影像
     * @param loginUser        登录的用户
     * @param userService      用户服务，用于判断当前用户是否为管理员
     * @param archiveService     档案库服务，用于查询影像所属档案库
     * @param archiveUserService 档案库成员服务，用于识别团队档案库成员角色
     */
    public static void checkEvidenceAuth(Evidence evidence, User loginUser, UserService userService,
                                        ArchiveService archiveService, ArchiveUserService archiveUserService) {
        ThrowUtils.throwIf(evidence == null, ErrorCode.PARAMS_ERROR, "影像不能为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR);
        Long archiveId = evidence.getArchiveId();
        if (archiveId != null && archiveId > 0) {
            // 档案库影像：私有档案库仅创建者可操作；团队档案库按成员角色放行，复用档案库的权限规则
            Archive archive = archiveService.getById(archiveId);
            ArchivePermissionUtils.checkArchiveAuth(archive, loginUser, userService, archiveUserService);
            return;
        }
        // 公共案例库（archiveId=0）：上传者本人直接放行，其余人只有管理员能操作
        if (loginUser.getId().equals(evidence.getUserId())) {
            return;
        }
        if (!userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR);
        }
    }
}
