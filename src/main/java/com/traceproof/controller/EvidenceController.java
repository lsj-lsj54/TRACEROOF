package com.traceproof.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceproof.annotation.AuthCheck;
import com.traceproof.annotation.RateLimit;
import com.traceproof.common.BaseResponse;
import com.traceproof.common.DeleteRequest;
import com.traceproof.common.EvidencePermissionUtils;
import com.traceproof.common.ResultUtils;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.auth.annotation.SaArchiveCheckPermission;
import com.traceproof.manager.EvidenceBloomFilter;
import com.traceproof.model.constant.ArchiveUserPermissionConstant;
import com.traceproof.model.constant.UserConstant;
import com.traceproof.model.dto.evidence.*;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.FileUploadEnum;
import com.traceproof.model.vo.EvidenceVO;
import com.traceproof.service.EvidenceService;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Date;

/**
 * 影像模块接口
 * <p>
 * 原先影像接口分散在 FileController 和 EvidenceServiceImpl 中，这里统一归位到影像模块自己的 Controller。
 * <p>
 * 档案库权限注解（{@link SaArchiveCheckPermission}）的挂载原则：
 * <ul>
 *     <li>写操作（上传 / 删除 / 编辑）都挂注解：进入方法前就按 archiveId 或 evidenceId 算角色权限码；</li>
 *     <li>列表与详情<strong>故意不挂</strong>：公共案例库允许匿名浏览，注解会连带强制登录；
 *     档案库内的可见性由 service 层按 archiveId 单独校验。</li>
 * </ul>
 *
 * @author lsj
 */
@RestController
@RequestMapping("/evidence")
public class EvidenceController {

    @Resource
    private EvidenceService evidenceService;

    @Resource
    private UserService userService;

    /**
     * 档案库服务：编辑影像时要校验影像是否在别人的私有档案库里
     */
    @Resource
    private ArchiveService archiveService;

    /**
     * 档案库成员服务：团队档案库的影像权限按成员角色判定
     */
    @Resource
    private ArchiveUserService archiveUserService;

    /**
     * 影像 id 布隆过滤器：查询前预判，防缓存穿透
     */
    @Resource
    private EvidenceBloomFilter evidenceBloomFilter;

