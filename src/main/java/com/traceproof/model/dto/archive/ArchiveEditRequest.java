package com.traceproof.model.dto.archive;

import lombok.Data;

import java.io.Serializable;

@Data
public class ArchiveEditRequest implements Serializable {

    /**
     * 档案库 id
     */
    private Long id;

    /**
     * 档案库名称
     */
    private String archiveName;

    private static final long serialVersionUID = 1L;
}
