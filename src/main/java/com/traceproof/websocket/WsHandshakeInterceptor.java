package com.traceproof.websocket;

import cn.hutool.core.util.StrUtil;
import com.traceproof.manager.auth.ArchiveUserAuthManager;
import com.traceproof.model.constant.ArchiveUserPermissionConstant;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.ArchiveUser;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.ArchiveTypeEnum;
import com.traceproof.service.EvidenceService;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * WebSocket 握手拦截器
 * <p>
 * 在握手阶段完成登录校验与影像编辑权限校验，只有通过的用户才能建立连接。
 * 校验通过后把 user / userId / evidenceId 放进 attributes，供后续的
 * {@link EvidenceEditHandler} 从会话属性中读取。
 *
 * @author lsj
 */
@Slf4j
@Component
public class WsHandshakeInterceptor implements HandshakeInterceptor {

    @Resource
    private UserService userService;

    @Resource
    private EvidenceService evidenceService;

    @Resource
    private ArchiveService archiveService;

    @Resource
    private ArchiveUserService archiveUserService;

    @Resource
    private ArchiveUserAuthManager archiveUserAuthManager;

    @Override
    public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Map<String, Object> attributes) {
        if (request instanceof ServletServerHttpRequest) {
            HttpServletRequest servletRequest = ((ServletServerHttpRequest) request).getServletRequest();
            // 获取请求参数
            String evidenceId = servletRequest.getParameter("evidenceId");
            if (StrUtil.isBlank(evidenceId)) {
                log.error("缺少影像参数，拒绝握手");
                return false;
            }
            User loginUser = userService.getLoginUserOrNull(servletRequest);
            if (loginUser == null) {
                log.error("用户未登录，拒绝握手");
                return false;
            }
            // 校验影像是否存在
            Evidence evidence = evidenceService.getById(evidenceId);
            if (evidence == null) {
                log.error("影像不存在，拒绝握手");
                return false;
            }
            // 校验用户是否有该影像的编辑权限
            if (!hasEvidenceEditPermission(evidence, loginUser)) {
                log.error("没有影像编辑权限，拒绝握手");
                return false;
            }
            // 设置 attributes，供 EvidenceEditHandler 读取
            attributes.put("user", loginUser);
            attributes.put("userId", loginUser.getId());
            // 记得转换为 Long 类型
            attributes.put("evidenceId", Long.valueOf(evidenceId));
        }
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response, WebSocketHandler wsHandler, Exception exception) {
    }

    /**
     * 判断登录用户是否有该影像的编辑权限
     * <p>
     * 规则与 {@code EvidencePermissionUtils} / {@code StpInterfaceImpl} 保持一致：
     * <ul>
     *     <li>系统管理员直接放行；</li>
     *     <li>公共案例库（archiveId 为空）：仅上传者本人可编辑；</li>
     *     <li>私有档案库：仅档案库创建者可编辑；</li>
     *     <li>团队档案库：editor / admin 角色的成员可编辑。</li>
     * </ul>
     */
    private boolean hasEvidenceEditPermission(Evidence evidence, User loginUser) {
        // 管理员直接放行
        if (userService.isAdmin(loginUser)) {
            return true;
        }
        Long archiveId = evidence.getArchiveId();
        // 公共案例库：仅上传者本人可编辑
        if (archiveId == null || archiveId == 0) {
            return Objects.equals(loginUser.getId(), evidence.getUserId());
        }
        Archive archive = archiveService.getById(archiveId);
        if (archive == null) {
            return false;
        }
        // 私有档案库：仅创建者可编辑
        if (Objects.equals(archive.getArchiveType(), ArchiveTypeEnum.PRIVATE.getValue())) {
            return Objects.equals(loginUser.getId(), archive.getUserId());
        }
        // 团队档案库：editor / admin 角色成员可编辑
        ArchiveUser archiveUser = archiveUserService.getArchiveUser(archiveId, loginUser.getId());
        if (archiveUser == null) {
            return false;
        }
        List<String> permissionList = archiveUserAuthManager.getPermissionsByRole(archiveUser.getArchiveRole());
        return permissionList.contains(ArchiveUserPermissionConstant.EVIDENCE_EDIT);
    }
}
