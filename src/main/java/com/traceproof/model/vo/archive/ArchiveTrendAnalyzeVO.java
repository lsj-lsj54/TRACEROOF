package com.traceproof.model.vo.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 上传行为分析（折线图）
 *
 * @author lsj
 */
@Data
public class ArchiveTrendAnalyzeVO implements Serializable {

    /**
     * 时间标签：按天 yyyy-MM-dd / 按周 yyyy-ww / 按月 yyyy-MM
     */
    private String date;

    /**
     * 该时间段的上传影像数量
     */
    private Long count;

    private static final long serialVersionUID = 1L;
}
