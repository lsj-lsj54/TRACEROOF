package com.traceproof.model.vo.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 档案库影像分类分析（分组条形图：数量 + 总大小）
 *
 * @author lsj
 */
@Data
public class ArchiveCategoryAnalyzeVO implements Serializable {

    /**
     * 分类名称，影像未设置分类时为「未分类」
     */
    private String category;

    /**
     * 该分类下的影像数量
     */
    private Long count;

    /**
     * 该分类下的影像总大小（字节）
     */
    private Long totalSize;

    private static final long serialVersionUID = 1L;
}
