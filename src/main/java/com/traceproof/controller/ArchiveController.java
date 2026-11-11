package com.traceproof.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import cn.hutool.core.util.StrUtil;
import com.traceproof.annotation.AuthCheck;
import com.traceproof.common.BaseResponse;
import com.traceproof.common.ResultUtils;
import com.traceproof.common.ArchivePermissionUtils;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.auth.annotation.SaArchiveCheckPermission;
import com.traceproof.model.constant.ArchiveUserPermissionConstant;
import com.traceproof.model.constant.UserConstant;
import com.traceproof.model.dto.archive.ArchiveAddRequest;
import com.traceproof.model.dto.archive.ArchiveDeleteRequest;
import com.traceproof.model.dto.archive.ArchiveEditRequest;
import com.traceproof.model.dto.archive.ArchiveQueryRequest;
import com.traceproof.model.dto.archive.ArchiveUpdateRequest;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.ArchiveLevelEnum;
import com.traceproof.model.vo.ArchiveVO;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 档案库模块接口
 * <p>
 * 档案库是用户的私有容器：只有档案库创建者（或管理员）能查看、修改、删除自己的档案库，
 * 档案库内的影像同样只对创建者可见，相关规则由 {@link ArchivePermissionUtils} 统一负责。
 *
 * @author lsj
 */
@Slf4j
@RestController
@RequestMapping("/archive")
@AllArgsConstructor
public class ArchiveController {

    private final ArchiveService archiveService;

    private final UserService userService;

    /**
     * 档案库成员服务：团队档案库的查看、编辑权限按成员角色判定
     */
    private final ArchiveUserService archiveUserService;

