package com.traceproof.model.vo.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 档案库影像标签分析（词云图）
 *
 * @author lsj
 */
@Data
public class ArchiveTagAnalyzeVO implements Serializable {

    /**
     * 标签文本
     */
    private String tag;

    /**
     * 关联影像数量
     */
    private Long count;

    private static final long serialVersionUID = 1L;
}
