package com.traceproof.model.dto.archiveuser;

import lombok.Data;

import java.io.Serializable;

@Data
public class ArchiveUserAddRequest implements Serializable {

    /**
     * 档案库 ID
     */
    private Long archiveId;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 档案库角色：viewer/editor/admin
     */
    private String archiveRole;

    private static final long serialVersionUID = 1L;
}
