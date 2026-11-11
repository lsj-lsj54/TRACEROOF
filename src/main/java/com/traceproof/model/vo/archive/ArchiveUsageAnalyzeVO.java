package com.traceproof.model.vo.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 档案库资源使用分析（仪表盘）
 * <p>
 * usedSize / maxSize、usedCount / maxCount 的占比分别驱动两个仪表盘；
 * 分析全部档案库时各字段是所有档案库的总和。
 *
 * @author lsj
 */
@Data
public class ArchiveUsageAnalyzeVO implements Serializable {

    /**
     * 已使用容量（字节）
     */
    private Long usedSize;

    /**
     * 容量配额（字节），全部档案库时为各档案库配额之和
     */
    private Long maxSize;

    /**
     * 已使用影像数量
     */
    private Long usedCount;

    /**
     * 数量配额，全部档案库时为各档案库数量配额之和
     */
    private Long maxCount;

    /**
     * 容量使用比例 0~100
     */
    private Double sizeRatio;

    /**
     * 数量使用比例 0~100
     */
    private Double countRatio;

    /**
     * 是否为全档案库（汇总）分析
     */
    private Boolean allArchive;

    private static final long serialVersionUID = 1L;
}
