package com.traceproof.service.impl;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceproof.conifg.CosClientConfig;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.manager.CosManager;
import com.traceproof.manager.factory.UploadFactory;
import com.traceproof.manager.uplode.FileEvidenceUpload;
import com.traceproof.manager.uplode.EvidenceUploadTemplate;
import com.traceproof.model.dto.evidence.EvidenceQueryRequest;
import com.traceproof.model.dto.evidence.EvidenceUploadRequest;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.FileUploadEnum;
import com.traceproof.model.enums.EvidenceReviewStatusEnum;
import com.traceproof.model.vo.EvidenceVO;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.UserService;
import com.qcloud.cos.model.PutObjectResult;
import com.qcloud.cos.model.ciModel.persistence.CIObject;
import com.qcloud.cos.model.ciModel.persistence.CIUploadResult;
import com.qcloud.cos.model.ciModel.persistence.ImageInfo;
import com.qcloud.cos.model.ciModel.persistence.OriginalInfo;
import com.qcloud.cos.model.ciModel.persistence.ProcessResults;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 影像操作的档案库校验测试
 * <p>
 * 项目里 archiveId 为空表示公共档案库（所有人可见），不为空表示私有档案库（只有档案库创建者和管理员可见、可操作）。
 * 这里覆盖「查看」和「上传」两条链路，确保别人档案库里的影像既看不到也传不进去。
 */
class EvidenceArchiveAuthTest {

    private static final long OWNER_ID = 1L;
    private static final long OTHER_ID = 2L;
    private static final long ARCHIVE_ID = 10L;

    private final UserService userService = mock(UserService.class);
    private final ArchiveService archiveService = mock(ArchiveService.class);
    private final UploadFactory uploadFactory = mock(UploadFactory.class);
    private final CosManager cosManager = mock(CosManager.class);
    private final com.traceproof.manager.EvidenceBloomFilter evidenceBloomFilter = mock(com.traceproof.manager.EvidenceBloomFilter.class);

    private EvidenceServiceImpl newService() throws Exception {
        EvidenceServiceImpl service = spy(new EvidenceServiceImpl());
        setField(service, "userService", userService);
        setField(service, "archiveService", archiveService);
        setField(service, "uploadFactory", uploadFactory);
        setField(service, "cosManager", cosManager);
        setField(service, "evidenceBloomFilter", evidenceBloomFilter);
        return service;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = EvidenceServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private Archive archive(long id, long ownerId) {
        Archive archive = new Archive();
        archive.setId(id);
        archive.setUserId(ownerId);
        archive.setMaxCount(100L);
        // 上限给得足够大，需要触发「容量不足」的用例再单独调小
        archive.setMaxSize(10_000_000L);
        archive.setTotalCount(0L);
        archive.setTotalSize(0L);
        return archive;
    }

    private Evidence evidence(long id, Long userId, Long archiveId) {
        Evidence evidence = new Evidence();
        evidence.setId(id);
        evidence.setUserId(userId);
        evidence.setArchiveId(archiveId);
        evidence.setUrl("https://example.com/a.jpg");
        evidence.setReviewStatus(EvidenceReviewStatusEnum.PASS.getValue());
        return evidence;
    }

    /* ------------------------------ 查看单张影像 ------------------------------ */

    @Test
    @DisplayName("公共档案库的影像未登录也能查看")
    void publicEvidenceShouldBeVisibleWithoutLogin() throws Exception {
        EvidenceServiceImpl service = newService();
        // 未登录时 getLoginUserOrNull 返回 null
        when(userService.getLoginUserOrNull(null)).thenReturn(null);

        EvidenceVO evidenceVO = service.getEvidenceVO(evidence(100L, OTHER_ID, null), null);

        assertNotNull(evidenceVO, "公共案例库必须允许未登录浏览");
    }

    @Test
    @DisplayName("公共待审核影像对未登录和其他用户不可见")
    void pendingPublicEvidenceShouldBeHiddenFromAnonymousAndOthers() throws Exception {
        EvidenceServiceImpl service = newService();
        Evidence pending = evidence(100L, OWNER_ID, 0L);
        pending.setReviewStatus(EvidenceReviewStatusEnum.REVIEWING.getValue());
        when(userService.getLoginUserOrNull(null)).thenReturn(null, user(OTHER_ID));

        assertThrows(BusinessException.class, () -> service.getEvidenceVO(pending, null));
        assertThrows(BusinessException.class, () -> service.getEvidenceVO(pending, null));
    }

    @Test
    @DisplayName("公共影像审核状态为空时不能向匿名用户开放")
    void nullReviewStatusPublicEvidenceShouldBeHidden() throws Exception {
        EvidenceServiceImpl service = newService();
        Evidence pending = evidence(100L, OWNER_ID, null);
        pending.setReviewStatus(null);
        when(userService.getLoginUserOrNull(null)).thenReturn(null);

        assertThrows(BusinessException.class, () -> service.getEvidenceVO(pending, null));
    }

    @Test
    @DisplayName("公共待审核影像允许上传者本人查看")
    void pendingPublicEvidenceShouldBeVisibleToOwner() throws Exception {
        EvidenceServiceImpl service = newService();
        Evidence pending = evidence(100L, OWNER_ID, 0L);
        pending.setReviewStatus(EvidenceReviewStatusEnum.REVIEWING.getValue());
        when(userService.getLoginUserOrNull(null)).thenReturn(user(OWNER_ID));

        assertNotNull(service.getEvidenceVO(pending, null));
    }

    @Test
    @DisplayName("公共待审核影像允许管理员查看")
    void pendingPublicEvidenceShouldBeVisibleToAdmin() throws Exception {
        EvidenceServiceImpl service = newService();
        Evidence pending = evidence(100L, OWNER_ID, 0L);
        pending.setReviewStatus(EvidenceReviewStatusEnum.REVIEWING.getValue());
        User admin = user(OTHER_ID);
        when(userService.getLoginUserOrNull(null)).thenReturn(admin);
        when(userService.isAdmin(admin)).thenReturn(true);

        assertNotNull(service.getEvidenceVO(pending, null));
    }

    @Test
    @DisplayName("别人私有档案库里的影像不能查看")
    void privateEvidenceShouldBeHiddenFromOthers() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);
        when(userService.getLoginUserOrNull(null)).thenReturn(user(OTHER_ID));

