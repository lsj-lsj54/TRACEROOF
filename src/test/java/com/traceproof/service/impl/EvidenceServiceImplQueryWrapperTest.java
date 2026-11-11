package com.traceproof.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.traceproof.model.dto.evidence.EvidenceQueryRequest;
import com.traceproof.model.entity.Evidence;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 影像查询条件构造的测试
 * <p>
 * 主要防回归：排序方向比较必须写成「常量在前」，
 * 否则 sortOrder 为 null 时会抛 NPE（UserServiceImpl 里就踩过这个坑）。
 */
class EvidenceServiceImplQueryWrapperTest {

    private final EvidenceServiceImpl evidenceService = new EvidenceServiceImpl();

    @Test
    @DisplayName("sortOrder 为 null 时不应抛异常")
    void nullSortOrderShouldNotThrow() {
        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder(null);
        assertDoesNotThrow(() -> evidenceService.getQueryWrapper(request));
    }

    @Test
    @DisplayName("请求为 null 时应返回空条件而不是报错")
    void nullRequestShouldReturnEmptyWrapper() {
        QueryWrapper<Evidence> wrapper = evidenceService.getQueryWrapper(null);
        assertTrue(wrapper.getSqlSegment().isEmpty(),
                "空请求不该带查询条件: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("ascend 应生成升序排序")
    void ascendShouldProduceAscOrder() {
        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder("ascend");

        QueryWrapper<Evidence> wrapper = evidenceService.getQueryWrapper(request);
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("ASC"),
                "未生成升序: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("descend 应生成降序排序")
    void descendShouldProduceDescOrder() {
        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setSortField("createTime");
        request.setSortOrder("descend");

        QueryWrapper<Evidence> wrapper = evidenceService.getQueryWrapper(request);
        assertTrue(wrapper.getSqlSegment().toUpperCase().contains("DESC"),
                "未生成降序: " + wrapper.getSqlSegment());
    }

    @Test
    @DisplayName("搜索词应同时匹配名称与简介")
    void searchTextShouldMatchNameAndIntroduction() {
        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setSearchText("海");

        QueryWrapper<Evidence> wrapper = evidenceService.getQueryWrapper(request);
        String sql = wrapper.getSqlSegment().toLowerCase();
        assertTrue(sql.contains("name"), "缺少名称匹配: " + sql);
        assertTrue(sql.contains("introduction"), "缺少简介匹配: " + sql);
    }

    @Test
    @DisplayName("按主色调查询应生成颜色距离筛选与排序，且不叠加通用排序")
    void picColorShouldProduceDistanceFilter() {
        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setPicColor("#736246");
        // 即便传了通用排序，按颜色查询时也应被忽略
        request.setSortField("createTime");
        request.setSortOrder("descend");

        QueryWrapper<Evidence> wrapper = evidenceService.getQueryWrapper(request);
        String sql = wrapper.getSqlSegment().toUpperCase();
        assertTrue(sql.contains("PICCOLORR"), "缺少主色调距离计算: " + sql);
        assertTrue(sql.contains("POW("), "缺少 POW 计算: " + sql);
        assertTrue(sql.contains("ASC"), "应按颜色距离升序: " + sql);
        assertFalse(sql.contains("CREATETIME"), "按颜色查询不应叠加通用排序: " + sql);
    }

    @Test
    @DisplayName("非法主色调不应触发颜色查询，回退到通用排序")
    void invalidPicColorIgnored() {
        EvidenceQueryRequest request = new EvidenceQueryRequest();
        request.setPicColor("notacolor");
        request.setSortField("createTime");
        request.setSortOrder("descend");

        QueryWrapper<Evidence> wrapper = evidenceService.getQueryWrapper(request);
        String sql = wrapper.getSqlSegment().toUpperCase();
        assertFalse(sql.contains("PICCOLORR"), "非法颜色不应生成颜色筛选: " + sql);
        assertTrue(sql.contains("DESC"), "非法颜色应回退到通用排序: " + sql);
    }
}