    /**
     * 上传影像（本地文件，可重新上传）
     * <p>
     * 权限：{@code evidence:upload}。请求带 archiveId 时校验该档案库的上传权限；
     * 不带 archiveId（公共案例库 / 个人档案库）时没有档案库维度可校验，任意登录用户可上传。
     *
     * @param multipartFile        上传的影像文件
     * @param evidenceUploadRequest 影像上传请求，id 不为空时表示重新上传
     * @param request              request 请求
     * @return 影像封装类
     */
    @PostMapping("/upload")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_UPLOAD)
    @RateLimit(key = "upload", rate = 10, windowSeconds = 60)
    public BaseResponse<EvidenceVO> uploadEvidence(@RequestPart(value = "file", required = false) MultipartFile multipartFile,
                                                 EvidenceUploadRequest evidenceUploadRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(multipartFile == null || multipartFile.isEmpty(),
                ErrorCode.PARAMS_ERROR, "文件不能为空");
        User loginUser = userService.getLoginUser(request);
        EvidenceVO evidenceVO = evidenceService.uploadEvidence(multipartFile, evidenceUploadRequest, loginUser);
        return ResultUtils.success(evidenceVO);
    }

    /**
     * 上传影像（通过 URL，可重新上传）
     * <p>
     * 会把远程影像下载后转存到对象存储，走与本地文件相同的模板流程。
     *
     * @param evidenceUploadRequest 影像上传请求，必须携带 evidenceUrl
     * @param request              request 请求
     * @return 影像封装类
     */
    @PostMapping("/upload/url")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_UPLOAD)
    @RateLimit(key = "upload", rate = 10, windowSeconds = 60)
    public BaseResponse<EvidenceVO> uploadEvidenceByUrl(@RequestBody EvidenceUploadRequest evidenceUploadRequest,
                                                      HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceUploadRequest == null
                        || StrUtil.isBlank(evidenceUploadRequest.getEvidenceUrl()),
                ErrorCode.PARAMS_ERROR, "文件地址不能为空");
        // 显式声明了上传方式时做一次一致性校验，避免参数与实际内容对不上
        ThrowUtils.throwIf(FileUploadEnum.getEnumByTypeOrNull(evidenceUploadRequest.getUploadType())
                        == FileUploadEnum.FILE,
                ErrorCode.PARAMS_ERROR, "当前是 URL 上传接口，请改用文件上传接口");
        User loginUser = userService.getLoginUser(request);
        EvidenceVO evidenceVO = evidenceService.uploadEvidenceByUrl(evidenceUploadRequest, loginUser);
        return ResultUtils.success(evidenceVO);
    }

    /**
     * 删除影像
     * <p>
     * 权限：{@code evidence:delete}。影像在档案库里时按档案库角色校验（viewer 无此权限）；
     * 公共案例库的影像要求是上传者本人或管理员。
     *
     * @param deleteRequest 删除请求，包含影像 id
     * @param request       request 请求
     * @return 是否删除成功
     */
    @PostMapping("/delete")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_DELETE)
    public BaseResponse<Boolean> deleteEvidence(@RequestBody DeleteRequest deleteRequest, HttpServletRequest request) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User loginUser = userService.getLoginUser(request);
        long id = deleteRequest.getId();
        // 删库 + 档案库校验 + 释放档案库容量 + 清缓存 + 清对象存储
        evidenceService.deleteEvidence(id, loginUser);
        return ResultUtils.success(true);
    }

    /**
     * 更新影像（仅管理员可用）
     *
     * @param evidenceUpdateRequest 影像更新请求
     * @param request              request 请求
     * @return 是否更新成功
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateEvidence(@RequestBody EvidenceUpdateRequest evidenceUpdateRequest,
                                               HttpServletRequest request) {
        if (evidenceUpdateRequest == null || evidenceUpdateRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 将实体类和 DTO 进行转换
        Evidence evidence = new Evidence();
        BeanUtils.copyProperties(evidenceUpdateRequest, evidence);
        // 注意将 list 转为 string
        evidence.setTags(JSONUtil.toJsonStr(evidenceUpdateRequest.getTags()));
        // 数据校验
        evidenceService.validEvidence(evidence);
        // 判断是否存在
        long id = evidenceUpdateRequest.getId();
        Evidence oldEvidence = evidenceService.getById(id);
        ThrowUtils.throwIf(oldEvidence == null, ErrorCode.NOT_FOUND_ERROR);
        // 补充审核参数
        User loginUser = userService.getLoginUser(request);
        evidenceService.fillReviewParams(evidence, loginUser);
        // 操作数据库
        boolean result = evidenceService.updateById(evidence);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 更新后清缓存
        evidenceService.clearEvidenceCache();
        return ResultUtils.success(true);
    }

    /**
     * 根据 id 获取影像（仅管理员可用）
     *
     * @param id 影像 id
     * @return 影像实体类
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Evidence> getEvidenceById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 布隆过滤器预判：id 一定不存在时直接返回，不查库（防缓存穿透）
        ThrowUtils.throwIf(!evidenceBloomFilter.mightContain(id), ErrorCode.NOT_FOUND_ERROR);
        // 查询数据库
        Evidence evidence = evidenceService.getById(id);
        ThrowUtils.throwIf(evidence == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(evidence);
    }

    /**
     * 根据 id 获取影像（封装类）
     *
     * @param id      影像 id
     * @param request request 请求
     * @return 影像封装类
     */
    @GetMapping("/get/vo")
    public BaseResponse<EvidenceVO> getEvidenceVOById(long id, HttpServletRequest request) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        // 布隆过滤器预判：id 一定不存在时直接返回，不查库（防缓存穿透）
        ThrowUtils.throwIf(!evidenceBloomFilter.mightContain(id), ErrorCode.NOT_FOUND_ERROR);
        // 查询数据库
        Evidence evidence = evidenceService.getById(id);
        ThrowUtils.throwIf(evidence == null, ErrorCode.NOT_FOUND_ERROR);
        // 获取封装类
        return ResultUtils.success(evidenceService.getEvidenceVO(evidence, request));
    }

    /**
     * 分页获取影像列表（仅管理员可用）
     *
     * @param evidenceQueryRequest 影像查询请求
     * @return 影像分页数据
     */
    @PostMapping("/list/page")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<Evidence>> listEvidenceByPage(@RequestBody EvidenceQueryRequest evidenceQueryRequest) {
        ThrowUtils.throwIf(evidenceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = evidenceQueryRequest.getCurrent();
        long size = evidenceQueryRequest.getPageSize();
        // 查询数据库
        Page<Evidence> evidencePage = evidenceService.page(new Page<>(current, size),
                evidenceService.getQueryWrapper(evidenceQueryRequest));
        return ResultUtils.success(evidencePage);
    }

    /**
     * 分页获取影像列表（封装类）
     * <p>
     * 可分页查询公共案例库（archiveId 为空，仅已过审影像）或自己的私有档案库（archiveId 不为空，
     * 只有档案库创建者能查），其他用户的档案库会被拒绝。
     *
     * @param evidenceQueryRequest 影像查询请求
     * @param request             request 请求
     * @return 影像封装类分页数据
     */
    @PostMapping("/list/page/vo")
    public BaseResponse<Page<EvidenceVO>> listEvidenceVOByPage(@RequestBody EvidenceQueryRequest evidenceQueryRequest,
                                                             HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        // 档案库校验与查询条件都在 service 内统一处理
        return ResultUtils.success(evidenceService.listEvidenceVOByPage(evidenceQueryRequest, request));
    }

    /**
     * 分页获取影像列表（封装类，带多级缓存）
     * <p>
     * 与 /list/page/vo 的区别是多了一层 Caffeine + Redis 缓存，
     * 适合首页这类「同样条件被反复查询」的场景。
     *
     * @param evidenceQueryRequest 影像查询请求
     * @param request             request 请求
     * @return 影像封装类分页数据
     */
    @PostMapping("/list/page/vo/cache")
    public BaseResponse<Page<EvidenceVO>> listEvidenceVOByPageWithCache(
            @RequestBody EvidenceQueryRequest evidenceQueryRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        return ResultUtils.success(
                evidenceService.listEvidenceVOByPageWithCache(evidenceQueryRequest, request));
    }

    /**
     * 编辑影像（给用户使用）
     * <p>
     * 权限：{@code evidence:edit}。档案库内的影像按档案库角色校验（viewer 无此权限），
     * 公共案例库的影像要求是上传者本人或管理员。
     *
     * @param evidenceEditRequest 影像编辑请求
     * @param request            request 请求
     * @return 是否编辑成功
     */
    @PostMapping("/edit")
    @SaArchiveCheckPermission(ArchiveUserPermissionConstant.EVIDENCE_EDIT)
    public BaseResponse<Boolean> editEvidence(@RequestBody EvidenceEditRequest evidenceEditRequest,
                                             HttpServletRequest request) {
        if (evidenceEditRequest == null || evidenceEditRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 在此处将实体类和 DTO 进行转换
        Evidence evidence = new Evidence();
        BeanUtils.copyProperties(evidenceEditRequest, evidence);
        // 注意将 list 转为 string
        evidence.setTags(JSONUtil.toJsonStr(evidenceEditRequest.getTags()));
        // 设置编辑时间
        evidence.setEditTime(new Date());
        // 数据校验
        evidenceService.validEvidence(evidence);
        User loginUser = userService.getLoginUser(request);
        // 判断是否存在
        long id = evidenceEditRequest.getId();
        Evidence oldEvidence = evidenceService.getById(id);
        ThrowUtils.throwIf(oldEvidence == null, ErrorCode.NOT_FOUND_ERROR);
        // 仅本人或管理员可编辑；影像在档案库里时还要满足档案库的权限规则（团队档案库按成员角色放行）
        EvidencePermissionUtils.checkEvidenceAuth(oldEvidence, loginUser, userService, archiveService, archiveUserService);
        // 补充审核参数
        evidenceService.fillReviewParams(evidence, loginUser);
        // 操作数据库
        boolean result = evidenceService.updateById(evidence);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 编辑后清缓存（编辑会把影像打回待审核，列表结果随之变化）
        evidenceService.clearEvidenceCache();
        return ResultUtils.success(true);
    }

    /**
     * 影像审核（仅管理员可用）
     *
     * @param evidenceReviewRequest 影像审核请求
     * @param request              request 请求
     * @return 是否审核成功
     */
    @PostMapping("/review")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> doEvidenceReview(@RequestBody EvidenceReviewRequest evidenceReviewRequest,
                                                 HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceReviewRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        evidenceService.doEvidenceReview(evidenceReviewRequest, loginUser);
        return ResultUtils.success(true);
    }


    @PostMapping("/upload/batch")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> uploadEvidenceByBatch(
            @RequestBody EvidenceUploadByBatchRequest evidenceUploadByBatchRequest,
            HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceUploadByBatchRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        int uploadCount = evidenceService.uploadEvidenceByBatch(evidenceUploadByBatchRequest, loginUser);
        return ResultUtils.success(uploadCount);
    }

    /**
     * 修复存量影像的对象存储 ACL（设为公有读）
     * <p>
     * 历史上传的影像在私有桶里没有单独开公有读，浏览器直接访问 URL 会 403 导致裂图。
     * 该接口遍历全部影像记录补设 ACL，仅需执行一次，之后新上传的影像会自动带上公有读。
     */
    @PostMapping("/fix/acl")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Integer> fixEvidenceAcl(HttpServletRequest request) {
        userService.getLoginUser(request);
        int fixed = evidenceService.fixAllEvidenceAcl();
        return ResultUtils.success(fixed);
    }

}
