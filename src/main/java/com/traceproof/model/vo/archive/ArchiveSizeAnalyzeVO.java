package com.traceproof.model.vo.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 档案库影像大小分析（饼图）
 *
 * @author lsj
 */
@Data
public class ArchiveSizeAnalyzeVO implements Serializable {

    /**
     * 大小区间描述，例如「< 100 KB」
     */
    private String sizeRange;

    /**
     * 该区间内的影像数量
     */
    private Long count;

    private static final long serialVersionUID = 1L;
}
