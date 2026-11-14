package com.traceproof.service.impl;


import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.lang.TypeReference;
import cn.hutool.core.util.*;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.traceproof.common.EvidencePermissionUtils;
import com.traceproof.common.ArchivePermissionUtils;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.CosManager;
import cn.hutool.core.util.StrUtil;
import com.traceproof.manager.factory.UploadFactory;
import com.traceproof.manager.uplode.EvidenceUploadTemplate;
import com.traceproof.manager.EvidenceBloomFilter;
import com.traceproof.model.dto.evidence.EvidenceQueryRequest;
import com.traceproof.model.dto.evidence.EvidenceReviewRequest;
import com.traceproof.model.dto.evidence.EvidenceUploadByBatchRequest;
import com.traceproof.model.dto.evidence.EvidenceUploadRequest;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.mapper.EvidenceMapper;
import com.traceproof.model.dto.file.UploadEvidenceResult;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.enums.FileUploadEnum;
import com.traceproof.model.enums.EvidenceReviewStatusEnum;
import com.traceproof.model.vo.EvidenceVO;
import com.traceproof.model.vo.UserVO;
import com.traceproof.service.EvidenceService;
import com.traceproof.utils.ColorUtils;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.springframework.beans.BeanUtils;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.stereotype.Service;
import org.springframework.util.DigestUtils;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author lsj
 * @description 针对表【evidence(影像)】的数据库操作Service实现
 * @createDate 2026-10-12 10:48:24
 */
