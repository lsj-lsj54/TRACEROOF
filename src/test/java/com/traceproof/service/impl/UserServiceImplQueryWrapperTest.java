package com.traceproof.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.traceproof.model.dto.user.UserQueryRequest;
import com.traceproof.model.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用户查询条件构造的测试
 * <p>
 * 重点覆盖排序字段缺省/为 null 的情况：sortOrder 为 null 时若写成
 * sortOrder.equals("ascend") 会直接抛 NPE（在 isNotBlank 判断之前就执行了）。
 */
class UserServiceImplQueryWrapperTest {

    private final UserServiceImpl userService = new UserServiceImpl();

    @Test
    @DisplayName("sortOrder 为 null 时不应抛异常")
    void nullSortOrderShouldNotThrow() {
        UserQueryRequest request = new UserQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder(null);

        assertDoesNotThrow(() -> userService.getQueryWrapper(request));
    }

    @Test
    @DisplayName("完全不设置排序字段时不应抛异常")
    void unsetSortOrderShouldNotThrow() {
        UserQueryRequest request = new UserQueryRequest();
        assertDoesNotThrow(() -> userService.getQueryWrapper(request));
    }

    @Test
    @DisplayName("ascend 应生成升序排序")
    void ascendShouldProduceAscOrder() {
        UserQueryRequest request = new UserQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder("ascend");

        QueryWrapper<User> wrapper = userService.getQueryWrapper(request);
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("ORDER BY"),
                "未生成排序片段: " + wrapper.getSqlSegment());
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("ASC"),
                "未生成升序: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("descend 应生成降序排序")
    void descendShouldProduceDescOrder() {
        UserQueryRequest request = new UserQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder("descend");

        QueryWrapper<User> wrapper = userService.getQueryWrapper(request);
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("DESC"),
                "未生成降序: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("按昵称与角色过滤应生效")
    void filtersShouldWork() {
        UserQueryRequest request = new UserQueryRequest();
        request.setUserName("lsj");
        request.setUserRole("admin");

        QueryWrapper<User> wrapper = userService.getQueryWrapper(request);
        String sql = wrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("username"), "缺少昵称条件: " + sql);
        assertTrue(sql.contains("userrole"), "缺少角色条件: " + sql);
    }
}
