package com.traceproof.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.traceproof.common.ArchivePermissionUtils;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.mapper.EvidenceMapper;
import com.traceproof.mapper.ArchiveMapper;
import com.traceproof.model.dto.archive.ArchiveAnalyzeRequest;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.archive.ArchiveCategoryAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveRankAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveSizeAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveTagAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveTrendAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveUsageAnalyzeVO;
import com.traceproof.service.ArchiveAnalyzeService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 档案库分析服务实现
 * <p>
 * 所有聚合都在数据库侧完成（count / sum / group by），
 * 只有标签解析因为 tags 是 JSON 数组字符串、需要拆分成多个标签，放在了应用侧统计。
 * 分析范围由 archiveId 决定：为空表示全系统（公共案例库 + 所有档案库，仅管理员），不为空表示单个档案库。
 *
 * @author lsj
 */
@Slf4j
@Service
@AllArgsConstructor
public class ArchiveAnalyzeServiceImpl implements ArchiveAnalyzeService {

    private static final long KB = 1024L;
    private static final long MB = 1024L * 1024L;

    /** 词云、排行等列表的默认返回条数与上限 */
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_LIMIT = 100;
    /** 排行榜默认取前 N 个档案库 */
    private static final int DEFAULT_TOP_N = 10;

    private final EvidenceMapper evidenceMapper;

    private final ArchiveMapper archiveMapper;

    private final UserService userService;

    /**
     * 档案库成员服务：团队档案库的分析权限对成员开放
     */
    private final ArchiveUserService archiveUserService;

    @Override
    public ArchiveUsageAnalyzeVO usage(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        checkAnalyzeAuth(analyzeRequest, loginUser);
        Long archiveId = analyzeRequest.getArchiveId();
        ArchiveUsageAnalyzeVO vo = new ArchiveUsageAnalyzeVO();
        vo.setAllArchive(archiveId == null || archiveId == 0);

        if (archiveId != null && archiveId > 0) {
            // 单个档案库：用量字段由上传 / 删除时维护，直接读档案库记录即可
            Archive archive = archiveMapper.selectById(archiveId);
            vo.setUsedSize(archive.getTotalSize());
            vo.setMaxSize(archive.getMaxSize());
            vo.setUsedCount(archive.getTotalCount());
            vo.setMaxCount(archive.getMaxCount());
        } else {
            // 全系统：已用量统计所有影像（公共案例库 + 所有档案库），配额取各档案库配额之和
            QueryWrapper<Evidence> evidenceWrapper = new QueryWrapper<>();
            evidenceWrapper.select("ifnull(sum(picSize), 0) as usedSize", "count(*) as usedCount");
            List<Map<String, Object>> rows = evidenceMapper.selectMaps(evidenceWrapper);
            Map<String, Object> row = rows.isEmpty() ? Collections.emptyMap() : rows.get(0);

            QueryWrapper<Archive> archiveWrapper = new QueryWrapper<>();
            archiveWrapper.select("ifnull(sum(maxSize), 0) as maxSize", "ifnull(sum(maxCount), 0) as maxCount");
            List<Map<String, Object>> archiveRows = archiveMapper.selectMaps(archiveWrapper);
            Map<String, Object> archiveRow = archiveRows.isEmpty() ? Collections.emptyMap() : archiveRows.get(0);

            vo.setUsedSize(toLong(row.get("usedSize")));
            vo.setMaxSize(toLong(archiveRow.get("maxSize")));
            vo.setUsedCount(toLong(row.get("usedCount")));
            vo.setMaxCount(toLong(archiveRow.get("maxCount")));
        }
        vo.setSizeRatio(ratio(vo.getUsedSize(), vo.getMaxSize()));
        vo.setCountRatio(ratio(vo.getUsedCount(), vo.getMaxCount()));
        return vo;
    }

