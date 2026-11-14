package com.traceproof.service.impl;

import com.baomidou.mybatisplus.extension.conditions.query.LambdaQueryChainWrapper;
import com.traceproof.exception.BusinessException;
import com.traceproof.manager.CosManager;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.lang.reflect.Field;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/**
 * 删除影像流程的测试
 * <p>
 * 删除是「档案库校验 + 删库 + 释放档案库容量 + 清缓存 + 清对象存储」几件事，
 * 这里验证权限拦得住、该做的都做了、顺序正确、且异常场景有合理行为。
 */
class EvidenceDeleteTest {

    private static final long OWNER_ID = 1L;

    /**
     * 构造一个假的 Service：不连数据库，也不真的删对象存储
     *
     * @param existing    getById 返回的记录，null 表示不存在
     * @param removeById  removeById 的返回值
     * @param userService 用户服务（判断管理员），传 null 表示走不到这一步
     * @param archiveService 档案库服务，传 null 表示影像不在私有档案库里
     */
    private EvidenceServiceImpl buildService(Evidence existing, boolean removeById,
                                            UserService userService, ArchiveService archiveService) throws Exception {
        EvidenceServiceImpl service = spy(new EvidenceServiceImpl());
        doReturn(existing).when(service).getById(anyLong());
        doReturn(removeById).when(service).removeById(anyLong());
        // 清理对象存储这一步单独验证，这里替换掉避免真的调用外部服务
        org.mockito.Mockito.doNothing().when(service).clearEvidenceFile(any(Evidence.class));

        // 注入 async 方法内部用到的 cosManager（部分场景会走到）
        CosManager cosManager = mock(CosManager.class);
        setField(service, "cosManager", cosManager);
        if (userService != null) {
            setField(service, "userService", userService);
        }
        if (archiveService != null) {
            setField(service, "archiveService", archiveService);
        }
        return service;
    }

    private EvidenceServiceImpl buildCleanupService(long referenceCount, CosManager cosManager) throws Exception {
        EvidenceServiceImpl service = spy(new EvidenceServiceImpl());
        LambdaQueryChainWrapper<Evidence> queryWrapper = mock(LambdaQueryChainWrapper.class);
        doReturn(queryWrapper).when(service).lambdaQuery();
        doReturn(queryWrapper).when(queryWrapper).eq(any(), any());
        doReturn(referenceCount).when(queryWrapper).count();
        setField(service, "cosManager", cosManager);
        return service;
    }

