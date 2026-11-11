package com.traceproof.manager.auth.model;

import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class ArchiveUserAuthConfig implements Serializable {

    /**
     * 权限列表
     */
    private List<ArchiveUserPermission> permissions;

    /**
     * 角色列表
     */
    private List<ArchiveUserRole> roles;

    private static final long serialVersionUID = 1L;
}
