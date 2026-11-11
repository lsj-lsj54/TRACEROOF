package com.traceproof.model.dto.archive;

import lombok.Data;

import java.io.Serializable;

@Data
public class ArchiveUpdateRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 档案库名称
     */
    private String archiveName;

    /**
     * 档案库级别：0-普通版 1-专业版 2-旗舰版
     */
    private Integer archiveLevel;

    /**
     * 档案库影像的最大总大小
     */
    private Long maxSize;

    /**
     * 档案库影像的最大数量
     */
    private Long maxCount;

    private static final long serialVersionUID = 1L;
}
