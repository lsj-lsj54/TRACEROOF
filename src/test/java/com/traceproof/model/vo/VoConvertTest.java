package com.traceproof.model.vo;

import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * VO 与实体互转的测试
 * <p>
 * voToEntity / entityToVo 是各 service 里转换的统一入口，
 * 一旦字段映射写错（尤其是 tags 的 JSON 转换、缩略图回退），
 * 会出现「列表能查出来但字段是空的」这种很难定位的问题。
 */
class VoConvertTest {

    /* ------------------------------ ArchiveVO ------------------------------ */

    @Test
    @DisplayName("ArchiveVO 与实体应能双向转换")
    void archiveVoShouldRoundTrip() {
        Archive archive = new Archive();
        archive.setId(10L);
        archive.setArchiveName("我的档案库");
        archive.setArchiveLevel(1);
        archive.setMaxSize(1024L);
        archive.setMaxCount(100L);
        archive.setTotalSize(512L);
        archive.setTotalCount(3L);
        archive.setUserId(7L);

        ArchiveVO archiveVO = ArchiveVO.entityToVo(archive);
        assertNotNull(archiveVO);
        assertEquals("我的档案库", archiveVO.getArchiveName());
        assertEquals(1, archiveVO.getArchiveLevel());
        assertEquals(7L, archiveVO.getUserId());

        Archive back = ArchiveVO.voToEntity(archiveVO);
        assertNotNull(back);
        assertEquals(archive.getId(), back.getId());
        assertEquals(archive.getArchiveName(), back.getArchiveName());
        assertEquals(archive.getArchiveLevel(), back.getArchiveLevel());
        assertEquals(archive.getMaxSize(), back.getMaxSize());
        assertEquals(archive.getMaxCount(), back.getMaxCount());
        assertEquals(archive.getTotalSize(), back.getTotalSize());
        assertEquals(archive.getTotalCount(), back.getTotalCount());
        assertEquals(archive.getUserId(), back.getUserId());
    }

    @Test
    @DisplayName("ArchiveVO 转换遇到 null 应返回 null，而不是抛异常")
    void archiveVoShouldHandleNull() {
        assertNull(ArchiveVO.entityToVo(null));
        assertNull(ArchiveVO.voToEntity(null));
    }

    /* ------------------------------ EvidenceVO ------------------------------ */

    @Test
    @DisplayName("EvidenceVO 转换应把 tags 在 JSON 字符串与列表之间互转")
    void evidenceVoShouldConvertTags() {
        Evidence evidence = new Evidence();
        evidence.setId(100L);
        evidence.setName("海边");
        evidence.setUrl("https://example.com/a.jpg");
        evidence.setThumbnailUrl("https://example.com/a.webp");
        evidence.setTags("[\"风景\",\"海边\"]");
        evidence.setArchiveId(10L);
        evidence.setUserId(7L);

        EvidenceVO evidenceVO = EvidenceVO.entityToVo(evidence);
        assertNotNull(evidenceVO);
        assertEquals(Arrays.asList("风景", "海边"), evidenceVO.getTags());
        assertEquals(10L, evidenceVO.getArchiveId(), "档案库归属必须传出去，否则前端分不清公共案例库和私有档案库");

        Evidence back = EvidenceVO.voToEntity(evidenceVO);
        assertNotNull(back);
        assertEquals("[\"风景\",\"海边\"]", back.getTags(), "tags 必须还原成 JSON 字符串再入库");
        assertEquals(Long.valueOf(10L), back.getArchiveId());
    }

    @Test
    @DisplayName("历史数据没有缩略图时应回退成原图，避免前端裂图")
    void evidenceVoShouldFallbackThumbnail() {
        Evidence evidence = new Evidence();
        evidence.setUrl("https://example.com/a.jpg");

        EvidenceVO evidenceVO = EvidenceVO.entityToVo(evidence);
        assertNotNull(evidenceVO);
        assertEquals("https://example.com/a.jpg", evidenceVO.getThumbnailUrl());
    }

    @Test
    @DisplayName("EvidenceVO 转换遇到 null 应返回 null")
    void evidenceVoShouldHandleNull() {
        assertNull(EvidenceVO.entityToVo(null));
        assertNull(EvidenceVO.voToEntity(null));
    }

    /* ------------------------------ UserVO / LoginUserVO ------------------------------ */

    @Test
    @DisplayName("UserVO 与实体应能双向转换，且不带上密码")
    void userVoShouldRoundTripWithoutPassword() {
        User user = new User();
        user.setId(1L);
        user.setUserAccount("lsj");
        user.setUserName("吕世杰");
        user.setUserRole("admin");
        user.setUserPassword("加密后的密码");
        user.setCreateTime(new Date(0L));

        UserVO userVO = UserVO.entityToVo(user);
        assertNotNull(userVO);
        assertEquals("lsj", userVO.getUserAccount());
        assertEquals("admin", userVO.getUserRole());

        User back = UserVO.voToEntity(userVO);
        assertNotNull(back);
        assertEquals(user.getId(), back.getId());
        assertEquals(user.getUserAccount(), back.getUserAccount());
        // VO 里没有密码字段，回写时不能凭空造出一个密码
        assertNull(back.getUserPassword());
    }

    @Test
    @DisplayName("LoginUserVO 与实体应能双向转换，且不带上密码")
    void loginUserVoShouldRoundTripWithoutPassword() {
        User user = new User();
        user.setId(1L);
        user.setUserAccount("lsj");
        user.setUserPassword("加密后的密码");

        LoginUserVO loginUserVO = LoginUserVO.entityToVo(user);
        assertNotNull(loginUserVO);
        assertEquals("lsj", loginUserVO.getUserAccount());

        User back = LoginUserVO.voToEntity(loginUserVO);
        assertNotNull(back);
        assertNull(back.getUserPassword());
        assertEquals(Long.valueOf(1L), back.getId());
    }

    @Test
    @DisplayName("UserVO / LoginUserVO 转换遇到 null 应返回 null")
    void userVoShouldHandleNull() {
        assertNull(UserVO.entityToVo(null));
        assertNull(UserVO.voToEntity(null));
        assertNull(LoginUserVO.entityToVo(null));
        assertNull(LoginUserVO.voToEntity(null));
    }

    @Test
    @DisplayName("属性为 null 时转换不应抛异常")
    void nullFieldsShouldNotThrow() {
        assertTrue(ArchiveVO.entityToVo(new Archive()) != null);
        assertTrue(EvidenceVO.entityToVo(new Evidence()) != null);
    }
}
