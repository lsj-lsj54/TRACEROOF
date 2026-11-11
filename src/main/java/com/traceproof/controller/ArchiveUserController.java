package com.traceproof.controller;

import com.traceproof.common.BaseResponse;
import com.traceproof.common.DeleteRequest;
import com.traceproof.common.ResultUtils;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.auth.annotation.SaArchiveCheckPermission;
import com.traceproof.model.constant.ArchiveUserPermissionConstant;
import com.traceproof.model.dto.archiveuser.ArchiveUserAddRequest;
import com.traceproof.model.dto.archiveuser.ArchiveUserEditRequest;
import com.traceproof.model.dto.archiveuser.ArchiveUserQueryRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.ArchiveUserVO;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * 团队成员模块接口
 * <p>
 * 团队档案库（archiveType = 1）的成员管理：邀请、移除、调整角色、查看成员列表，
 * 以及查询「我加入的团队档案库」。成员权限由 Sa-Token 注解
 * {@link SaArchiveCheckPermission} 在入口处校验（权限码来自
 * {@link com.traceproof.manager.auth.StpInterfaceImpl}），业务规则见
 * {@link com.traceproof.service.ArchiveUserService} 各方法注释。
 *
 * @author lsj
 */
@Slf4j
@RestController
@RequestMapping("/archive/user")
@AllArgsConstructor
public class ArchiveUserController {

    private final ArchiveUserService archiveUserService;

    private final UserService userService;

    /**
     * 邀请用户加入团队档案库
     * <p>
     * 权限：{@code archiveUser:manage}，即档案库创建者或团队 admin 角色（系统管理员由业务层放行）。
     *
     * @param archiveUserAddRequest 邀请请求（archiveId / userId / archiveRole，角色缺省为 viewer）
     * @param request             request 请求
     * @return 新成员记录 id
     */
    @PostMapping("/add")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.ARCHIVE_USER_MANAGE)
    public BaseResponse<Long> addArchiveUser(@RequestBody ArchiveUserAddRequest archiveUserAddRequest,
                                           HttpServletRequest request) {
        ThrowUtils.throwIf(archiveUserAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long id = archiveUserService.addArchiveUser(archiveUserAddRequest, loginUser);
        return ResultUtils.success(id);
    }

    /**
     * 移除团队成员（成员也可用于退出团队）
     * <p>
     * 特意不加 {@code archiveUser:manage} 注解：退出团队是成员的自助操作，viewer 也需要能调用，
     * 「本人退出 / 管理者移除他人」的区分放在业务层判断。
     *
     * @param deleteRequest 成员记录 id
     * @param request       request 请求
     * @return 是否成功
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteArchiveUser(@RequestBody DeleteRequest deleteRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(deleteRequest == null || deleteRequest.getId() == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = archiveUserService.deleteArchiveUser(deleteRequest.getId(), loginUser);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "移除成员失败");
        return ResultUtils.success(true);
    }

    /**
     * 调整团队成员角色
     * <p>
     * 权限：{@code archiveUser:manage}，只有档案库创建者 / 团队 admin 可调整。
     *
     * @param archiveUserEditRequest 编辑请求（id / archiveRole）
     * @param request              request 请求
     * @return 是否成功
     */
    @PostMapping("/edit")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.ARCHIVE_USER_MANAGE)
    public BaseResponse<Boolean> editArchiveUser(@RequestBody ArchiveUserEditRequest archiveUserEditRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(archiveUserEditRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        boolean result = archiveUserService.editArchiveUser(archiveUserEditRequest, loginUser);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "调整角色失败");
        return ResultUtils.success(true);
    }

    /**
     * 查询某个档案库的成员列表
     * <p>
     * 权限：{@code evidence:view}，即该档案库的任意成员（含 viewer）都能看到成员列表。
     *
     * @param archiveUserQueryRequest 查询请求（archiveId 必填，可按角色筛选）
     * @param request               request 请求
     * @return 成员封装列表（含用户信息）
     */
    @PostMapping("/list")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_VIEW)
    public BaseResponse<List<ArchiveUserVO>> listArchiveUser(@RequestBody ArchiveUserQueryRequest archiveUserQueryRequest,
                                                         HttpServletRequest request) {
        ThrowUtils.throwIf(archiveUserQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveUserService.listArchiveUserVO(archiveUserQueryRequest, loginUser));
    }

    /**
     * 查询当前用户加入的团队档案库列表
     *
     * @param request request 请求
     * @return 我加入的团队档案库（含档案库信息与我的角色）
     */
    @PostMapping("/list/my")
    public BaseResponse<List<ArchiveUserVO>> listMyTeamArchive(HttpServletRequest request) {
        User loginUser = userService.getLoginUser(request);
        return ResultUtils.success(archiveUserService.listMyTeamArchive(loginUser));
    }
}
