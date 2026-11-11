package com.traceproof.common;

import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.service.UserService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 档案库权限校验的测试
 * <p>
 * 规则：私有档案库只有档案库创建者和管理员能看、能动，别人一律拒绝。
 * 这条规则是「不能看别人档案库」的最后一道防线，必须逐个分支覆盖。
 */
class ArchivePermissionUtilsTest {

    private static final long OWNER_ID = 1L;
    private static final long OTHER_ID = 2L;
    private static final long ARCHIVE_ID = 10L;

    private User user(long id) {
        User user = new User();
        user.setId(id);
        return user;
    }

    private Archive archive(long ownerId) {
        Archive archive = new Archive();
        archive.setId(ARCHIVE_ID);
        archive.setUserId(ownerId);
        return archive;
    }

    private UserService userService(boolean admin) {
        UserService userService = mock(UserService.class);
        when(userService.isAdmin(any(User.class))).thenReturn(admin);
        return userService;
    }

    @Test
    @DisplayName("档案库创建者可以操作自己的档案库")
    void ownerShouldPass() {
        assertDoesNotThrow(() -> ArchivePermissionUtils.checkArchiveAuth(
                archive(OWNER_ID), user(OWNER_ID), userService(false)));
    }

    @Test
    @DisplayName("管理员可以操作任意档案库")
    void adminShouldPass() {
        assertDoesNotThrow(() -> ArchivePermissionUtils.checkArchiveAuth(
                archive(OWNER_ID), user(OTHER_ID), userService(true)));
    }

    @Test
    @DisplayName("别人不能操作我的档案库")
    void otherUserShouldBeRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> ArchivePermissionUtils.checkArchiveAuth(
                archive(OWNER_ID), user(OTHER_ID), userService(false)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("别人不能查看我的档案库")
    void otherUserShouldNotView() {
        BusinessException e = assertThrows(BusinessException.class, () -> ArchivePermissionUtils.checkArchiveViewAuth(
                archive(OWNER_ID), user(OTHER_ID), userService(false)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("档案库创建者可以查看自己的档案库")
    void ownerShouldView() {
        assertDoesNotThrow(() -> ArchivePermissionUtils.checkArchiveViewAuth(
                archive(OWNER_ID), user(OWNER_ID), userService(false)));
    }

    @Test
    @DisplayName("未登录不能查看或操作档案库")
    void anonymousShouldBeRejected() {
        BusinessException e = assertThrows(BusinessException.class, () -> ArchivePermissionUtils.checkArchiveViewAuth(
                archive(OWNER_ID), null, userService(false)));
        assertEquals(ErrorCode.NO_AUTH_ERROR.getCode(), e.getCode());
    }

    @Test
    @DisplayName("档案库不存在时应报「数据不存在」而不是无权限")
    void missingArchiveShouldReportNotFound() {
        BusinessException e = assertThrows(BusinessException.class, () -> ArchivePermissionUtils.checkArchiveAuth(
                null, user(OWNER_ID), userService(false)));
        assertEquals(ErrorCode.NOT_FOUND_ERROR.getCode(), e.getCode());
    }
}