    /**
     * 创建档案库
     * <p>
     * 普通用户只能创建普通版档案库，专业版 / 旗舰版需要管理员身份。
     *
     * @param archiveAddRequest 档案库创建请求
     * @param request         request 请求
     * @return 新档案库 id
     */
    @PostMapping("/add")
    public BaseResponse<Long> addArchive(@RequestBody ArchiveAddRequest archiveAddRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(archiveAddRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        long newArchiveId = archiveService.addArchive(archiveAddRequest, loginUser, request);
        ThrowUtils.throwIf(newArchiveId <= 0, ErrorCode.OPERATION_ERROR, "创建档案库失败");
        return ResultUtils.success(newArchiveId);
    }

    /**
     * 更新档案库（仅管理员可用）
     *
     * @param archiveUpdateRequest 档案库更新请求
     * @return Boolean true 是成功
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateArchive(@RequestBody ArchiveUpdateRequest archiveUpdateRequest) {
        if (archiveUpdateRequest == null || archiveUpdateRequest.getId() == null
                || archiveUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 将实体类和 DTO 进行转换
        Archive archive = new Archive();
        BeanUtils.copyProperties(archiveUpdateRequest, archive);
        // 自动填充数据
        archiveService.fillArchiveByArchiveLevel(archive);
        // 数据校验
        archiveService.validArchive(archive, false);
        // 判断是否存在
        long id = archiveUpdateRequest.getId();
        Archive oldArchive = archiveService.getById(id);
        ThrowUtils.throwIf(oldArchive == null, ErrorCode.NOT_FOUND_ERROR);
        // 操作数据库
        boolean result = archiveService.updateById(archive);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 删除档案库（仅档案库创建者或管理员可用）
     * <p>
     * 档案库内的影像会被一并删除（含对象存储中的文件）。
     *
     * @param archiveDeleteRequest 档案库删除请求
     * @param request            request 请求
     * @return 是否删除成功
     */
    @PostMapping("/delete")
    public BaseResponse<Boolean> deleteArchive(@RequestBody ArchiveDeleteRequest archiveDeleteRequest,
                                             HttpServletRequest request) {
        ThrowUtils.throwIf(archiveDeleteRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        ThrowUtils.throwIf(!archiveService.deleteArchive(archiveDeleteRequest, loginUser),
                ErrorCode.SYSTEM_ERROR, "删除失败");
        return ResultUtils.success(true);
    }

    /**
     * 编辑档案库名称（仅档案库创建者或管理员可用）
     * <p>
     * 权限：{@code evidence:edit}，与「编辑档案库内容」同权——团队档案库的 editor / admin 成员可改名。
     *
     * @param archiveEditRequest 档案库编辑请求
     * @param request          request 请求
     * @return 是否编辑成功
     */
    @PostMapping("/edit")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_EDIT)
    public BaseResponse<Boolean> editArchive(@RequestBody ArchiveEditRequest archiveEditRequest,
                                           HttpServletRequest request) {
        if (archiveEditRequest == null || archiveEditRequest.getId() == null || archiveEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 改名不允许把名称留空：validArchive 只在创建时校验名称非空
        ThrowUtils.throwIf(StrUtil.isBlank(archiveEditRequest.getArchiveName()),
                ErrorCode.PARAMS_ERROR, "档案库名称不能为空");
        User loginUser = userService.getLoginUser(request);
        Archive oldArchive = archiveService.getById(archiveEditRequest.getId());
        ThrowUtils.throwIf(oldArchive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        // 私有档案库只能改自己的；团队档案库 admin / editor 成员也可以改名
        ArchivePermissionUtils.checkArchiveAuth(oldArchive, loginUser, userService, archiveUserService);
        Archive archive = new Archive();
        archive.setId(archiveEditRequest.getId());
        archive.setArchiveName(archiveEditRequest.getArchiveName());
        archive.setEditTime(new Date());
        archiveService.validArchive(archive, false);
        boolean result = archiveService.updateById(archive);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取档案库实体（仅管理员可用）
     *
     * @param id 档案库 id
     * @return 档案库实体
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Archive> getArchiveById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        Archive archive = archiveService.getById(id);
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(archive);
    }

    /**
     * 根据 id 获取档案库封装类（仅档案库创建者、成员或管理员可用）
     * <p>
     * 权限：{@code evidence:view}，团队档案库任意角色的成员都能查看档案库信息。
     *
     * @param id      档案库 id
     * @param request request 请求
     * @return 档案库封装类
     */
    @GetMapping("/get/vo")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_VIEW)
    public BaseResponse<ArchiveVO> getArchiveVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        Archive archive = archiveService.getById(id);
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        User loginUser = userService.getLoginUser(request);
        // 不能查看别人的私有档案库；团队档案库成员可查看
        ArchivePermissionUtils.checkArchiveViewAuth(archive, loginUser, userService, archiveUserService);
        return ResultUtils.success(archiveService.getArchiveVO(archive, request));
    }

    /**
     * 分页获取档案库实体列表（仅管理员可用）
     *
     * @param archiveQueryRequest 档案库查询请求
     * @return 档案库分页数据
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Archive>> listArchiveByPage(@RequestBody ArchiveQueryRequest archiveQueryRequest) {
        ThrowUtils.throwIf(archiveQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = archiveQueryRequest.getCurrent();
        long size = archiveQueryRequest.getPageSize();
        Page<Archive> archivePage = archiveService.page(new Page<>(current, size),
                archiveService.getQueryWrapper(archiveQueryRequest));
        return ResultUtils.success(archivePage);
    }

    /**
     * 分页获取档案库封装列表（仅管理员可用）
     *
     * @param archiveQueryRequest 档案库查询请求
     * @param request           request 请求
     * @return 档案库封装分页数据
     */
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<ArchiveVO>> listArchiveVOByPage(@RequestBody ArchiveQueryRequest archiveQueryRequest,
                                                         HttpServletRequest request) {
        ThrowUtils.throwIf(archiveQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = archiveQueryRequest.getCurrent();
        long size = archiveQueryRequest.getPageSize();
        Page<Archive> archivePage = archiveService.page(new Page<>(current, size),
                archiveService.getQueryWrapper(archiveQueryRequest));
        return ResultUtils.success(archiveService.getArchiveVOPage(archivePage, request));
    }

    /**
     * 分页获取当前用户自己的档案库列表
     * <p>
     * 用户 id 由后端从登录态写入，前端传什么都不作数，避免查到别人的档案库。
     *
     * @param archiveQueryRequest 档案库查询请求
     * @param request           request 请求
     * @return 档案库封装分页数据
     */
    @PostMapping("/my/list/page/vo")
    public BaseResponse<Page<ArchiveVO>> listMyArchiveVOByPage(@RequestBody ArchiveQueryRequest archiveQueryRequest,
                                                           HttpServletRequest request) {
        ThrowUtils.throwIf(archiveQueryRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        archiveQueryRequest.setUserId(loginUser.getId());
        long current = archiveQueryRequest.getCurrent();
        long size = archiveQueryRequest.getPageSize();
        Page<Archive> archivePage = archiveService.page(new Page<>(current, size),
                archiveService.getQueryWrapper(archiveQueryRequest));
        return ResultUtils.success(archiveService.getArchiveVOPage(archivePage, request));
    }

    /**
     * 获取档案库级别字典（创建档案库时给前端下拉框用）
     *
     * @return 级别列表：text / value / maxSize / maxCount
     */
    @GetMapping("/list/level")
    public BaseResponse<List<Map<String, Object>>> listArchiveLevel() {
        List<Map<String, Object>> levelList = Arrays.stream(ArchiveLevelEnum.values())
                .map(archiveLevelEnum -> {
                    Map<String, Object> map = new HashMap<>(4);
                    map.put("text", archiveLevelEnum.getText());
                    map.put("value", archiveLevelEnum.getValue());
                    map.put("maxSize", archiveLevelEnum.getMaxSize());
                    map.put("maxCount", archiveLevelEnum.getMaxCount());
                    return map;
                })
                .collect(Collectors.toList());
        return ResultUtils.success(levelList);
    }
}