@Service
@Slf4j
public class EvidenceServiceImpl extends ServiceImpl<EvidenceMapper, Evidence>
        implements EvidenceService {

    /**
     * 批量抓取一次最多允许的影像数，避免一次请求打爆对象存储和数据库
     */
    private static final int MAX_BATCH_COUNT = 30;

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "url", "thumbnailUrl", "name", "introduction", "category", "tags",
            "picSize", "picWidth", "picHeight", "picScale", "picFormat", "userId",
            "reviewStatus", "reviewMessage", "reviewerId", "reviewTime", "archiveId",
            "picColor", "picColorR", "picColorG", "picColorB",
            "createTime", "editTime", "updateTime");

    private static final Set<String> SORT_ORDERS = Set.of("ascend", "descend");

    /**
     * 按主色调查询时的默认颜色距离阈值（0~441，值越大返回的颜色范围越宽）。
     * 含义：与目标颜色欧氏距离 <= 该值的影像才会被返回，并按距离升序排列。
     */
    private static final int DEFAULT_COLOR_DISTANCE = 80;

    /**
     * 抓取搜索页的超时时间，单位毫秒
     */
    private static final int FETCH_TIMEOUT_MILLIS = 10 * 1000;

    /**
     * 抓取搜索页使用的 UA，部分站点会拒绝空 UA 的请求
     */
    private static final String USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    @Resource
    private UserService userService;

    /**
     * 档案库服务：影像归属档案库后，上传、删除、查看都要做档案库校验，并维护档案库用量。
     * <p>
     * 档案库服务删除档案库时会反过来调用本服务清理档案库内的影像，
     * 循环依赖由 ArchiveServiceImpl 侧的 @Lazy 打断，这里正常注入即可。
     */
    @Resource
    private ArchiveService archiveService;

    /**
     * 档案库成员服务：团队档案库的影像权限按成员角色判定
     */
    @Resource
    private ArchiveUserService archiveUserService;


    @Resource
    private UploadFactory uploadFactory;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CosManager cosManager;

    /**
     * 影像 id 布隆过滤器：上传时写入，查询时防穿透
     */
    @Resource
    private EvidenceBloomFilter evidenceBloomFilter;

    /**
     * 影像列表缓存的 key 前缀
     */
    private static final String CACHE_KEY_PREFIX = "evidence:listVO:";

    /**
     * 缓存基础过期时间（秒）
     */
    private static final int CACHE_EXPIRE_BASE_SECONDS = 300;

    /**
     * 在基础过期时间上叠加的随机秒数，避免缓存同时失效
     */
    private static final int CACHE_EXPIRE_JITTER_SECONDS = 300;

    /**
     * 上传影像（本地文件）
     *
     * @param multipartFile        上传的影像文件
     * @param evidenceUploadRequest 影像上传请求
     * @param loginUser            当前登录的 user
     * @return evidence 的封装对象
     */
    @Override
    public EvidenceVO uploadEvidence(MultipartFile multipartFile, EvidenceUploadRequest evidenceUploadRequest,
                                   User loginUser) {
        ThrowUtils.throwIf(multipartFile == null || multipartFile.isEmpty(),
                ErrorCode.PARAMS_ERROR, "文件不能为空");
        return doUpload(multipartFile, FileUploadEnum.FILE_TYPE, evidenceUploadRequest, loginUser);
    }

    /**
     * 上传影像（通过 URL）
     *
     * @param evidenceUploadRequest 影像上传请求，必须携带 evidenceUrl
     * @param loginUser            当前登录的 user
     * @return evidence 的封装对象
     */
    @Override
    public EvidenceVO uploadEvidenceByUrl(EvidenceUploadRequest evidenceUploadRequest, User loginUser) {
        ThrowUtils.throwIf(evidenceUploadRequest == null
                        || StrUtil.isBlank(evidenceUploadRequest.getEvidenceUrl()),
                ErrorCode.PARAMS_ERROR, "文件地址不能为空");
        String evidenceUrl = evidenceUploadRequest.getEvidenceUrl().trim();
        return doUpload(evidenceUrl, FileUploadEnum.URL_TYPE, evidenceUploadRequest, loginUser);
    }

    /**
     * 上传影像的公共流程
     * <p>
     * 本地文件和 URL 两种方式共用：校验登录态 → 确定新增还是覆盖 → 校验操作权限 →
     * 交给对应模板上传 → 组装入库对象（含审核参数）→ 保存。
     *
     * @param inputSource          输入源：MultipartFile 或影像 URL
     * @param uploadType           上传方式，见 {@link FileUploadEnum}
     * @param evidenceUploadRequest 影像上传请求
     * @param loginUser            当前登录的 user
     * @return evidence 的封装对象
     */
    private EvidenceVO doUpload(Object inputSource, String uploadType,
                               EvidenceUploadRequest evidenceUploadRequest, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NOT_LOGIN_ERROR);

        // 判断是新增还是覆盖已有影像
        Long evidenceId = evidenceUploadRequest == null ? null : evidenceUploadRequest.getId();
        Evidence oldEvidence = null;
        if (evidenceId != null) {
            //先保留旧影像信息
            oldEvidence = this.getById(evidenceId);
            ThrowUtils.throwIf(oldEvidence == null, ErrorCode.NOT_FOUND_ERROR, "影像不存在");
            // 仅本人或管理员可覆盖；影像在档案库里时还要满足档案库的权限规则（团队档案库按成员角色放行）
            EvidencePermissionUtils.checkEvidenceAuth(oldEvidence, loginUser, userService, archiveService, archiveUserService);
        }

        // 目标档案库：archiveId 为 0（或空）表示公共案例库，>0 表示上传到对应的私有/团队档案库
        Long archiveId = evidenceUploadRequest == null ? null : evidenceUploadRequest.getArchiveId();
        // 归一化：公共案例库统一用 0 表示，前端可能传 null 也可能传 0
        archiveId = (archiveId == null || archiveId == 0) ? 0L : archiveId;
        if (archiveId == 0 && oldEvidence != null) {
            // 覆盖上传又没带 archiveId 时，保持影像原有归属，避免把私有档案库里的影像意外挪进公共案例库
            archiveId = oldEvidence.getArchiveId();
        }
        Long oldArchiveId = oldEvidence == null ? null : oldEvidence.getArchiveId();
        long oldSize = oldEvidence == null || oldEvidence.getPicSize() == null ? 0L : oldEvidence.getPicSize();
        // 档案库必须存在且属于当前用户（管理员除外）；公共案例库（archiveId=0）返回 null
        Archive archive = getAndCheckArchive(archiveId, loginUser);
        // 同一档案库内覆盖上传不会新增影像，被覆盖影像占用的容量也可以抵扣
        boolean sameArchive = archiveId > 0 && archiveId.equals(oldArchiveId);
        long countDelta = archiveId > 0 && !sameArchive ? 1L : 0L;
        // 数量先校验：档案库已经放不下时就不用白跑一趟上传了
        if (archive != null) {
            checkArchiveCount(archive, countDelta > 0);
        }

        // 按上传方式取出对应实现，按用户 id 划分目录
        String uploadPathPrefix = String.format("public/%s", loginUser.getId());
        EvidenceUploadTemplate uploadTemplate = uploadFactory.getUploadTemplate(uploadType);
        UploadEvidenceResult uploadEvidenceResult = uploadTemplate.uploadEvidence(inputSource, uploadPathPrefix);

        // 主色调：以腾讯云数据万象 imageAve 返回为准，获取失败不阻断上传
        String mainColor = cosManager.getMainColor(uploadEvidenceResult.getUrl());
        uploadEvidenceResult.setPicColor(mainColor);

        // 容量要等拿到真实文件大小才能算，超限就把刚上传的文件删掉，不给对象存储留垃圾
        long newSize = uploadEvidenceResult.getPicSize() == null ? 0L : uploadEvidenceResult.getPicSize();
        if (archive != null) {
            try {
                checkArchiveSize(archive, newSize, sameArchive ? oldSize : 0L);
            } catch (RuntimeException e) {
                deleteUploadedFile(uploadEvidenceResult);
                throw e;
            }
        }

        // 构造要入库的影像信息
        Evidence evidence = new Evidence();
        evidence.setUrl(uploadEvidenceResult.getUrl());
        // 缩略图：列表页用它，详情页用原图
        evidence.setThumbnailUrl(uploadEvidenceResult.getThumbnailUrl());
        // 允许调用方指定名称（批量抓取时用来生成「搜索词+序号」），未指定则用原始文件名
        String picName = uploadEvidenceResult.getPicName();
        if (evidenceUploadRequest != null && StrUtil.isNotBlank(evidenceUploadRequest.getPicName())) {
            picName = evidenceUploadRequest.getPicName();
        }
        evidence.setName(picName);
        evidence.setPicSize(uploadEvidenceResult.getPicSize());
        evidence.setPicWidth(uploadEvidenceResult.getPicWidth());
        evidence.setPicHeight(uploadEvidenceResult.getPicHeight());
        evidence.setPicScale(uploadEvidenceResult.getPicScale());
        evidence.setPicFormat(uploadEvidenceResult.getPicFormat());
        // 主色调：直接存腾讯云 imageAve 返回的颜色；历史或获取失败的影像该字段为空
        evidence.setPicColor(uploadEvidenceResult.getPicColor());
        if (mainColor != null) {
            int[] rgb = ColorUtils.hexToRgb(mainColor);
            evidence.setPicColorR(rgb[0]);
            evidence.setPicColorG(rgb[1]);
            evidence.setPicColorB(rgb[2]);
        }
        evidence.setUserId(loginUser.getId());
        // 归属档案库：前端据此区分公共案例库和私有档案库
        evidence.setArchiveId(archiveId);
        // 补充审核参数
        fillReviewParams(evidence, loginUser);

        // 如果是覆盖，需要补充 id 和编辑时间
        if (evidenceId != null) {
            evidence.setId(evidenceId);
            evidence.setEditTime(new Date());
        }
        boolean result = this.saveOrUpdate(evidence);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "影像上传失败");
        // 新影像 id 写入布隆过滤器，供查询防缓存穿透
        evidenceBloomFilter.add(evidence.getId());
        // 维护档案库用量：影像离开旧档案库要扣减，进入新档案库要累加；公共案例库（archiveId=0）不维护用量
        if (oldArchiveId != null && oldArchiveId > 0 && !oldArchiveId.equals(archiveId)) {
            archiveService.updateArchiveUsage(oldArchiveId, -1L, -oldSize);
        }
        if (archiveId != null && archiveId > 0) {
            long sizeDelta = newSize - (sameArchive ? oldSize : 0L);
            archiveService.updateArchiveUsage(archiveId, countDelta, sizeDelta);
        }
        // 数据变了，首页缓存要失效，否则会一直看到旧列表
        clearEvidenceCache();
        return EvidenceVO.entityToVo(evidence);
    }

    /**
     * 取出目标档案库并校验归属
     *
     * @param archiveId   档案库 id，0（或空）表示公共案例库
     * @param loginUser 当前登录用户
     * @return 目标档案库，archiveId 为 0 时返回 null
     */
    private Archive getAndCheckArchive(Long archiveId, User loginUser) {
        if (archiveId == null || archiveId == 0) {
            // 公共案例库不属于任何人，不需要校验
            return null;
        }
        Archive archive = archiveService.getById(archiveId);
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        // 私有档案库只能往自己的档案库里传图；团队档案库 admin / editor 成员也可以传
        ArchivePermissionUtils.checkArchiveAuth(archive, loginUser, userService, archiveUserService);
        return archive;
    }

    /**
     * 校验档案库还能不能再放下一张影像
     *
     * @param archive          目标档案库
     * @param countIncrement 本次上传是否会给档案库增加一条影像
     */
    private void checkArchiveCount(Archive archive, boolean countIncrement) {
        if (!countIncrement) {
            return;
        }
        long maxCount = archive.getMaxCount() == null ? 0L : archive.getMaxCount();
        long totalCount = archive.getTotalCount() == null ? 0L : archive.getTotalCount();
        ThrowUtils.throwIf(totalCount + 1 > maxCount, ErrorCode.OPERATION_ERROR,
                "档案库影像数量已满，请先清理档案库或升级档案库");
    }

    /**
     * 校验档案库容量是否还放得下这张影像
     *
     * @param archive        目标档案库
     * @param newSize      本次上传影像的大小（字节）
     * @param releasedSize 被覆盖影像原先占用的容量，覆盖同一档案库内的影像时可抵扣
     */
    private void checkArchiveSize(Archive archive, long newSize, long releasedSize) {
        long maxSize = archive.getMaxSize() == null ? 0L : archive.getMaxSize();
        long totalSize = archive.getTotalSize() == null ? 0L : archive.getTotalSize();
        ThrowUtils.throwIf(totalSize - releasedSize + newSize > maxSize, ErrorCode.OPERATION_ERROR,
                "档案库容量不足，请先清理档案库或升级档案库");
    }

    /**
     * 删除刚上传成功、但没能入库的对象存储文件
     * <p>
     * 容量校验失败等场景下用，避免对象存储里堆积没有任何记录引用的垃圾文件。
     *
     * @param uploadEvidenceResult 上传结果
     */
    private void deleteUploadedFile(UploadEvidenceResult uploadEvidenceResult) {
        if (uploadEvidenceResult == null) {
            return;
        }
        String url = uploadEvidenceResult.getUrl();
        if (StrUtil.isNotBlank(url)) {
            cosManager.deleteObjectByUrl(url);
        }
        String thumbnailUrl = uploadEvidenceResult.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl) && !thumbnailUrl.equals(url)) {
            cosManager.deleteObjectByUrl(thumbnailUrl);
        }
    }


    /**
     * 获取查询条件
     *
     * @param evidenceQueryRequest 影像查询请求
     * @return 可用来查询的 queryWrapper
     */
    @Override
    public QueryWrapper<Evidence> getQueryWrapper(EvidenceQueryRequest evidenceQueryRequest) {
        QueryWrapper<Evidence> queryWrapper = new QueryWrapper<>();
        if (evidenceQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = evidenceQueryRequest.getId();
        String name = evidenceQueryRequest.getName();
        String introduction = evidenceQueryRequest.getIntroduction();
        String category = evidenceQueryRequest.getCategory();
        List<String> tags = evidenceQueryRequest.getTags();
        Long picSize = evidenceQueryRequest.getPicSize();
        Integer picWidth = evidenceQueryRequest.getPicWidth();
        Integer picHeight = evidenceQueryRequest.getPicHeight();
        Double picScale = evidenceQueryRequest.getPicScale();
        String picFormat = evidenceQueryRequest.getPicFormat();
        String searchText = evidenceQueryRequest.getSearchText();
        Long userId = evidenceQueryRequest.getUserId();
        String sortField = evidenceQueryRequest.getSortField();
        String sortOrder = evidenceQueryRequest.getSortOrder();
        Integer reviewStatus = evidenceQueryRequest.getReviewStatus();
        String reviewMessage = evidenceQueryRequest.getReviewMessage();
        Long reviewerId = evidenceQueryRequest.getReviewerId();
        Long archiveId = evidenceQueryRequest.getArchiveId();

        ThrowUtils.throwIf(StrUtil.isNotBlank(sortField) && !SORT_FIELDS.contains(sortField),
                ErrorCode.PARAMS_ERROR, "排序字段不合法");
        ThrowUtils.throwIf(StrUtil.isNotBlank(sortOrder) && !SORT_ORDERS.contains(sortOrder),
                ErrorCode.PARAMS_ERROR, "排序方向不合法");

        // 按主色调（腾讯云 imageAve 返回）查询：解析目标颜色，准备颜色距离筛选与排序
        String picColor = evidenceQueryRequest.getPicColor();
        Integer colorDistance = evidenceQueryRequest.getColorDistance();
        int[] targetRgb = StrUtil.isNotBlank(picColor) ? ColorUtils.hexToRgb(picColor) : null;
        boolean colorQuery = targetRgb != null;
        if (colorQuery) {
            int r = targetRgb[0];
            int g = targetRgb[1];
            int b = targetRgb[2];
            int threshold = (colorDistance != null && colorDistance >= 0)
                    ? colorDistance : DEFAULT_COLOR_DISTANCE;
            // 在数据库侧直接计算 RGB 欧氏距离，只返回距离不超过阈值且已提取主色调的影像
            String distExpr = String.format(
                    "SQRT(POW(picColorR - %d, 2) + POW(picColorG - %d, 2) + POW(picColorB - %d, 2))",
                    r, g, b);
            queryWrapper.isNotNull("picColorR");
            queryWrapper.apply(distExpr + " <= " + threshold);
            queryWrapper.orderByAsc(distExpr);
        }

        // 从多字段中搜索
        if (StrUtil.isNotBlank(searchText)) {
            // 需要拼接查询条件
            queryWrapper.and(qw -> qw.like("name", searchText)
                    .or()
                    .like("introduction", searchText)
            );
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        queryWrapper.like(StrUtil.isNotBlank(name), "name", name);
        queryWrapper.like(StrUtil.isNotBlank(introduction), "introduction", introduction);
        queryWrapper.like(StrUtil.isNotBlank(picFormat), "picFormat", picFormat);
        queryWrapper.eq(StrUtil.isNotBlank(category), "category", category);
        queryWrapper.eq(ObjUtil.isNotEmpty(picWidth), "picWidth", picWidth);
        queryWrapper.eq(ObjUtil.isNotEmpty(picHeight), "picHeight", picHeight);
        queryWrapper.eq(ObjUtil.isNotEmpty(picSize), "picSize", picSize);
        queryWrapper.eq(ObjUtil.isNotEmpty(picScale), "picScale", picScale);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewStatus), "reviewStatus", reviewStatus);
        queryWrapper.like(StrUtil.isNotBlank(reviewMessage), "reviewMessage", reviewMessage);
        queryWrapper.eq(ObjUtil.isNotEmpty(reviewerId), "reviewerId", reviewerId);
        // archiveId = 0 表示公共案例库，也要拼条件精确过滤；null 表示不按档案库过滤（如「我的影像」）
        queryWrapper.eq(archiveId != null, "archiveId", archiveId);

        // JSON 数组查询
        if (CollUtil.isNotEmpty(tags)) {
            for (String tag : tags) {
                queryWrapper.like("tags", "\"" + tag + "\"");
            }
        }
        // 排序：按颜色查询时以「颜色距离」为唯一排序，避免和通用排序叠加产生歧义
        if (!colorQuery) {
            queryWrapper.orderBy(StrUtil.isNotEmpty(sortField), "ascend".equals(sortOrder), sortField);
        }
        return queryWrapper;
    }

    /**
     * 获取单个影像的 VO 对象
     *
     * @param evidence evidence 影像
     * @param request request 请求
     * @return 对应影像的 VO
     */
    @Override
    public EvidenceVO getEvidenceVO(Evidence evidence, HttpServletRequest request) {
        // 私有档案库里的影像只有档案库创建者（或管理员）能看，公共档案库里的影像所有人可见
        checkEvidenceViewAuth(evidence, request);
        // 实体转封装类
        EvidenceVO evidenceVO = EvidenceVO.entityToVo(evidence);
        // 关联查询用户信息
        Long userId = evidence.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            UserVO userVO = userService.getUserVO(user);
            evidenceVO.setUser(userVO);
        }
        return evidenceVO;
    }

    /**
     * 校验当前请求能否查看这张影像
     * <p>
     * 公共档案库（archiveId 为空）的影像任何人可看；私有档案库的影像只允许档案库创建者和管理员看，
     * 未登录或访问别人的档案库都直接拒绝。
     *
     * @param evidence evidence 影像
     * @param request request 请求
     */
    private void checkEvidenceViewAuth(Evidence evidence, HttpServletRequest request) {
        ThrowUtils.throwIf(evidence == null, ErrorCode.PARAMS_ERROR, "影像不能为空");
        Long archiveId = evidence.getArchiveId();
        if (archiveId == null || archiveId == 0) {
            if (evidence.getReviewStatus() != null
                    && evidence.getReviewStatus() == EvidenceReviewStatusEnum.PASS.getValue()) {
                return;
            }
            User loginUser = userService.getLoginUserOrNull(request);
            boolean owner = loginUser != null && loginUser.getId() != null
                    && loginUser.getId().equals(evidence.getUserId());
            ThrowUtils.throwIf(!owner && !userService.isAdmin(loginUser),
                    ErrorCode.NO_AUTH_ERROR, "影像尚未审核通过");
            return;
        }
        Archive archive = archiveService.getById(archiveId);
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        // 未登录时 getLoginUserOrNull 返回 null，checkArchiveViewAuth 会按无权限处理
        ArchivePermissionUtils.checkArchiveViewAuth(archive, userService.getLoginUserOrNull(request),
                userService, archiveUserService);
    }

    /**
     * 分页查询影像 VO
     * <p>
     * archiveId 为空：只查公共档案库、且只查已过审的影像，所有人可见；
     * archiveId 不为空：先校验档案库归属，只有档案库创建者（或管理员）能查，可看到档案库内全部影像。
     *
     * @param evidenceQueryRequest 影像查询请求
     * @param request             request 请求
     * @return 分页的 VO
     */
    @Override
    public Page<EvidenceVO> listEvidenceVOByPage(EvidenceQueryRequest evidenceQueryRequest,
                                               HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = evidenceQueryRequest.getCurrent();
        long size = evidenceQueryRequest.getPageSize();
        ThrowUtils.throwIf(current < 1, ErrorCode.PARAMS_ERROR, "页码必须大于 0");
        ThrowUtils.throwIf(size < 1, ErrorCode.PARAMS_ERROR, "每页数量必须大于 0");
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多 20 条");
        Long archiveId = evidenceQueryRequest.getArchiveId();
        if (archiveId == null || archiveId == 0) {
            // 公共案例库：所有人可见，但只能看到已过审的影像；统一归一化为 0
            evidenceQueryRequest.setReviewStatus(EvidenceReviewStatusEnum.PASS.getValue());
            evidenceQueryRequest.setArchiveId(0L);
        } else {
            // 私有档案库：不能查看别人的档案库；团队档案库：成员可查看
            Archive archive = archiveService.getById(archiveId);
            ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
            ArchivePermissionUtils.checkArchiveViewAuth(archive, userService.getLoginUserOrNull(request),
                    userService, archiveUserService);
        }
        // getQueryWrapper 已对 archiveId（含 0）拼精确过滤；这里不要再手动加一条，
        // 重复的 archiveId=0 会让 ShardingSphere 提取不到分片键，路由到错误的表
        QueryWrapper<Evidence> queryWrapper = this.getQueryWrapper(evidenceQueryRequest);
        Page<Evidence> evidencePage = this.page(new Page<>(current, size), queryWrapper);
        return this.getEvidenceVOPage(evidencePage, request);
    }

    /**
     * 分页获取影像封装
     *
     * @param evidencePage evidence 分页对象
     * @param request     request 请求
     * @return 分页的 VO
     */
    @Override
    public Page<EvidenceVO> getEvidenceVOPage(Page<Evidence> evidencePage, HttpServletRequest request) {
        List<Evidence> evidenceList = evidencePage.getRecords();
        Page<EvidenceVO> evidenceVOPage = new Page<>(evidencePage.getCurrent(), evidencePage.getSize(), evidencePage.getTotal());
        if (CollUtil.isEmpty(evidenceList)) {
            return evidenceVOPage;
        }
        // 对象列表 => 封装对象列表
        List<EvidenceVO> evidenceVOList = evidenceList.stream().map(EvidenceVO::entityToVo).collect(Collectors.toList());
        // 1. 关联查询用户信息
        Set<Long> userIdSet = evidenceList.stream().map(Evidence::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        evidenceVOList.forEach(evidenceVO -> {
            Long userId = evidenceVO.getUserId();
            User user = null;
            if (userIdUserListMap.containsKey(userId)) {
                user = userIdUserListMap.get(userId).get(0);
            }
            evidenceVO.setUser(userService.getUserVO(user));
        });
        evidenceVOPage.setRecords(evidenceVOList);
        return evidenceVOPage;
    }

    /**
     * 校验参数
     *
     * @param evidence 需要校验的 evidence 对象
     */
    @Override
    public void validEvidence(Evidence evidence) {
        ThrowUtils.throwIf(evidence == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        Long id = evidence.getId();
        String url = evidence.getUrl();
        String introduction = evidence.getIntroduction();
        // 修改数据时，id 不能为空，有参数则校验
        ThrowUtils.throwIf(ObjUtil.isNull(id), ErrorCode.PARAMS_ERROR, "id 不能为空");
        if (StrUtil.isNotBlank(url)) {
            ThrowUtils.throwIf(url.length() > 1024, ErrorCode.PARAMS_ERROR, "url 过长");
        }
        if (StrUtil.isNotBlank(introduction)) {
            ThrowUtils.throwIf(introduction.length() > 800, ErrorCode.PARAMS_ERROR, "简介过长");
        }
    }

    /**
     * 影像审核
     *
     * @param evidenceReviewRequest 影像审核请求
     * @param loginUser            当前登录的 user
     */
    @Override
    public void doEvidenceReview(EvidenceReviewRequest evidenceReviewRequest, User loginUser) {
        Long id = evidenceReviewRequest.getId();
        Integer reviewStatus = evidenceReviewRequest.getReviewStatus();
        EvidenceReviewStatusEnum reviewStatusEnum = EvidenceReviewStatusEnum.getEnumByValue(reviewStatus);
        if (id == null || reviewStatusEnum == null || EvidenceReviewStatusEnum.REVIEWING.equals(reviewStatusEnum)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 判断是否存在
        Evidence oldEvidence = this.getById(id);
        ThrowUtils.throwIf(oldEvidence == null, ErrorCode.NOT_FOUND_ERROR);
        // 已是该状态
        if (oldEvidence.getReviewStatus().equals(reviewStatus)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请勿重复审核");
        }
        // 更新审核状态
        Evidence updateEvidence = new Evidence();
        BeanUtils.copyProperties(evidenceReviewRequest, updateEvidence);
        updateEvidence.setReviewerId(loginUser.getId());
        updateEvidence.setReviewTime(new Date());
        boolean result = this.updateById(updateEvidence);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 审核结果会影响「已过审」列表，缓存必须失效
        clearEvidenceCache();
    }

    /**
     * 填充审核参数，方便其他方法使用
     *
     * @param evidence   evidence
     * @param loginUser 登录的用户
     */
    @Override
    public void fillReviewParams(Evidence evidence, User loginUser) {
        boolean archiveEvidence = evidence.getArchiveId() != null && evidence.getArchiveId() > 0;
        boolean admin = userService.isAdmin(loginUser);
        if (admin || archiveEvidence) {
            // 管理员或者私人/团队档案库自动过审，公共案例库仍需审核
            evidence.setReviewStatus(EvidenceReviewStatusEnum.PASS.getValue());
            evidence.setReviewerId(loginUser.getId());
            evidence.setReviewMessage(admin ? "管理员自动过审" : "档案库影像无需公共审核");
            evidence.setReviewTime(new Date());
        } else {
            // 非管理员，创建或编辑都要改为待审核
            evidence.setReviewStatus(EvidenceReviewStatusEnum.REVIEWING.getValue());
        }
    }

    /**
     * 批量抓取和创建影像
     * <p>
     * 从必应影像搜索页解析出影像直链，再逐个走 URL 上传流程入库。
     * 单个影像失败不影响其余影像，只记录日志并继续。
     *
     * @param evidenceUploadByBatchRequest 批量抓取请求
     * @param loginUser                   当前登录的 user
     * @return 成功创建的影像数
     */
    @Override
    public Integer uploadEvidenceByBatch(EvidenceUploadByBatchRequest evidenceUploadByBatchRequest, User loginUser) {
        ThrowUtils.throwIf(loginUser == null, ErrorCode.NO_AUTH_ERROR);
        String searchText = evidenceUploadByBatchRequest.getSearchText();
        ThrowUtils.throwIf(StrUtil.isBlank(searchText), ErrorCode.PARAMS_ERROR, "搜索词不能为空");

        // 名称前缀：没传就用搜索词，保证抓下来的影像名字可读
        // 注意判断方向 —— 写反会导致前缀被赋成空字符串，影像名全部退化成原始文件名
        String namePrefix = evidenceUploadByBatchRequest.getNamePrefix();
        if (StrUtil.isBlank(namePrefix)) {
            namePrefix = searchText;
        }

        // 校验抓取数量
        Integer count = evidenceUploadByBatchRequest.getCount();
        ThrowUtils.throwIf(count == null || count <= 0, ErrorCode.PARAMS_ERROR, "抓取数量必须大于 0");
        ThrowUtils.throwIf(count > MAX_BATCH_COUNT, ErrorCode.PARAMS_ERROR, "最多 " + MAX_BATCH_COUNT + " 条");

        // 要抓取的地址（只取必应返回的结果片段，比整页 HTML 轻量）
        String fetchUrl = String.format("https://cn.bing.com/images/async?q=%s&mmasync=1",
                URLUtil.encode(searchText));
        Document document = parseSearchPage(fetchUrl);

        Element div = document.getElementsByClass("dgControl").first();
        ThrowUtils.throwIf(ObjUtil.isNull(div), ErrorCode.OPERATION_ERROR, "获取元素失败");

        Elements imgElementList = div.select("img.mimg");
        ThrowUtils.throwIf(imgElementList.isEmpty(), ErrorCode.OPERATION_ERROR,
                "没有解析到影像，可能是搜索词无结果或页面结构变了");

        int uploadCount = 0;
        // 搜索结果里常出现同一张图（同一原图被不同条目引用），去重避免入库一堆重复影像
        Set<String> uploadedUrls = new HashSet<>();
        for (Element imgElement : imgElementList) {
            String fileUrl = resolveImageUrl(imgElement);
            if (StrUtil.isBlank(fileUrl)) {
                log.info("当前链接为空，已跳过");
                continue;
            }
            if (!uploadedUrls.add(fileUrl)) {
                log.info("影像地址重复，已跳过: {}", fileUrl);
                continue;
            }
            // 上传影像
            EvidenceUploadRequest evidenceUploadRequest = new EvidenceUploadRequest();
            evidenceUploadRequest.setUploadType(FileUploadEnum.URL_TYPE);
            evidenceUploadRequest.setEvidenceUrl(fileUrl);
            // 名称用「前缀 + 序号」，序号跟着成功数递增，失败时不会跳号
            evidenceUploadRequest.setPicName(namePrefix + (uploadCount + 1));
            try {
                EvidenceVO evidenceVO = this.uploadEvidenceByUrl(evidenceUploadRequest, loginUser);
                log.info("影像上传成功, id = {}", evidenceVO.getId());
                uploadCount++;
            } catch (Exception e) {
                // 单张失败不影响整体
                log.error("影像上传失败，地址 = {}", fileUrl, e);
                continue;
            }
            if (uploadCount >= count) {
                break;
            }
        }
        return uploadCount;
    }

    /**
     * 拉取并解析搜索页
     * <p>
     * 单独抽出来是为了把「网络请求」与「结果解析」解耦，方便测试替换。
     *
     * @param fetchUrl 搜索页地址
     * @return 解析后的文档（其 baseUri 即 fetchUrl，可用于补全相对地址）
     */
    protected Document parseSearchPage(String fetchUrl) {
        try {
            return Jsoup.connect(fetchUrl)
                    .userAgent(USER_AGENT)
                    .timeout(FETCH_TIMEOUT_MILLIS)
                    .get();
        } catch (IOException e) {
            log.error("获取页面失败，地址 = {}", fetchUrl, e);
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "获取页面失败");
        }
    }

    /**
     * 从搜索结果里解析出可下载的影像地址
     * <p>
     * 优先从结果条目的 m 属性（JSON）里取 murl —— 这是必应最稳定的原图直链位置；
     * 其次取影像节点自身的 murl；再退回 data-src / src。
     * 注意 src 只是站内缩略图（形如 /th?id=OIP.xxx），既没有影像后缀、画质也差，
     * 只能作为最后的兜底。
     *
     * @param imgElement 影像节点（其所属文档带有 baseUri，可解析相对地址）
     * @return 可下载的影像地址，解析不出时返回空串
     */
    private String resolveImageUrl(Element imgElement) {
        String fileUrl = extractMurlFromMeta(imgElement);
        if (StrUtil.isBlank(fileUrl)) {
            fileUrl = imgElement.attr("murl");
        }
        if (StrUtil.isBlank(fileUrl)) {
            fileUrl = imgElement.attr("data-src");
        }
        if (StrUtil.isBlank(fileUrl)) {
            // absUrl 能把 // 开头的协议相对地址和 / 开头的站内地址补全
            fileUrl = imgElement.absUrl("src");
        }
        if (StrUtil.isBlank(fileUrl)) {
            return StrUtil.EMPTY;
        }
        // 协议相对地址补上 https
        if (fileUrl.startsWith("//")) {
            fileUrl = "https:" + fileUrl;
        }
        return fileUrl.trim();
    }

    /**
     * 从结果条目的 m 属性里解析出原图地址
     * <p>
     * 该属性是一段 JSON 字符串，形如 {"murl":"https://...","turl":"https://..."}。
     * 解析失败属于正常情况（不同版本的页面结构不一致），返回空串由调用方兜底。
     *
     * @param imgElement 影像节点
     * @return 原图地址，取不到时返回空串
     */
    private String extractMurlFromMeta(Element imgElement) {
        // m 属性挂在包裹影像的 a.iusc 上
        Element link = imgElement.closest("a.iusc");
        if (link == null) {
            link = imgElement.parent();
        }
        if (link == null) {
            return StrUtil.EMPTY;
        }
        String meta = link.attr("m");
        if (StrUtil.isBlank(meta)) {
            return StrUtil.EMPTY;
        }
        try {
            JSONObject json = JSONUtil.parseObj(meta);
            return StrUtil.nullToEmpty(json.getStr("murl"));
        } catch (Exception e) {
            // m 属性不是合法 JSON 时静默跳过，交给后续兜底
            log.debug("解析 m 属性失败: {}", meta);
            return StrUtil.EMPTY;
        }
    }


    /**
     * 本地缓存（一级缓存）
     * <p>
     * 生命周期跟着应用走，重启即失效；多实例部署时各实例互不可见。
     * 只用来挡住热点查询，真正的跨实例缓存在 Redis。
     */
    private final Cache<String, String> LOCAL_CACHE =
            Caffeine.newBuilder().initialCapacity(128)
                    .maximumSize(500L)
                    // 本地缓存比 Redis 稍短，让两级缓存自然错开，避免同时失效
                    .expireAfterWrite(5L, TimeUnit.MINUTES)
                    .build();

    /**
     * 分页查询影像（带多级缓存）
     * <p>
     * 缓存策略：Caffeine（本地）→ Redis（分布式）→ 数据库，逐级回填。
     * <p>
     * 只对公共案例库的数据做缓存：这里强制 archiveId = 0、reviewStatus = PASS，
     * 所以缓存内容与用户身份无关，可以放心共用同一个 key。
     * 查私有档案库时结果因人而异，不能进公共缓存，校验完权限直接走数据库。
     *
     * @param evidenceQueryRequest 影像查询请求
     * @param request             request 请求
     * @return 影像封装类分页数据
     */
    @Override
    public Page<EvidenceVO> listEvidenceVOByPageWithCache(EvidenceQueryRequest evidenceQueryRequest,
                                                        HttpServletRequest request) {
        ThrowUtils.throwIf(evidenceQueryRequest == null, ErrorCode.PARAMS_ERROR);
        if (evidenceQueryRequest.getArchiveId() != null && evidenceQueryRequest.getArchiveId() > 0) {
            // 私有/团队档案库：不做缓存，交给带档案库校验的查询方法
            return this.listEvidenceVOByPage(evidenceQueryRequest, request);
        }
        // 公共案例库：统一归一化为 0，与查询语义一致，也让缓存 key 稳定
        evidenceQueryRequest.setArchiveId(0L);
        long current = evidenceQueryRequest.getCurrent();
        long size = evidenceQueryRequest.getPageSize();
        ThrowUtils.throwIf(current < 1, ErrorCode.PARAMS_ERROR, "页码必须大于 0");
        ThrowUtils.throwIf(size < 1, ErrorCode.PARAMS_ERROR, "每页数量必须大于 0");
        // 限制爬虫
        ThrowUtils.throwIf(size > 20, ErrorCode.PARAMS_ERROR, "每页最多 20 条");
        // 普通用户默认只能查看已过审的数据
        evidenceQueryRequest.setReviewStatus(EvidenceReviewStatusEnum.PASS.getValue());

        // 构建缓存 key：只取影响结果的字段，保证序列化顺序稳定
        String cacheKey = CACHE_KEY_PREFIX + DigestUtils.md5DigestAsHex(
                buildCacheCondition(evidenceQueryRequest).getBytes(StandardCharsets.UTF_8));

        // 1. 本地缓存
        String cachedValue = LOCAL_CACHE.getIfPresent(cacheKey);
        if (cachedValue != null) {
            log.debug("命中本地缓存，key = {}", cacheKey);
            return parseCachedPage(cachedValue);
        }

        // 2. Redis 缓存
        ValueOperations<String, String> valueOps = stringRedisTemplate.opsForValue();
        String redisValue = valueOps.get(cacheKey);
        if (redisValue != null) {
            log.debug("命中 Redis 缓存，key = {}", cacheKey);
            // 回填本地缓存，下次就不用走网络了
            LOCAL_CACHE.put(cacheKey, redisValue);
            return parseCachedPage(redisValue);
        }

        // 3. 查数据库
        QueryWrapper<Evidence> queryWrapper = this.getQueryWrapper(evidenceQueryRequest);
        // getQueryWrapper 已对 archiveId=0 拼精确过滤，无需重复追加（重复会让 ShardingSphere 路由错表）
        Page<Evidence> evidencePage = this.page(new Page<>(current, size), queryWrapper);
        Page<EvidenceVO> evidenceVOPage = this.getEvidenceVOPage(evidencePage, request);

        // 4. 回填两级缓存
        String cacheValue = serializePage(evidenceVOPage);
        // 5 - 10 分钟随机过期，防止同一时刻大批 key 一起失效造成缓存雪崩
        int cacheExpireTime = CACHE_EXPIRE_BASE_SECONDS + RandomUtil.randomInt(0, CACHE_EXPIRE_JITTER_SECONDS);
        valueOps.set(cacheKey, cacheValue, cacheExpireTime, TimeUnit.SECONDS);
        LOCAL_CACHE.put(cacheKey, cacheValue);

        return evidenceVOPage;
    }

    /**
     * 清除本地影像缓存
     * <p>
     * 影像增删改（上传、编辑、删除、审核）后调用，避免首页一直看到旧数据。
     * <p>
     * 注意：这里只清了本机的 Caffeine。Redis 里的数据靠 5~10 分钟的 TTL 自然过期，
     * 单机部署下影响可以接受；如果以后多实例部署、要求写入后立刻一致，
     * 需要在这里把 Redis 中 {@link #CACHE_KEY_PREFIX} 开头的 key 一并删掉。
     */
    @Override
    public void clearEvidenceCache() {
        LOCAL_CACHE.invalidateAll();
        log.debug("本地影像缓存已清除");
    }

    /**
     * 拼装参与缓存的查询条件
     * <p>
     * 在副本上规范化后序列化整个 DTO，避免新增查询字段时漏进缓存 key。
     * 标签排序不影响原请求，排序方向统一小写，保证等价查询使用同一个 key。
     *
     * @param queryRequest 查询请求
     * @return 用于计算 md5 的条件串
     */
    private String buildCacheCondition(EvidenceQueryRequest queryRequest) {
        EvidenceQueryRequest normalizedRequest = new EvidenceQueryRequest();
        BeanUtils.copyProperties(queryRequest, normalizedRequest);
        if (CollUtil.isNotEmpty(queryRequest.getTags())) {
            normalizedRequest.setTags(queryRequest.getTags().stream()
                    .sorted()
                    .collect(Collectors.toList()));
        }
        normalizedRequest.setSortOrder(StrUtil.nullToEmpty(queryRequest.getSortOrder()).toLowerCase());
        return JSONUtil.toJsonStr(normalizedRequest);
    }

    /**
     * 把分页对象序列化成缓存字符串
     *
     * @param page 分页对象
     * @return JSON 字符串
     */
    private String serializePage(Page<EvidenceVO> page) {
        return JSONUtil.toJsonStr(page);
    }

    /**
     * 把缓存字符串还原成分页对象
     * <p>
     * 必须用 TypeReference 指定泛型：直接 toBean(xxx, Page.class) 会丢掉
     * records 里的泛型信息，返回的 Page 的 records 是 JSONObject 列表，
     * 遍历时取字段会直接抛异常。
     *
     * @param cachedValue 缓存中的 JSON 字符串
     * @return 分页对象
     */
    private Page<EvidenceVO> parseCachedPage(String cachedValue) {
        return JSONUtil.toBean(cachedValue, new TypeReference<Page<EvidenceVO>>() {
        }, false);
    }

    /**
     * 删除影像（数据库记录 + 对象存储文件）
     * <p>
     * 顺序上先删库再清对象存储：对象存储清理失败只会留下无引用的文件，
     * 不影响用户看到的结果；反过来先删文件再删库，一旦删库失败就会出现「记录在、图打不开」。
     * <p>
     * 删除前做档案库校验：公共档案库的影像仅上传者本人可删，
     * 私有档案库的影像仅档案库创建者（或管理员）可删。
     *
     * @param evidenceId 影像 id
     * @param loginUser 当前登录用户
     */
    @Override
    public void deleteEvidence(long evidenceId, User loginUser) {
        // 先取出记录，删掉之后就查不到 url 了
        Evidence oldEvidence = this.getById(evidenceId);
        ThrowUtils.throwIf(oldEvidence == null, ErrorCode.NOT_FOUND_ERROR, "影像不存在");
        // 不能删别人档案库里的影像；团队档案库按成员角色放行
        EvidencePermissionUtils.checkEvidenceAuth(oldEvidence, loginUser, userService, archiveService, archiveUserService);

        boolean result = this.removeById(evidenceId);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR, "删除失败");

        // 释放档案库容量：删掉的影像不再占用该档案库
        if (oldEvidence.getArchiveId() != null) {
            long picSize = oldEvidence.getPicSize() == null ? 0L : oldEvidence.getPicSize();
            archiveService.updateArchiveUsage(oldEvidence.getArchiveId(), -1L, -picSize);
        }

        // 缓存里的列表还带着这张图，必须清掉
        clearEvidenceCache();
        // 异步清理对象存储，失败只记日志，不影响本次删除结果
        clearEvidenceFile(oldEvidence);
    }

    /**
     * 清理影像对应的对象存储文件
     * <p>
     * 只有确定这张影像不再被其他记录引用时才删，避免误删公用的文件。
     * 传入的是完整访问地址，由 CosManager 负责反推对象 key ——
     * 直接把 URL 当 key 传给对象存储是删不掉任何东西的。
     * <p>
     * 说明：这里是同步执行的，删除失败会被 CosManager 吞掉并记日志。
     * 之前标的 @Async 实际不生效（deleteEvidence 内部是 this 调用，绕过了 Spring 代理），
     * 与其留个假注释不如直接说明：删除是低频操作，同步执行更可预期。
     *
     * @param oldEvidence 已删除的影像记录
     */
    @Override
    public void clearEvidenceFile(Evidence oldEvidence) {
        if (oldEvidence == null) {
            return;
        }
        // 判断该影像是否被多条记录使用
        String evidenceUrl = oldEvidence.getUrl();
        long count = this.lambdaQuery()
                .eq(Evidence::getUrl, evidenceUrl)
                .count();
        // 删除记录后仍有其他记录引用该影像，不清理
        if (count > 0) {
            log.info("该影像仍被 {} 条记录引用，跳过对象存储清理: {}", count, evidenceUrl);
            return;
        }
        // 删原图
        cosManager.deleteObjectByUrl(evidenceUrl);
        // 删缩略图（历史数据可能没有）
        String thumbnailUrl = oldEvidence.getThumbnailUrl();
        if (StrUtil.isNotBlank(thumbnailUrl) && !thumbnailUrl.equals(evidenceUrl)) {
            cosManager.deleteObjectByUrl(thumbnailUrl);
        }
    }


    /**
     * 查询某个档案库下的全部影像
     * <p>
     * 内部方法，调用方（删除档案库等）必须先做档案库权限校验，这里不再重复校验。
     * <p>
     * 注意：这里必须用 {@link LambdaQueryWrapper} 而不是把 lambdaQuery() 的结果直接传给 list()。
     * MyBatis-Plus 3.5.9 拼 SQL 时会取 wrapper 的 sqlFirst，而链式 wrapper
     * （AbstractChainWrapper）的 getSqlFirst 直接抛异常，传进去会报
     * 「can not use this method for "getSqlFirst"」。
     *
     * @param archive 档案库，必须带 id
     * @return 档案库下的影像列表
     */
    @Override
    public List<Evidence> getListEvidenceByArchiveId(Archive archive) {
        ThrowUtils.throwIf(archive == null || archive.getId() == null, ErrorCode.PARAMS_ERROR, "档案库id不可以为空");
        LambdaQueryWrapper<Evidence> queryWrapper = Wrappers.lambdaQuery(Evidence.class)
                .eq(Evidence::getArchiveId, archive.getId());
        return list(queryWrapper);
    }

    @Override
    public void deleteBatchIds(List<Long> list) {
        removeByIds(list);
    }

    @Override
    public int fixAllEvidenceAcl() {
        List<Evidence> evidences = list();
        int fixed = 0;
        for (Evidence evidence : evidences) {
            try {
                String url = evidence.getUrl();
                String key = cosManager.parseKeyFromUrl(url);
                if (StrUtil.isNotBlank(key)) {
                    cosManager.setPublicReadAcl(key);
                }
                String thumbnailUrl = evidence.getThumbnailUrl();
                // 缩略图可能与原图同 key（webp 源图），重复设置无害，跳过相同 key 省一次请求
                String thumbnailKey = cosManager.parseKeyFromUrl(thumbnailUrl);
                if (StrUtil.isNotBlank(thumbnailKey) && !thumbnailKey.equals(key)) {
                    cosManager.setPublicReadAcl(thumbnailKey);
                }
                fixed++;
            } catch (Exception e) {
                log.error("修复影像 ACL 失败，evidenceId = {}: {}", evidence.getId(), e.getMessage());
            }
        }
        return fixed;
    }
}