    private void setField(Object target, String name, Object value) throws Exception {
        Field field = EvidenceServiceImpl.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private Evidence evidence(long id) {
        Evidence evidence = new Evidence();
        evidence.setId(id);
        evidence.setUserId(OWNER_ID);
        evidence.setUrl("https://bucket.cos.ap-guangzhou.myqcloud.com/public/1/a.jpg");
        evidence.setThumbnailUrl("https://bucket.cos.ap-guangzhou.myqcloud.com/public/1/a.webp");
        return evidence;
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
        return archive;
    }

    @Test
    @DisplayName("删除应同时删库、清缓存、清对象存储")
    void shouldDeleteRecordAndFileAndCache() throws Exception {
        Evidence existing = evidence(100L);
        EvidenceServiceImpl service = buildService(existing, true, null, null);

        service.deleteEvidence(100L, user(OWNER_ID));

        verify(service).removeById(100L);
        verify(service).clearEvidenceCache();
        verify(service).clearEvidenceFile(existing);
    }

    @Test
    @DisplayName("应先删库再清对象存储（顺序反了会留下打不开的记录）")
    void shouldDeleteRecordBeforeFile() throws Exception {
        Evidence existing = evidence(100L);
        EvidenceServiceImpl service = buildService(existing, true, null, null);

        service.deleteEvidence(100L, user(OWNER_ID));

        InOrder inOrder = inOrder(service);
        inOrder.verify(service).removeById(100L);
        inOrder.verify(service).clearEvidenceFile(existing);
    }

    @Test
    @DisplayName("影像不存在时应报错，且不做任何删除动作")
    void shouldFailWhenEvidenceNotFound() throws Exception {
        EvidenceServiceImpl service = buildService(null, true, null, null);

        assertThrows(BusinessException.class, () -> service.deleteEvidence(100L, user(OWNER_ID)));

        verify(service, never()).removeById(anyLong());
        verify(service, never()).clearEvidenceFile(any(Evidence.class));
    }

    @Test
    @DisplayName("删库失败时不应继续清理对象存储")
    void shouldNotClearFileWhenRemoveFails() throws Exception {
        Evidence existing = evidence(100L);
        EvidenceServiceImpl service = buildService(existing, false, null, null);

        assertThrows(BusinessException.class, () -> service.deleteEvidence(100L, user(OWNER_ID)));

        verify(service, never()).clearEvidenceFile(any(Evidence.class));
    }

    @Test
    @DisplayName("清缓存要在删库之后，避免清完又被读回旧数据")
    void shouldClearCacheAfterRemove() throws Exception {
        Evidence existing = evidence(100L);
        EvidenceServiceImpl service = buildService(existing, true, null, null);

        service.deleteEvidence(100L, user(OWNER_ID));

        InOrder inOrder = inOrder(service);
        inOrder.verify(service).removeById(100L);
        inOrder.verify(service).clearEvidenceCache();
    }

    @Test
    @DisplayName("公共档案库里别人的影像不能删")
    void shouldRejectDeletingOthersEvidenceInPublicArchive() throws Exception {
        Evidence existing = evidence(100L);
        existing.setUserId(2L);
        UserService userService = mock(UserService.class);
        EvidenceServiceImpl service = buildService(existing, true, userService, mock(ArchiveService.class));

        assertThrows(BusinessException.class, () -> service.deleteEvidence(100L, user(OWNER_ID)));

        verify(service, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("别人私有档案库里的影像不能删")
    void shouldRejectDeletingEvidenceInOthersArchive() throws Exception {
        Evidence existing = evidence(100L);
        // 影像挂在 id=10 的档案库下，但档案库所有者是别人
        existing.setArchiveId(10L);
        UserService userService = mock(UserService.class);
        ArchiveService archiveService = mock(ArchiveService.class);
        doReturn(archive(10L, 2L)).when(archiveService).getById(10L);
        EvidenceServiceImpl service = buildService(existing, true, userService, archiveService);

        assertThrows(BusinessException.class, () -> service.deleteEvidence(100L, user(OWNER_ID)));

        verify(service, never()).removeById(anyLong());
    }

    @Test
    @DisplayName("删除自己档案库里的影像要释放档案库容量")
    void shouldReleaseArchiveUsageWhenDeletingEvidenceInArchive() throws Exception {
        Evidence existing = evidence(100L);
        existing.setArchiveId(10L);
        existing.setPicSize(1024L);
        UserService userService = mock(UserService.class);
        ArchiveService archiveService = mock(ArchiveService.class);
        doReturn(archive(10L, OWNER_ID)).when(archiveService).getById(10L);
        EvidenceServiceImpl service = buildService(existing, true, userService, archiveService);

        service.deleteEvidence(100L, user(OWNER_ID));

        verify(archiveService).updateArchiveUsage(10L, -1L, -1024L);
    }

    @Test
    @DisplayName("公共档案库的影像不涉及档案库容量，不应调用档案库用量更新")
    void shouldNotTouchArchiveUsageForPublicEvidence() throws Exception {
        Evidence existing = evidence(100L);
        ArchiveService archiveService = mock(ArchiveService.class);
        EvidenceServiceImpl service = buildService(existing, true, null, archiveService);

        service.deleteEvidence(100L, user(OWNER_ID));

        verify(archiveService, never()).updateArchiveUsage(any(), anyLong(), anyLong());
    }

    @Test
    @DisplayName("删除记录后仍有一条引用时不能删除 COS 对象")
    void shouldKeepCosObjectWhenOneReferenceRemains() throws Exception {
        CosManager cosManager = mock(CosManager.class);
        EvidenceServiceImpl service = buildCleanupService(1L, cosManager);

        service.clearEvidenceFile(evidence(100L));

        verify(cosManager, never()).deleteObjectByUrl(anyString());
    }

    @Test
    @DisplayName("删除记录后没有引用时才删除原图和缩略图")
    void shouldDeleteCosObjectsWhenNoReferenceRemains() throws Exception {
        CosManager cosManager = mock(CosManager.class);
        EvidenceServiceImpl service = buildCleanupService(0L, cosManager);
        Evidence oldEvidence = evidence(100L);

        service.clearEvidenceFile(oldEvidence);

        verify(cosManager).deleteObjectByUrl(oldEvidence.getUrl());
        verify(cosManager).deleteObjectByUrl(oldEvidence.getThumbnailUrl());
    }
}
