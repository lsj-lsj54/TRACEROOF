package com.traceproof.model.dto.archive;

import lombok.Data;

import java.io.Serializable;


@Data
public class ArchiveDeleteRequest implements Serializable {

    /**
     * 档案库id
     */
    private Long id;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 档案库名称
     */
    private String archiveName;

    /**
     * 档案库级别：0-普通版 1-专业版 2-旗舰版
     */
    private Integer archiveLevel;

    private static final long serialVersionUID = 1L;
}
