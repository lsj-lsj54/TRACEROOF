package com.traceproof.model.dto.archive;

import lombok.Data;

import java.io.Serializable;

@Data
public class ArchiveAddRequest implements Serializable {

    /**
     * 档案库名称
     */
    private String archiveName;

    /**
     * 档案库级别：0-普通版 1-专业版 2-旗舰版
     */
    private Integer archiveLevel;

    /**
     * 档案库类型：0-私有 1-团队（不传默认为私有）
     */
    private Integer archiveType;

    private static final long serialVersionUID = 1L;
}
