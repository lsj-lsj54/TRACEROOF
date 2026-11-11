package com.traceproof.model.dto.archive;

import lombok.Data;

import java.io.Serializable;

/**
 * 档案库分析请求
 * <p>
 * 同一个请求体复用在全部分析接口上：各接口只取自己关心的字段，
 * 未传的字段按各自默认值处理。
 *
 * @author lsj
 */
@Data
public class ArchiveAnalyzeRequest implements Serializable {

    /**
     * 档案库 id
     * <p>
     * 为空表示分析「全部档案库 / 公共案例库」，该能力仅管理员可用；
     * 不为空时只能分析自己的档案库（管理员可分析任意档案库）。
     */
    private Long archiveId;

    /**
     * 上传趋势的时间维度：day-按天（默认）/ week-按周 / month-按月
     */
    private String timeDimension;

    /**
     * 词云、排行等列表的最大返回条数，默认 20，上限 100
     */
    private Integer limit;

    private static final long serialVersionUID = 1L;
}
