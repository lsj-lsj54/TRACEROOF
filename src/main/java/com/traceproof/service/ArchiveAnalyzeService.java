package com.traceproof.service;

import com.traceproof.model.dto.archive.ArchiveAnalyzeRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.archive.ArchiveCategoryAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveRankAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveSizeAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveTagAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveTrendAnalyzeVO;
import com.traceproof.model.vo.archive.ArchiveUsageAnalyzeVO;

import java.util.List;

/**
 * 档案库分析服务
 * <p>
 * 统一负责分析接口的权限校验：archiveId 为空时仅管理员可分析全部档案库，
 * 不为空时仅档案库创建者（或管理员）可分析自己的档案库。
 *
 * @author lsj
 */
public interface ArchiveAnalyzeService {

    /**
     * 档案库资源使用分析（已用/配额比例，驱动仪表盘）
     */
    ArchiveUsageAnalyzeVO usage(ArchiveAnalyzeRequest analyzeRequest, User loginUser);

    /**
     * 影像分类分析（各分类的数量与总大小，驱动分组条形图）
     */
    List<ArchiveCategoryAnalyzeVO> category(ArchiveAnalyzeRequest analyzeRequest, User loginUser);

    /**
     * 影像标签分析（标签关联影像数，驱动词云图）
     */
    List<ArchiveTagAnalyzeVO> tag(ArchiveAnalyzeRequest analyzeRequest, User loginUser);

    /**
     * 影像大小分段分析（< 100 KB / 100 KB-1 MB / > 1 MB，驱动饼图）
     */
    List<ArchiveSizeAnalyzeVO> size(ArchiveAnalyzeRequest analyzeRequest, User loginUser);

    /**
     * 上传行为分析（按天 / 周 / 月的上传数量趋势，驱动折线图）
     */
    List<ArchiveTrendAnalyzeVO> trend(ArchiveAnalyzeRequest analyzeRequest, User loginUser);

    /**
     * 档案库使用排行（按已用容量排序的前 N 个档案库，驱动柱状图，仅管理员）
     */
    List<ArchiveRankAnalyzeVO> rank(ArchiveAnalyzeRequest analyzeRequest, User loginUser);
}
