package com.traceproof.manager.auth;

import cn.dev33.satoken.stp.StpInterface;
import cn.hutool.core.util.ObjectUtil;
import cn.hutool.core.util.ReflectUtil;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.manager.auth.model.ArchiveUserAuthContext;
import com.traceproof.model.constant.ArchiveUserPermissionConstant;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.ArchiveUser;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.ArchiveRoleEnum;
import com.traceproof.model.enums.ArchiveTypeEnum;
import com.traceproof.model.enums.UserRoleEnum;
import com.traceproof.service.EvidenceService;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.extra.servlet.ServletUtil;
import cn.hutool.http.ContentType;
import cn.hutool.http.Header;
import cn.hutool.json.JSONUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.Collections;

import static com.traceproof.model.constant.UserConstant.USER_LOGIN_STATE;

/**
 * 自定义权限加载接口实现类
 */
@Slf4j
@Component    // 保证此类被 SpringBoot 扫描，完成 Sa-Token 的自定义权限验证扩展
public class StpInterfaceImpl implements StpInterface {

    @Resource
    private ArchiveUserAuthManager archiveUserAuthManager;
    @Resource
    private ArchiveUserService archiveUserService;
    @Resource
    private EvidenceService evidenceService;
    @Resource
    private UserService userService;
    @Resource
    private ArchiveService archiveService;

    /**
     * 返回一个账号所拥有的权限码集合
     */
    public List<String> getPermissionList(Object loginId, String loginType) {
        // 判断 loginType，仅对类型为 "archive" 进行权限校验
        if (!StpKit.ARCHIVE_TYPE.equals(loginType)) {
            return new ArrayList<>();
        }
        // 管理员权限，表示权限校验通过
        List<String> ADMIN_PERMISSIONS = archiveUserAuthManager.getPermissionsByRole(ArchiveRoleEnum.ADMIN.getValue());
        // 获取上下文对象
        ArchiveUserAuthContext authContext = getAuthContextByRequest();
        // 如果所有字段都为空，表示请求没有携带任何档案库/影像上下文（例如无参 POST），放行由业务层兜底
        if (isAllFieldsNull(authContext)) {
            return ADMIN_PERMISSIONS;
        }
        // 获取 userId
        User loginUser = (User) StpKit.ARCHIVE.getSessionByLoginId(loginId).get(USER_LOGIN_STATE);
        if (loginUser == null) {
            // 会话里没有用户信息（例如上线前签发的旧 token），退化为查库，避免直接判未登录
            loginUser = userService.getById(Long.valueOf(loginId.toString()));
        }
        if (loginUser == null) {
            throw new BusinessException(ErrorCode.NO_AUTH_ERROR, "用户未登录");
        }
        Long userId = loginUser.getId();
        // 优先从上下文中获取 ArchiveUser 对象
        ArchiveUser archiveUser = authContext.getArchiveUser();
        if (archiveUser != null) {
            return archiveUserAuthManager.getPermissionsByRole(archiveUser.getArchiveRole());
        }
        // 如果有 archiveUserId，必然是团队档案库，通过数据库查询 ArchiveUser 对象
        Long archiveUserId = authContext.getArchiveUserId();
        if (archiveUserId != null) {
            archiveUser = archiveUserService.getById(archiveUserId);
            if (archiveUser == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到档案库用户信息");
            }
            // 取出当前登录用户对应的 archiveUser
            ArchiveUser loginArchiveUser = archiveUserService.lambdaQuery()
                    .eq(ArchiveUser::getArchiveId, archiveUser.getArchiveId())
                    .eq(ArchiveUser::getUserId, userId)
                    .one();
            if (loginArchiveUser == null) {
                return new ArrayList<>();
            }
            // 这里会导致管理员在私有档案库没有权限，可以再查一次库处理
            return archiveUserAuthManager.getPermissionsByRole(loginArchiveUser.getArchiveRole());
        }
        // 如果没有 archiveUserId，尝试通过 archiveId 或 evidenceId 获取 Archive 对象并处理
        Long archiveId = authContext.getArchiveId();
        if (archiveId == null || archiveId == 0) {
            // 没有 archiveId（或公共案例库），通过 evidenceId 获取 Evidence 对象和 Archive 对象
            Long evidenceId = authContext.getEvidenceId();
            // 影像 id 也没有，则默认通过权限校验
            if (evidenceId == null) {
                return ADMIN_PERMISSIONS;
            }
            Evidence evidence = evidenceService.lambdaQuery()
                    .eq(Evidence::getId, evidenceId)
                    .select(Evidence::getId, Evidence::getArchiveId, Evidence::getUserId)
                    .one();
            if (evidence == null) {
                throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到影像信息");
            }
            archiveId = evidence.getArchiveId();
            // 公共案例库，仅本人或管理员可操作
            if (archiveId == null || archiveId == 0) {
                if (evidence.getUserId().equals(userId) || userService.isAdmin(loginUser)) {
                    return ADMIN_PERMISSIONS;
                } else {
                    // 不是自己的影像，仅可查看
                    return Collections.singletonList(ArchiveUserPermissionConstant.EVIDENCE_VIEW);
                }
            }
        }
        // 获取 Archive 对象
        Archive archive = archiveService.getById(archiveId);
        if (archive == null) {
            throw new BusinessException(ErrorCode.NOT_FOUND_ERROR, "未找到档案库信息");
        }
        // 根据 Archive 类型判断权限
        if (archive.getArchiveType() == ArchiveTypeEnum.PRIVATE.getValue()) {
            // 私有档案库，仅本人或管理员有权限
            if (archive.getUserId().equals(userId) || userService.isAdmin(loginUser)) {
                return ADMIN_PERMISSIONS;
            } else {
                return new ArrayList<>();
            }
        } else {
            // 团队档案库，查询 ArchiveUser 并获取角色和权限
            archiveUser = archiveUserService.lambdaQuery()
                    .eq(ArchiveUser::getArchiveId, archiveId)
                    .eq(ArchiveUser::getUserId, userId)
                    .one();
            if (archiveUser == null) {
                return new ArrayList<>();
            }
            return archiveUserAuthManager.getPermissionsByRole(archiveUser.getArchiveRole());
        }
    }


