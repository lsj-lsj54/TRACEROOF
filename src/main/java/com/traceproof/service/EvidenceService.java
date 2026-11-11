package com.traceproof.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceproof.model.dto.evidence.EvidenceQueryRequest;
import com.traceproof.model.dto.evidence.EvidenceReviewRequest;
import com.traceproof.model.dto.evidence.EvidenceUploadByBatchRequest;
import com.traceproof.model.dto.evidence.EvidenceUploadRequest;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.EvidenceVO;

import com.baomidou.mybatisplus.extension.service.IService;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
* @author lsj
* @description 针对表【evidence(影像)】的数据库操作Service
* @createDate 2026-10-12 10:48:24
*/
public interface EvidenceService extends IService<Evidence> {
    /**
     * 上传影像（本地文件）
     *
     * @param multipartFile        上传的影像文件
     * @param evidenceUploadRequest 影像上传请求，id 不为空时表示重新上传
     * @param loginUser            登录的用户
     * @return 影像封装类
     */
    EvidenceVO uploadEvidence(MultipartFile multipartFile,
                            EvidenceUploadRequest evidenceUploadRequest,
                            User loginUser);

    /**
     * 上传影像（通过 URL）
     * <p>
     * 会把远程影像下载后转存到对象存储，其余流程与本地文件上传一致。
     *
     * @param evidenceUploadRequest 影像上传请求，必须携带 evidenceUrl
     * @param loginUser            登录的用户
     * @return 影像封装类
     */
    EvidenceVO uploadEvidenceByUrl(EvidenceUploadRequest evidenceUploadRequest,
                                 User loginUser);

    /**
     * 获取查询的 queryWrapper
     *
     * @param evidenceQueryRequest 影像请求类
     * @return 可用来查询的 queryWrapper
     */
    QueryWrapper<Evidence> getQueryWrapper(EvidenceQueryRequest evidenceQueryRequest);

    /**
     * 获取单个影像的 VO 对象
     *
     * @param evidence evidence 对象
     * @param request request 请求
     * @return 对应影像的 VO
     */
    EvidenceVO getEvidenceVO(Evidence evidence, HttpServletRequest request);

    /**
     * 分页获取影像 VO 对象
     *
     * @param evidencePage page 对象
     * @param request     request 请求
     * @return 分页的 VO
     */
    Page<EvidenceVO> getEvidenceVOPage(Page<Evidence> evidencePage, HttpServletRequest request);

    /**
     * 分页获取影像 VO 对象
     * <p>
     * 档案库规则：
     * <ul>
     *     <li>archiveId 为空：只返回公共档案库的影像，且只返回已过审的影像，任何人可看</li>
     *     <li>archiveId 不为空：只允许该档案库的创建者（或管理员）查询，能看到档案库内全部影像</li>
     * </ul>
     *
     * @param evidenceQueryRequest 影像查询请求
     * @param request             request 请求
     * @return 分页的 VO
     */
    Page<EvidenceVO> listEvidenceVOByPage(EvidenceQueryRequest evidenceQueryRequest,
                                       HttpServletRequest request);

    /**
     * 分页获取影像 VO 对象（带多级缓存）
     * <p>
     * Caffeine（本地）→ Redis（分布式）→ 数据库，逐级回填。
     * 缓存的是所有人共享的公共档案库数据，因此只对 archiveId 为空（公共档案库）的查询生效；
     * 查私有档案库时校验完权限直接走数据库，不读也不写缓存，避免把别人的私有影像缓存出去。
     *
     * @param evidenceQueryRequest 影像查询请求
     * @param request             request 请求
     * @return 分页的 VO
     */
    Page<EvidenceVO> listEvidenceVOByPageWithCache(EvidenceQueryRequest evidenceQueryRequest,
                                                HttpServletRequest request);

    /**
     * 清除影像列表缓存
     * <p>
     * 影像增删改、审核之后都要调用，否则首页会一直返回旧数据。
     * 目前清的是本机 Caffeine；Redis 侧靠 TTL 自然过期，详见实现类注释。
     */
    void clearEvidenceCache();

    /**
     * 校验参数
     *
     * @param evidence 需要校验的 evidence 对象
     */
    void validEvidence(Evidence evidence);
    /**
     * 影像审核
     *
     * @param evidenceReviewRequest 影像审核请求
     * @param loginUser            当前登录的 user
     */
    void doEvidenceReview(EvidenceReviewRequest evidenceReviewRequest, User loginUser);

    /**
     * 填充审核参数，方便其他方法使用
     *
     * @param evidence   evidence
     * @param loginUser 登录的用户
     */
    void fillReviewParams(Evidence evidence, User loginUser);

    /**
     * 批量抓取和创建影像
     *
     * @param evidenceUploadByBatchRequest
     * @param loginUser
     * @return 成功创建的影像数
     */
    Integer uploadEvidenceByBatch(
            EvidenceUploadByBatchRequest evidenceUploadByBatchRequest,
            User loginUser
    );

    /**
     * 清理影像在对象存储中的文件（原图 + 缩略图）
     * <p>
     * 被其他记录引用的影像会被跳过，不会误删公用文件。
     * 删除失败只记日志，不影响调用方。
     *
     * @param oldEvidence 已删除的影像记录
     */
    void clearEvidenceFile(Evidence oldEvidence);

    /**
     * 删除影像（数据库记录 + 对象存储文件）
     * <p>
     * 先删数据库记录，再清理对象存储，避免对象存储异常导致请求失败。
     * 删除前会做档案库校验：公共档案库的影像仅上传者本人可删，
     * 私有档案库的影像仅档案库创建者（或管理员）可删。
     *
     * @param evidenceId 影像 id
     * @param loginUser 当前登录用户
     */
    void deleteEvidence(long evidenceId, User loginUser);

    List<Evidence> getListEvidenceByArchiveId(Archive archive);

    void deleteBatchIds(List<Long> list);

    /**
     * 修复存量影像的对象存储 ACL（设为公有读）
     * <p>
     * 历史上传的影像没有设置公有读权限，浏览器直接访问 URL 会 403 导致裂图。
     * 该方法遍历全部影像记录，对原图和缩略图逐一补设 ACL。
     *
     * @return 成功处理的影像条数
     */
    int fixAllEvidenceAcl();
}
