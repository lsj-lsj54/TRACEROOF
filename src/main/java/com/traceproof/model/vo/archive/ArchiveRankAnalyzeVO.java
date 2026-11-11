package com.traceproof.model.vo.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 档案库使用排行（管理员，柱状图）
 *
 * @author lsj
 */
@Data
public class ArchiveRankAnalyzeVO implements Serializable {

    /**
     * 档案库 id
     */
    private Long archiveId;

    /**
     * 档案库名称
     */
    private String archiveName;

    /**
     * 已用容量（字节）
     */
    private Long totalSize;

    /**
     * 影像数量
     */
    private Long totalCount;

    /**
     * 占全系统已用容量的比例 0~100
     */
    private Double sizeRatio;

    private static final long serialVersionUID = 1L;
}
