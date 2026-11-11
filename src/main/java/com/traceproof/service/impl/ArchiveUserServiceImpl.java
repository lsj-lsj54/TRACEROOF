package com.traceproof.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.mapper.ArchiveMapper;
import com.traceproof.mapper.ArchiveUserMapper;
import com.traceproof.model.dto.archiveuser.ArchiveUserAddRequest;
import com.traceproof.model.dto.archiveuser.ArchiveUserEditRequest;
import com.traceproof.model.dto.archiveuser.ArchiveUserQueryRequest;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.ArchiveUser;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.ArchiveRoleEnum;
import com.traceproof.model.enums.ArchiveTypeEnum;
import com.traceproof.model.vo.ArchiveUserVO;
import com.traceproof.model.vo.ArchiveVO;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @author lsj
 * @description 针对表【archive_user(档案库用户关联)】的数据库操作Service实现
 * @createDate 2026-10-22
 */
@Service
public class ArchiveUserServiceImpl extends ServiceImpl<ArchiveUserMapper, ArchiveUser>
        implements ArchiveUserService {

    /**
     * 校验档案库是否存在直接走 ArchiveMapper，而不是注入 ArchiveService：
     * ArchiveServiceImpl 创建团队档案库时要反过来调本服务写创建者的成员记录，
     * 互相注入会形成循环依赖。
     */
    private final ArchiveMapper archiveMapper;

    private final UserService userService;

    public ArchiveUserServiceImpl(ArchiveMapper archiveMapper, UserService userService) {
        this.archiveMapper = archiveMapper;
        this.userService = userService;
    }

    @Override
    public long addArchiveUser(ArchiveUserAddRequest archiveUserAddRequest, User loginUser) {
        ThrowUtils.throwIf(archiveUserAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        ArchiveUser archiveUser = new ArchiveUser();
        BeanUtils.copyProperties(archiveUserAddRequest, archiveUser);
        // 不传角色时默认给浏览者，权限最小化
        if (StrUtil.isBlank(archiveUser.getArchiveRole())) {
            archiveUser.setArchiveRole(ArchiveRoleEnum.VIEWER.getValue());
        }
        validArchiveUser(archiveUser, true);
        Long archiveId = archiveUser.getArchiveId();
        Archive archive = archiveMapper.selectById(archiveId);
        // 只有团队档案库才有成员概念
        ThrowUtils.throwIf(!Objects.equals(ArchiveTypeEnum.TEAM.getValue(), archive.getArchiveType()),
                ErrorCode.PARAMS_ERROR, "只有团队档案库才能邀请成员");
        // 仅档案库创建者、团队管理员或系统管理员可以邀请
        ThrowUtils.throwIf(!canManageMember(archive, loginUser),
                ErrorCode.NO_AUTH_ERROR, "无权限邀请成员");
        // 防止重复邀请，数据库唯一索引做兜底
        long count = this.count(new QueryWrapper<ArchiveUser>()
                .eq("archiveId", archiveId)
                .eq("userId", archiveUser.getUserId()));
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "该用户已在团队中");
        ThrowUtils.throwIf(!this.save(archiveUser), ErrorCode.SYSTEM_ERROR, "邀请成员失败");
        return archiveUser.getId();
    }

    @Override
    public boolean deleteArchiveUser(long id, User loginUser) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR, "成员记录 id 不能为空");
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        ArchiveUser archiveUser = this.getById(id);
        ThrowUtils.throwIf(archiveUser == null, ErrorCode.NOT_FOUND_ERROR, "成员记录不存在");
        Archive archive = archiveMapper.selectById(archiveUser.getArchiveId());
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        // 创建者本人的成员记录与档案库共存亡，不能单独移除
        ThrowUtils.throwIf(Objects.equals(archive.getUserId(), archiveUser.getUserId()),
                ErrorCode.OPERATION_ERROR, "不能移除档案库创建者");
        // 创建者 / 团队管理员 / 系统管理员可移除他人；普通成员可移除自己（退出团队）
        boolean isSelf = Objects.equals(loginUser.getId(), archiveUser.getUserId());
        ThrowUtils.throwIf(!isSelf && !canManageMember(archive, loginUser),
                ErrorCode.NO_AUTH_ERROR, "无权限移除该成员");
        return this.removeById(id);
    }

    @Override
    public boolean editArchiveUser(ArchiveUserEditRequest archiveUserEditRequest, User loginUser) {
        ThrowUtils.throwIf(archiveUserEditRequest == null || archiveUserEditRequest.getId() == null,
                ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        ArchiveUser oldArchiveUser = this.getById(archiveUserEditRequest.getId());
        ThrowUtils.throwIf(oldArchiveUser == null, ErrorCode.NOT_FOUND_ERROR, "成员记录不存在");
        Archive archive = archiveMapper.selectById(oldArchiveUser.getArchiveId());
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        // 角色调整只放开给创建者和系统管理员，团队管理员也不能改，防止互相提权
        boolean isCreator = Objects.equals(archive.getUserId(), loginUser.getId());
        ThrowUtils.throwIf(!isCreator && !userService.isAdmin(loginUser),
                ErrorCode.NO_AUTH_ERROR, "仅档案库创建者可调整成员角色");
        ThrowUtils.throwIf(Objects.equals(archive.getUserId(), oldArchiveUser.getUserId()),
                ErrorCode.OPERATION_ERROR, "不能修改档案库创建者的角色");
        ArchiveUser archiveUser = new ArchiveUser();
        archiveUser.setId(oldArchiveUser.getId());
        archiveUser.setArchiveRole(archiveUserEditRequest.getArchiveRole());
        validArchiveUser(archiveUser, false);
        return this.updateById(archiveUser);
    }

    @Override
    public List<ArchiveUserVO> listArchiveUserVO(ArchiveUserQueryRequest archiveUserQueryRequest, User loginUser) {
        ThrowUtils.throwIf(archiveUserQueryRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        Long archiveId = archiveUserQueryRequest.getArchiveId();
        ThrowUtils.throwIf(archiveId == null || archiveId <= 0, ErrorCode.PARAMS_ERROR, "档案库 id 不能为空");
        Archive archive = archiveMapper.selectById(archiveId);
        ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        // 成员列表对团队内成员开放；非成员只有系统管理员能看
        boolean isMember = this.getArchiveUser(archiveId, loginUser.getId()) != null;
        boolean isCreator = Objects.equals(archive.getUserId(), loginUser.getId());
        ThrowUtils.throwIf(!isMember && !isCreator && !userService.isAdmin(loginUser),
                ErrorCode.NO_AUTH_ERROR, "无权限查看该档案库成员");
        List<ArchiveUser> archiveUserList = this.list(getQueryWrapper(archiveUserQueryRequest));
        if (CollUtil.isEmpty(archiveUserList)) {
            return Collections.emptyList();
        }
        // 批量查用户信息，避免 N+1
        Set<Long> userIdSet = archiveUserList.stream().map(ArchiveUser::getUserId).collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userService.listByIds(userIdSet).stream()
                .collect(Collectors.groupingBy(User::getId));
        ArchiveVO archiveVO = ArchiveVO.entityToVo(archive);
        return archiveUserList.stream().map(archiveUser -> {
            ArchiveUserVO archiveUserVO = ArchiveUserVO.objToVo(archiveUser);
            List<User> userList = userIdUserListMap.get(archiveUser.getUserId());
            archiveUserVO.setUser(userService.getUserVO(CollUtil.isEmpty(userList) ? null : userList.get(0)));
            archiveUserVO.setArchive(archiveVO);
            return archiveUserVO;
        }).collect(Collectors.toList());
    }

    @Override
    public List<ArchiveUserVO> listMyTeamArchive(User loginUser) {
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR, "未登录");
        List<ArchiveUser> archiveUserList = this.list(new QueryWrapper<ArchiveUser>()
                .eq("userId", loginUser.getId()));
        if (CollUtil.isEmpty(archiveUserList)) {
            return Collections.emptyList();
        }
        // 批量查档案库信息，成员表只关联团队档案库，但档案库可能被删，查不到的要跳过
        Set<Long> archiveIdSet = archiveUserList.stream().map(ArchiveUser::getArchiveId).collect(Collectors.toSet());
        Map<Long, List<Archive>> archiveIdArchiveListMap = archiveMapper.selectByIds(archiveIdSet).stream()
                .collect(Collectors.groupingBy(Archive::getId));
        // 用户信息即当前登录用户，逐条填充即可，无需再查库
        return archiveUserList.stream().map(archiveUser -> {
            List<Archive> archiveList = archiveIdArchiveListMap.get(archiveUser.getArchiveId());
            if (CollUtil.isEmpty(archiveList)) {
                return null;
            }
            Archive archive = archiveList.get(0);
            // 排除自己创建的团队档案库：创建者已在「我创建的档案库」里看到，这里再列一次会重复
            if (Objects.equals(archive.getUserId(), loginUser.getId())) {
                return null;
            }
            ArchiveUserVO archiveUserVO = ArchiveUserVO.objToVo(archiveUser);
            archiveUserVO.setUser(userService.getUserVO(loginUser));
            archiveUserVO.setArchive(ArchiveVO.entityToVo(archive));
            return archiveUserVO;
        }).filter(Objects::nonNull).collect(Collectors.toList());
    }

    @Override
    public void validArchiveUser(ArchiveUser archiveUser, boolean add) {
        ThrowUtils.throwIf(archiveUser == null, ErrorCode.PARAMS_ERROR);
        Long archiveId = archiveUser.getArchiveId();
        Long userId = archiveUser.getUserId();
        String archiveRole = archiveUser.getArchiveRole();
        if (add) {
            ThrowUtils.throwIf(archiveId == null || archiveId <= 0, ErrorCode.PARAMS_ERROR, "档案库 id 不能为空");
            ThrowUtils.throwIf(userId == null || userId <= 0, ErrorCode.PARAMS_ERROR, "用户 id 不能为空");
            ThrowUtils.throwIf(StrUtil.isBlank(archiveRole), ErrorCode.PARAMS_ERROR, "档案库角色不能为空");
        }
        if (ObjUtil.isNotEmpty(archiveId)) {
            Archive archive = archiveMapper.selectById(archiveId);
            ThrowUtils.throwIf(archive == null, ErrorCode.NOT_FOUND_ERROR, "档案库不存在");
        }
        if (ObjUtil.isNotEmpty(userId)) {
            User user = userService.getById(userId);
            ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        }
        if (StrUtil.isNotBlank(archiveRole)) {
            ArchiveRoleEnum archiveRoleEnum = ArchiveRoleEnum.getEnumByValue(archiveRole);
            ThrowUtils.throwIf(archiveRoleEnum == null, ErrorCode.PARAMS_ERROR, "档案库角色不存在");
        }
    }

    @Override
    public QueryWrapper<ArchiveUser> getQueryWrapper(ArchiveUserQueryRequest archiveUserQueryRequest) {
        QueryWrapper<ArchiveUser> queryWrapper = new QueryWrapper<>();
        if (archiveUserQueryRequest == null) {
            return queryWrapper;
        }
        queryWrapper.eq(ObjUtil.isNotEmpty(archiveUserQueryRequest.getId()), "id", archiveUserQueryRequest.getId());
        queryWrapper.eq(ObjUtil.isNotEmpty(archiveUserQueryRequest.getArchiveId()), "archiveId", archiveUserQueryRequest.getArchiveId());
        queryWrapper.eq(ObjUtil.isNotEmpty(archiveUserQueryRequest.getUserId()), "userId", archiveUserQueryRequest.getUserId());
        queryWrapper.eq(StrUtil.isNotBlank(archiveUserQueryRequest.getArchiveRole()), "archiveRole", archiveUserQueryRequest.getArchiveRole());
        return queryWrapper;
    }

    @Override
    public ArchiveUserVO getArchiveUserVO(ArchiveUser archiveUser) {
        ArchiveUserVO archiveUserVO = ArchiveUserVO.objToVo(archiveUser);
        if (archiveUserVO == null) {
            return null;
        }
        Long userId = archiveUser.getUserId();
        if (userId != null && userId > 0) {
            archiveUserVO.setUser(userService.getUserVO(userService.getById(userId)));
        }
        Long archiveId = archiveUser.getArchiveId();
        if (archiveId != null && archiveId > 0) {
            Archive archive = archiveMapper.selectById(archiveId);
            archiveUserVO.setArchive(ArchiveVO.entityToVo(archive));
        }
        return archiveUserVO;
    }

    @Override
    public ArchiveUser getArchiveUser(Long archiveId, Long userId) {
        if (archiveId == null || userId == null) {
            return null;
        }
        return this.getOne(new QueryWrapper<ArchiveUser>()
                .eq("archiveId", archiveId)
                .eq("userId", userId));
    }

    /**
     * 是否为档案库成员管理者：档案库创建者、团队管理员或系统管理员。
     * <p>
     * 这里直接查 archive_user 表判断角色，而不是用 {@code StpUtil.hasPermission} 拼权限码：
     * 权限码由 {@code StpInterfaceImpl} 依据请求上下文动态计算，在 service 内部调用时
     * 拿不到可靠的 archiveId 来源，容易算出空权限把正常操作误判成越权。
     * 入口处的角色权限拦截交给 Controller 上的 {@code @SaArchiveCheckPermission}。
     */
    private boolean canManageMember(Archive archive, User loginUser) {
        if (Objects.equals(archive.getUserId(), loginUser.getId())) {
            return true;
        }
        if (userService.isAdmin(loginUser)) {
            return true;
        }
        // 团队管理员可管理成员
        ArchiveUser archiveUser = getArchiveUser(archive.getId(), loginUser.getId());
        return archiveUser != null && ArchiveRoleEnum.ADMIN.getValue().equals(archiveUser.getArchiveRole());
    }
}