    @Override
    public List<ArchiveCategoryAnalyzeVO> category(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        checkAnalyzeAuth(analyzeRequest, loginUser);

        QueryWrapper<Evidence> wrapper = new QueryWrapper<>();
        wrapper.select("if(category is null or category = '', '未分类', category) as category",
                        "count(*) as count",
                        "ifnull(sum(picSize), 0) as totalSize")
                .groupBy("category")
                .orderByDesc("count");
        applyArchiveScope(wrapper, analyzeRequest.getArchiveId());
        return evidenceMapper.selectMaps(wrapper).stream()
                .map(row -> {
                    ArchiveCategoryAnalyzeVO vo = new ArchiveCategoryAnalyzeVO();
                    vo.setCategory(String.valueOf(row.get("category")));
                    vo.setCount(toLong(row.get("count")));
                    vo.setTotalSize(toLong(row.get("totalSize")));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ArchiveTagAnalyzeVO> tag(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        checkAnalyzeAuth(analyzeRequest, loginUser);
        int limit = normalizeLimit(analyzeRequest.getLimit());

        // tags 是 JSON 数组字符串，数据库侧拆分不划算，只取出 tags 列在应用侧统计
        QueryWrapper<Evidence> wrapper = new QueryWrapper<>();
        wrapper.select("tags").isNotNull("tags").ne("tags", "");
        applyArchiveScope(wrapper, analyzeRequest.getArchiveId());
        Map<String, Long> tagCounts = new LinkedHashMap<>();
        for (Object tagsObj : evidenceMapper.selectObjs(wrapper)) {
            String tagsStr = tagsObj == null ? null : String.valueOf(tagsObj);
            if (StrUtil.isBlank(tagsStr)) {
                continue;
            }
            try {
                JSONArray tags = JSONUtil.parseArray(tagsStr);
                for (Object tag : tags) {
                    String tagText = StrUtil.trimToEmpty(String.valueOf(tag));
                    if (StrUtil.isNotEmpty(tagText)) {
                        tagCounts.merge(tagText, 1L, Long::sum);
                    }
                }
            } catch (Exception e) {
                // 个别脏数据解析失败只影响它自己，不能让整个分析接口挂掉
                log.warn("影像标签解析失败，已跳过: {}", tagsStr);
            }
        }
        return tagCounts.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .limit(limit)
                .map(entry -> {
                    ArchiveTagAnalyzeVO vo = new ArchiveTagAnalyzeVO();
                    vo.setTag(entry.getKey());
                    vo.setCount(entry.getValue());
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ArchiveSizeAnalyzeVO> size(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        checkAnalyzeAuth(analyzeRequest, loginUser);

        QueryWrapper<Evidence> wrapper = new QueryWrapper<>();
        wrapper.select("picSize");
        applyArchiveScope(wrapper, analyzeRequest.getArchiveId());
        long small = 0;
        long medium = 0;
        long large = 0;
        for (Object sizeObj : evidenceMapper.selectObjs(wrapper)) {
            long picSize = sizeObj == null ? 0 : ((Number) sizeObj).longValue();
            if (picSize < 100 * KB) {
                small++;
            } else if (picSize <= MB) {
                medium++;
            } else {
                large++;
            }
        }
        List<ArchiveSizeAnalyzeVO> result = new ArrayList<>();
        result.add(buildSizeVO("< 100 KB", small));
        result.add(buildSizeVO("100 KB - 1 MB", medium));
        result.add(buildSizeVO("> 1 MB", large));
        return result;
    }

    @Override
    public List<ArchiveTrendAnalyzeVO> trend(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        checkAnalyzeAuth(analyzeRequest, loginUser);
        String dimension = StrUtil.blankToDefault(analyzeRequest.getTimeDimension(), "day");
        ThrowUtils.throwIf(!"day".equals(dimension) && !"week".equals(dimension) && !"month".equals(dimension),
                ErrorCode.PARAMS_ERROR, "时间维度仅支持 day / week / month");
        // MySQL 格式：%Y-%m-%d 按天；%Y-%u 按周（周一起算）；%Y-%m 按月
        String dateFormat;
        if ("week".equals(dimension)) {
            dateFormat = "'%Y-%u'";
        } else if ("month".equals(dimension)) {
            dateFormat = "'%Y-%m'";
        } else {
            dateFormat = "'%Y-%m-%d'";
        }

        QueryWrapper<Evidence> wrapper = new QueryWrapper<>();
        wrapper.select("DATE_FORMAT(createTime, " + dateFormat + ") as date",
                        "count(*) as count")
                .groupBy("date")
                .orderByAsc("date");
        applyArchiveScope(wrapper, analyzeRequest.getArchiveId());
        return evidenceMapper.selectMaps(wrapper).stream()
                .map(row -> {
                    ArchiveTrendAnalyzeVO vo = new ArchiveTrendAnalyzeVO();
                    vo.setDate(String.valueOf(row.get("date")));
                    vo.setCount(toLong(row.get("count")));
                    return vo;
                })
                .collect(Collectors.toList());
    }

    @Override
    public List<ArchiveRankAnalyzeVO> rank(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        // 排行是管理员视角的全局统计：控制器上有 @AuthCheck，这里再兜一道，避免服务被直接调用绕过鉴权
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "未登录");
        ThrowUtils.throwIf(!userService.isAdmin(loginUser), ErrorCode.NO_AUTH_ERROR, "仅管理员可查看档案库排行");

        int topN = analyzeRequest.getLimit() == null ? DEFAULT_TOP_N
                : Math.min(Math.max(analyzeRequest.getLimit(), 1), MAX_LIMIT);

        // 全系统已用容量作为占比分母（包含公共案例库）
        QueryWrapper<Evidence> totalWrapper = new QueryWrapper<>();
        totalWrapper.select("ifnull(sum(picSize), 0) as total");
        long totalUsed = toLong(evidenceMapper.selectMaps(totalWrapper)
                .stream().findFirst().map(row -> row.get("total")).orElse(0L));

        QueryWrapper<Archive> wrapper = new QueryWrapper<>();
        wrapper.orderByDesc("totalSize").last("limit " + topN);
        return archiveMapper.selectList(wrapper).stream()
                .map(archive -> {
                    ArchiveRankAnalyzeVO vo = new ArchiveRankAnalyzeVO();
                    vo.setArchiveId(archive.getId());
                    vo.setArchiveName(archive.getArchiveName());
                    vo.setTotalSize(archive.getTotalSize());
                    vo.setTotalCount(archive.getTotalCount());
                    vo.setSizeRatio(totalUsed > 0
                            ? Math.round(archive.getTotalSize() * 1000.0 / totalUsed) / 10.0 : 0.0);
                    return vo;
                })
                .collect(Collectors.toList());
    }

    /**
     * 把分析范围拼进影像查询条件
     * <p>
     * archiveId 为空表示全系统（公共案例库 + 所有档案库），不加过滤；
     * 不为空时只统计该档案库内的影像。
     * <p>
     * 注意：这一步必须拼在条件里，漏掉会让「分析某个档案库」实际返回全系统数据，
     * 既是数据错误，也会把别人的档案库数据泄露给档案库创建者。
     */
    private void applyArchiveScope(QueryWrapper<Evidence> wrapper, Long archiveId) {
        if (archiveId != null && archiveId > 0) {
            wrapper.eq("archiveId", archiveId);
        }
    }

    /**
     * 分析权限校验：指定档案库仅创建者、团队成员（或管理员）可看，全部档案库仅管理员
     */
    private void checkAnalyzeAuth(ArchiveAnalyzeRequest analyzeRequest, User loginUser) {
        ThrowUtils.throwIf(analyzeRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null,
                ErrorCode.NOT_LOGIN_ERROR, "未登录");
        if (analyzeRequest.getArchiveId() != null && analyzeRequest.getArchiveId() > 0) {
            Archive archive = archiveMapper.selectById(analyzeRequest.getArchiveId());
            ArchivePermissionUtils.checkArchiveViewAuth(archive, loginUser, userService, archiveUserService);
        } else if (!userService.isAdmin(loginUser)) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "仅管理员可分析全部档案库");
        }
    }

    private ArchiveSizeAnalyzeVO buildSizeVO(String range, long count) {
        ArchiveSizeAnalyzeVO vo = new ArchiveSizeAnalyzeVO();
        vo.setSizeRange(range);
        vo.setCount(count);
        return vo;
    }

    /**
     * 返回条数归一化：默认 20，范围 1~100
     */
    private int normalizeLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_LIMIT;
        }
        return Math.min(Math.max(limit, 1), MAX_LIMIT);
    }

    /**
     * 占比 0~100，保留 1 位小数；配额为空或 0 时返回 0
     */
    private Double ratio(Long used, Long max) {
        if (used == null || max == null || max <= 0) {
            return 0.0;
        }
        return Math.min(Math.round(used * 1000.0 / max) / 10.0, 100.0);
    }

    private Long toLong(Object value) {
        if (value == null) {
            return 0L;
        }
        return value instanceof Number ? ((Number) value).longValue()
                : Long.parseLong(String.valueOf(value));
    }
}
