package com.traceproof.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceproof.model.dto.archive.ArchiveQueryRequest;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.ArchiveVO;
import com.traceproof.model.vo.UserVO;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 档案库查询条件构造与 VO 转换的测试
 * <p>
 * 主要防回归：
 * 1. 排序方向比较必须写成「常量在前」，否则 sortOrder 为 null 时会抛 NPE；
 * 2. 档案库级别、用户 id 等条件必须真的进了 SQL，否则筛选条件会被静默忽略；
 * 3. getArchiveVO / getArchiveVOPage 以前直接返回 null，接口等于不可用。
 */
class ArchiveServiceImplQueryWrapperTest {

    private final UserService userService = mock(UserService.class);

    /** TransactionTemplate / ArchiveUserService 只在真正写库时才会用到，构造查询条件不需要 */
    private final ArchiveServiceImpl archiveService = new ArchiveServiceImpl(userService, null, null);

    private Archive archive(long id, String name, Long userId) {
        Archive archive = new Archive();
        archive.setId(id);
        archive.setArchiveName(name);
        archive.setArchiveLevel(0);
        archive.setUserId(userId);
        return archive;
    }

    /* ------------------------------ 查询条件 ------------------------------ */

    @Test
    @DisplayName("sortOrder 为 null 时不应抛异常")
    void nullSortOrderShouldNotThrow() {
        ArchiveQueryRequest request = new ArchiveQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder(null);
        assertDoesNotThrow(() -> archiveService.getQueryWrapper(request));
    }

    @Test
    @DisplayName("请求为 null 时应返回空条件而不是报错")
    void nullRequestShouldReturnEmptyWrapper() {
        QueryWrapper<Archive> wrapper = archiveService.getQueryWrapper(null);
        assertTrue(wrapper.getSqlSegment().isEmpty(),
                "空请求不该带查询条件: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("档案库级别应进入查询条件")
    void archiveLevelShouldBeInCondition() {
        ArchiveQueryRequest request = new ArchiveQueryRequest();
        request.setArchiveLevel(1);

        QueryWrapper<Archive> wrapper = archiveService.getQueryWrapper(request);
        String sql = wrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("archivelevel"), "缺少档案库级别条件: " + sql);
    }

    @Test
    @DisplayName("用户 id 与档案库名称应进入查询条件")
    void userAndNameShouldBeInCondition() {
        ArchiveQueryRequest request = new ArchiveQueryRequest();
        request.setUserId(7L);
        request.setArchiveName("我的档案库");

        QueryWrapper<Archive> wrapper = archiveService.getQueryWrapper(request);
        String sql = wrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("userid"), "缺少用户条件: " + sql);
        assertTrue(sql.contains("archivename"), "缺少档案库名称条件: " + sql);
    }

    @Test
    @DisplayName("ascend 应生成升序、descend 应生成降序")
    void sortOrderShouldWork() {
        ArchiveQueryRequest ascend = new ArchiveQueryRequest();
        ascend.setSortField("createTime");
        ascend.setSortOrder("ascend");
        assertTrue(archiveService.getQueryWrapper(ascend).getSqlSegment().toUpperCase().contains("ASC"));

        ArchiveQueryRequest descend = new ArchiveQueryRequest();
        descend.setSortField("createTime");
        descend.setSortOrder("descend");
        assertTrue(archiveService.getQueryWrapper(descend).getSqlSegment().toUpperCase().contains("DESC"));
    }

    /* ------------------------------ 单条 VO ------------------------------ */

    @Test
    @DisplayName("getArchiveVO 应转出 VO 并带上创建人信息")
    void getArchiveVOShouldReturnVoWithUser() {
        User owner = new User();
        owner.setId(7L);
        owner.setUserAccount("lsj");
        UserVO ownerVO = new UserVO();
        ownerVO.setId(7L);
        ownerVO.setUserAccount("lsj");
        when(userService.getById(7L)).thenReturn(owner);
        when(userService.getUserVO(any(User.class))).thenReturn(ownerVO);

        ArchiveVO archiveVO = archiveService.getArchiveVO(archive(10L, "我的档案库", 7L), null);

        assertNotNull(archiveVO, "getArchiveVO 不能返回 null");
        assertEquals("我的档案库", archiveVO.getArchiveName());
        assertEquals(Long.valueOf(10L), archiveVO.getId());
        assertNotNull(archiveVO.getUser(), "缺少创建人信息，前端列表展示不出来");
        assertEquals("lsj", archiveVO.getUser().getUserAccount());
    }

    @Test
    @DisplayName("getArchiveVO 传 null 应返回 null")
    void getArchiveVOShouldHandleNull() {
        assertNull(archiveService.getArchiveVO(null, null));
    }

    /* ------------------------------ 分页 VO ------------------------------ */

    @Test
    @DisplayName("getArchiveVOPage 应转出分页 VO 并保留分页信息")
    void getArchiveVOPageShouldMapRecords() {
        User owner = new User();
        owner.setId(7L);
        UserVO ownerVO = new UserVO();
        ownerVO.setId(7L);
        when(userService.listByIds(any())).thenReturn(Arrays.asList(owner));
        when(userService.getUserVO(any(User.class))).thenReturn(ownerVO);

        List<Archive> records = new ArrayList<>(Arrays.asList(
                archive(10L, "档案库A", 7L), archive(11L, "档案库B", 7L)));
        Page<Archive> archivePage = new Page<>(2L, 10L, 25L);
        archivePage.setRecords(records);

        Page<ArchiveVO> voPage = archiveService.getArchiveVOPage(archivePage, null);

        assertNotNull(voPage, "getArchiveVOPage 不能返回 null");
        assertEquals(2L, voPage.getCurrent());
        assertEquals(10L, voPage.getSize());
        assertEquals(25L, voPage.getTotal());
        assertEquals(2, voPage.getRecords().size());
        assertEquals("档案库A", voPage.getRecords().get(0).getArchiveName());
        assertNotNull(voPage.getRecords().get(0).getUser());
    }

    @Test
    @DisplayName("getArchiveVOPage 空列表应返回空分页，不报错")
    void getArchiveVOPageShouldHandleEmptyRecords() {
        Page<Archive> archivePage = new Page<>(1L, 10L, 0L);
        archivePage.setRecords(new ArrayList<>());

        Page<ArchiveVO> voPage = archiveService.getArchiveVOPage(archivePage, null);

        assertNotNull(voPage);
        assertTrue(voPage.getRecords().isEmpty());
    }
}
