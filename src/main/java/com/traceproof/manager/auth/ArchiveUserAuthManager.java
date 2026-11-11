package com.traceproof.manager.auth;

import cn.hutool.core.io.resource.ResourceUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.traceproof.manager.auth.model.ArchiveUserAuthConfig;
import com.traceproof.manager.auth.model.ArchiveUserRole;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.List;

@Component
public class ArchiveUserAuthManager {

    @Resource
    private ArchiveUserService archiveUserService;

    @Resource
    private UserService userService;

    public static final ArchiveUserAuthConfig ARCHIVE_USER_AUTH_CONFIG;

    static {
        String json = ResourceUtil.readUtf8Str("all/ArchiveUserAuthConfig.json");
        ARCHIVE_USER_AUTH_CONFIG = JSONUtil.toBean(json, ArchiveUserAuthConfig.class);
    }

    /**
     * 根据角色获取权限列表
     */
    public List<String> getPermissionsByRole(String archiveUserRole) {
        if (StrUtil.isBlank(archiveUserRole)) {
            return new ArrayList<>();
        }
        // 找到匹配的角色
        ArchiveUserRole role = ARCHIVE_USER_AUTH_CONFIG.getRoles().stream()
                .filter(r -> archiveUserRole.equals(r.getKey()))
                .findFirst()
                .orElse(null);
        if (role == null) {
            return new ArrayList<>();
        }
        return role.getPermissions();
    }




}