        BusinessException e = assertThrows(BusinessException.class,
                () -> service.getEvidenceVO(evidence(100L, OWNER_ID, ARCHIVE_ID), null));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("私有档案库的影像未登录不能查看")
    void privateEvidenceShouldBeHiddenFromAnonymous() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);
        when(userService.getLoginUserOrNull(null)).thenReturn(null);

        assertThrows(BusinessException.class,
                () -> service.getEvidenceVO(evidence(100L, OWNER_ID, ARCHIVE_ID), null));
    }

    @Test
    @DisplayName("档案库创建者可以查看自己档案库里的影像")
    void privateEvidenceShouldBeVisibleToOwner() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);
        when(userService.getLoginUserOrNull(null)).thenReturn(user(OWNER_ID));

        EvidenceVO evidenceVO = service.getEvidenceVO(evidence(100L, OWNER_ID, ARCHIVE_ID), null);

        assertNotNull(evidenceVO);
        assertEquals(Long.valueOf(ARCHIVE_ID), evidenceVO.getArchiveId());
    }

    /* ------------------------------ 分页查询 ------------------------------ */

    @Test
    @DisplayName("不传 archiveId 时只查公共档案库，且强制只看已过审影像")
    void listWithoutArchiveShouldOnlyQueryPublicArchive() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(new Page<Evidence>()).when(service).page(any(), any());
        EvidenceQueryRequest request = new EvidenceQueryRequest();

        service.listEvidenceVOByPage(request, null);

        assertEquals(EvidenceReviewStatusEnum.PASS.getValue(), request.getReviewStatus(),
                "公共案例库必须只看已过审的影像");
        ArgumentCaptor<Wrapper<Evidence>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(service).page(any(), captor.capture());
        String sql = captor.getValue().getSqlSegment().toLowerCase();
        assertTrue(sql.contains("archiveid = "),
                "没有限定只查公共档案库，会把别人私有档案库里的影像一起查出来: " + sql);
    }

    @Test
    @DisplayName("公共影像列表应拒绝非法页码和每页数量")
    void invalidPublicPageShouldBeRejected() throws Exception {
        EvidenceServiceImpl service = newService();
        EvidenceQueryRequest invalidCurrent = new EvidenceQueryRequest();
        invalidCurrent.setCurrent(0);
        EvidenceQueryRequest invalidSize = new EvidenceQueryRequest();
        invalidSize.setPageSize(0);
        EvidenceQueryRequest oversizedPage = new EvidenceQueryRequest();
        oversizedPage.setPageSize(21);

        for (EvidenceQueryRequest request : Arrays.asList(invalidCurrent, invalidSize, oversizedPage)) {
            assertThrows(BusinessException.class, () -> service.listEvidenceVOByPage(request, null));
        }
        verify(service, never()).page(any(), any());
    }

    @Test
    @DisplayName("查询别人档案库应被拒绝")
    void listOthersArchiveShouldBeRejected() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);
        when(userService.getLoginUserOrNull(null)).thenReturn(user(OTHER_ID));

        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setArchiveId(ARCHIVE_ID);

        assertThrows(BusinessException.class, () -> service.listEvidenceVOByPage(request, null));
        verify(service, never()).page(any(), any());
    }

    @Test
    @DisplayName("档案库创建者可以查询自己档案库里的影像")
    void listOwnArchiveShouldPass() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);
        when(userService.getLoginUserOrNull(null)).thenReturn(user(OWNER_ID));
        doReturn(new Page<Evidence>()).when(service).page(any(), any());

        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setArchiveId(ARCHIVE_ID);

        service.listEvidenceVOByPage(request, null);

        // 私有档案库里能看到自己的待审核影像，所以不能强制改成已过审
        assertNull(request.getReviewStatus());
        verify(service).page(any(), any());
    }

    @Test
    @DisplayName("仅 archiveId 大于 0 的档案库或管理员可自动过审")
    void onlyNonPublicArchiveOrAdminShouldAutoPass() throws Exception {
        EvidenceServiceImpl service = newService();
        User owner = user(OWNER_ID);

        Evidence publicZero = evidence(100L, OWNER_ID, 0L);
        service.fillReviewParams(publicZero, owner);
        assertEquals(EvidenceReviewStatusEnum.REVIEWING.getValue(), publicZero.getReviewStatus());

        Evidence publicNull = evidence(101L, OWNER_ID, null);
        service.fillReviewParams(publicNull, owner);
        assertEquals(EvidenceReviewStatusEnum.REVIEWING.getValue(), publicNull.getReviewStatus());

        Evidence archived = evidence(102L, OWNER_ID, ARCHIVE_ID);
        service.fillReviewParams(archived, owner);
        assertEquals(EvidenceReviewStatusEnum.PASS.getValue(), archived.getReviewStatus());

        User admin = user(OTHER_ID);
        when(userService.isAdmin(admin)).thenReturn(true);
        Evidence adminPublic = evidence(103L, OTHER_ID, 0L);
        service.fillReviewParams(adminPublic, admin);
        assertEquals(EvidenceReviewStatusEnum.PASS.getValue(), adminPublic.getReviewStatus());
    }

    /* ------------------------------ 上传 ------------------------------ */

    private static final String HOST = "https://test-bucket.cos.ap-guangzhou.myqcloud.com";

    /**
     * 用真实的上传实现来跑上传流程
     * <p>
     * {@code uploadEvidence} 是模板方法且被声明为 final，Mockito 没法打桩，
     * 所以这里只把对象存储与配置换成假的，模板流程本身照常执行。
     */
    private void useRealUploadTemplate() throws Exception {
        FileEvidenceUpload template = new FileEvidenceUpload();
        when(cosManager.putEvidenceObject(anyString(), any())).thenReturn(buildPutObjectResult());

        CosClientConfig config = new CosClientConfig();
        config.setHost(HOST);
        setTemplateField(template, "cosManager", cosManager);
        setTemplateField(template, "cosClientConfig", config);

        when(uploadFactory.getUploadTemplate(FileUploadEnum.FILE_TYPE)).thenReturn(template);
    }

    private void setTemplateField(Object target, String name, Object value) throws Exception {
        Field field = EvidenceUploadTemplate.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    /** 构造对象存储的返回：原图信息 + 一张 30000 字节的压缩图 */
    private PutObjectResult buildPutObjectResult() {
        ImageInfo imageInfo = new ImageInfo();
        imageInfo.setWidth(800);
        imageInfo.setHeight(600);
        imageInfo.setFormat("png");

        OriginalInfo originalInfo = new OriginalInfo();
        originalInfo.setImageInfo(imageInfo);

        CIObject compressed = new CIObject();
        compressed.setKey("/public/1/compressed.webp");
        compressed.setFormat("webp");
        compressed.setWidth(800);
        compressed.setHeight(600);
        compressed.setSize(30000);

        ProcessResults processResults = new ProcessResults();
        processResults.setObjectList(new ArrayList<>(Arrays.asList(compressed)));

        CIUploadResult ciUploadResult = new CIUploadResult();
        ciUploadResult.setOriginalInfo(originalInfo);
        ciUploadResult.setProcessResults(processResults);

        PutObjectResult result = new PutObjectResult();
        result.setCiUploadResult(ciUploadResult);
        return result;
    }

    private MockMultipartFile pngFile() {
        return new MockMultipartFile("file", "photo.png", "image/png", new byte[1024]);
    }

    @Test
    @DisplayName("不能往别人的档案库里传图")
    void uploadToOthersArchiveShouldBeRejected() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);

        EvidenceUploadRequest request = new EvidenceUploadRequest();
        request.setArchiveId(ARCHIVE_ID);

        assertThrows(BusinessException.class,
                () -> service.uploadEvidence(pngFile(), request, user(OTHER_ID)));
        // 权限没通过时不该真的去上传文件
        verify(uploadFactory, never()).getUploadTemplate(anyString());
    }

    @Test
    @DisplayName("档案库影像数量已满时应拒绝上传，且不上传文件")
    void uploadShouldBeRejectedWhenArchiveCountIsFull() throws Exception {
        EvidenceServiceImpl service = newService();
        Archive archive = archive(ARCHIVE_ID, OWNER_ID);
        // 档案库只允许 1 张，且已经用满了
        archive.setMaxCount(1L);
        archive.setTotalCount(1L);
        doReturn(archive).when(archiveService).getById(ARCHIVE_ID);

        EvidenceUploadRequest request = new EvidenceUploadRequest();
        request.setArchiveId(ARCHIVE_ID);

        assertThrows(BusinessException.class,
                () -> service.uploadEvidence(pngFile(), request, user(OWNER_ID)));

        // 数量校验在上传之前，放不下就没必要再去传文件
        verify(uploadFactory, never()).getUploadTemplate(anyString());
        verify(service, never()).saveOrUpdate(any(Evidence.class));
    }

    @Test
    @DisplayName("档案库容量不足时应拒绝上传，并清掉刚上传的文件")
    void uploadShouldBeRejectedWhenArchiveSizeIsFull() throws Exception {
        EvidenceServiceImpl service = newService();
        Archive archive = archive(ARCHIVE_ID, OWNER_ID);
        // 只允许 1000 字节，而上传结果有 30000 字节
        archive.setMaxSize(1000L);
        doReturn(archive).when(archiveService).getById(ARCHIVE_ID);
        useRealUploadTemplate();

        EvidenceUploadRequest request = new EvidenceUploadRequest();
        request.setArchiveId(ARCHIVE_ID);

        assertThrows(BusinessException.class,
                () -> service.uploadEvidence(pngFile(), request, user(OWNER_ID)));

        // 没能入库的文件必须从对象存储里删掉（原图 + 压缩图），避免留下没有记录引用的垃圾
        ArgumentCaptor<String> urlCaptor = ArgumentCaptor.forClass(String.class);
        verify(cosManager, times(2)).deleteObjectByUrl(urlCaptor.capture());
        assertTrue(urlCaptor.getAllValues().get(0).startsWith(HOST + "/public/1/"),
                "清理的不是刚上传的原图: " + urlCaptor.getAllValues().get(0));
        verify(service, never()).saveOrUpdate(any(Evidence.class));
    }

    @Test
    @DisplayName("上传成功后应把影像归到档案库并累加档案库用量")
    void uploadShouldIncreaseArchiveUsage() throws Exception {
        EvidenceServiceImpl service = newService();
        doReturn(archive(ARCHIVE_ID, OWNER_ID)).when(archiveService).getById(ARCHIVE_ID);
        useRealUploadTemplate();
        doReturn(true).when(service).saveOrUpdate(any(Evidence.class));

        EvidenceUploadRequest request = new EvidenceUploadRequest();
        request.setArchiveId(ARCHIVE_ID);

        EvidenceVO evidenceVO = service.uploadEvidence(pngFile(), request, user(OWNER_ID));

        assertNotNull(evidenceVO);
        assertEquals(Long.valueOf(ARCHIVE_ID), evidenceVO.getArchiveId(), "影像没有归到目标档案库");
        assertEquals(EvidenceReviewStatusEnum.PASS.getValue(), evidenceVO.getReviewStatus(),
                "档案库影像应按原逻辑自动过审");
        // 新增 1 张图、占用 30000 字节
        verify(archiveService).updateArchiveUsage(ARCHIVE_ID, 1L, 30000L);
    }

    @Test
    @DisplayName("上传到公共档案库不应影响任何档案库用量")
    void uploadToPublicArchiveShouldNotTouchUsage() throws Exception {
        EvidenceServiceImpl service = newService();
        useRealUploadTemplate();
        doReturn(true).when(service).saveOrUpdate(any(Evidence.class));

        EvidenceUploadRequest request = new EvidenceUploadRequest();

        EvidenceVO evidenceVO = service.uploadEvidence(pngFile(), request, user(OWNER_ID));

        assertNotNull(evidenceVO);
        assertEquals(0L, evidenceVO.getArchiveId(), "公共案例库影像的 archiveId 应为 0");
        assertEquals(EvidenceReviewStatusEnum.REVIEWING.getValue(), evidenceVO.getReviewStatus(),
                "公共案例库影像不能自动过审");
        verify(archiveService, never()).updateArchiveUsage(anyLong(), anyLong(), anyLong());
    }
}
