package com.traceproof.model.dto.archiveuser;

import lombok.Data;

import java.io.Serializable;

@Data
public class ArchiveUserEditRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 档案库角色：viewer/editor/admin
     */
    private String archiveRole;

    private static final long serialVersionUID = 1L;
}