    private boolean isAllFieldsNull(Object object) {
        if (object == null) {
            return true; // 对象本身为空
        }
        // 获取所有字段并判断是否所有字段都为空
        return Arrays.stream(ReflectUtil.getFields(object.getClass()))
                // 获取字段值
                .map(field -> ReflectUtil.getFieldValue(object, field))
                // 检查是否所有字段都为空
                .allMatch(ObjectUtil::isEmpty);
    }


    /**
     * 返回一个账号所拥有的角色集合（全局角色，与档案库角色无关）
     * <p>
     * 不能像模板代码那样无条件返回 admin：{@code @SaCheckPermission(orRole = "admin")}
     * 一旦用上，任何人都能靠 orRole 绕过权限码校验。
     */
    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        // archive 体系只做权限码校验，不提供全局角色
        if (StpKit.ARCHIVE_TYPE.equals(loginType) || loginId == null) {
            return new ArrayList<>();
        }
        User loginUser = userService.getById(Long.valueOf(loginId.toString()));
        if (loginUser == null || !UserRoleEnum.ADMIN.getValue().equals(loginUser.getUserRole())) {
            return new ArrayList<>();
        }
        return Collections.singletonList(UserRoleEnum.ADMIN.getValue());
    }

    @Value("${server.servlet.context-path}")
    private String contextPath;

    /**
     * 从请求中获取上下文对象（把请求里的 id 解析成档案库 / 影像 / 成员 id）
     * <p>
     * 不同接口的 {@code id} 含义不同，靠路径前缀区分。注意成员接口的路径是
     * {@code /archive/user/**}，它的 id 是成员记录 id，必须在 {@code /archive/} 的规则之前判断，
     * 否则会被当成档案库 id 去算权限，导致改角色、退团队判到错误的档案库上。
     */
    private ArchiveUserAuthContext getAuthContextByRequest() {
        HttpServletRequest request = ((ServletRequestAttributes) RequestContextHolder.currentRequestAttributes()).getRequest();
        ArchiveUserAuthContext authRequest = parseAuthContext(request);
        // 根据请求路径区分 id 字段的含义
        Long id = authRequest.getId();
        if (ObjUtil.isNull(id)) {
            return authRequest;
        }
        String requestUri = request.getRequestURI();
        String partUri = StrUtil.removePrefix(requestUri, StrUtil.nullToEmpty(contextPath) + "/");
        if (StrUtil.startWith(partUri, "archive/user")) {
            // 成员管理：id 是 archive_user 记录 id
            authRequest.setArchiveUserId(id);
        } else if (StrUtil.startWith(partUri, "archive")) {
            authRequest.setArchiveId(id);
        } else if (StrUtil.startWith(partUri, "evidence")) {
            authRequest.setEvidenceId(id);
        }
        return authRequest;
    }

    /**
     * 按 Content-Type 从请求体或查询参数里解析上下文
     * <p>
     * 两个易踩的点：
     * 1. {@code application/json;charset=UTF-8} 这类带参数的内容类型，要按前缀匹配，
     *    否则会走到参数分支，拿不到 body 里的 archiveId；
     * 2. 允许请求体为空（例如无参 POST），空 body 或非法 JSON 时返回空上下文，
     *    不能让 JSON 解析异常把接口打成 500。
     */
    private ArchiveUserAuthContext parseAuthContext(HttpServletRequest request) {
        String contentType = request.getHeader(Header.CONTENT_TYPE.getValue());
        if (StrUtil.startWithIgnoreCase(contentType, ContentType.JSON.getValue())) {
            String body = ServletUtil.getBody(request);
            if (StrUtil.isBlank(body)) {
                return new ArchiveUserAuthContext();
            }
            try {
                ArchiveUserAuthContext authRequest = JSONUtil.toBean(body, ArchiveUserAuthContext.class);
                return authRequest == null ? new ArchiveUserAuthContext() : authRequest;
            } catch (Exception e) {
                log.warn("解析档案库鉴权上下文失败，按无档案库上下文处理：{}", e.getMessage());
                return new ArchiveUserAuthContext();
            }
        }
        Map<String, String> paramMap = ServletUtil.getParamMap(request);
        ArchiveUserAuthContext authRequest = BeanUtil.toBean(paramMap, ArchiveUserAuthContext.class);
        return authRequest == null ? new ArchiveUserAuthContext() : authRequest;
    }

}
