package com.traceproof.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.ObjUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.traceproof.common.ArchivePermissionUtils;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.mapper.ArchiveMapper;
import com.traceproof.manager.sharding.DynamicShardingManager;
import com.traceproof.model.dto.archive.ArchiveAddRequest;
import com.traceproof.model.dto.archive.ArchiveDeleteRequest;
import com.traceproof.model.dto.archive.ArchiveQueryRequest;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.ArchiveUser;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.ArchiveLevelEnum;
import com.traceproof.model.enums.ArchiveRoleEnum;
import com.traceproof.model.enums.ArchiveTypeEnum;
import com.traceproof.model.vo.ArchiveVO;
import com.traceproof.service.EvidenceService;
import com.traceproof.service.ArchiveService;
import com.traceproof.service.ArchiveUserService;
import com.traceproof.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.CollectionUtils;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;


/**
 * @author lsj
 * @description 针对表【archive(档案库)】的数据库操作Service实现
 * @createDate 2026-10-13 23:36:58
 */
@Service
@Slf4j
public class ArchiveServiceImpl extends ServiceImpl<ArchiveMapper, Archive>
        implements ArchiveService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "archiveName", "archiveLevel", "archiveType", "maxSize", "maxCount",
            "totalSize", "totalCount", "userId", "createTime", "editTime", "updateTime");

    private static final Set<String> SORT_ORDERS = Set.of("ascend", "descend");

    private final UserService userService;

    private final ArchiveUserService archiveUserService;

    private final TransactionTemplate transactionTemplate;

    /**
     * 删除档案库时要连带清理档案库下的影像，所以依赖影像服务。
     * <p>
     * 影像服务为了做档案库权限校验又反过来依赖档案库服务，两个 Bean 互相依赖，
     * 这里必须用 @Lazy 打断（Spring Boot 2.6 起默认禁止循环依赖，不加会启动失败）。
     */
    @Autowired
    @Lazy
    private EvidenceService evidenceService;

    /**
     * 团队档案库创建后要建对应的 evidence_{archiveId} 分表。
     * <p>
     * DynamicShardingManager 反过来依赖 ArchiveService 查团队档案库列表，构成循环依赖，
     * 所以同样用 @Lazy 注入代理对象。
     */
    @Autowired
    @Lazy
    private DynamicShardingManager dynamicShardingManager;

    /**
     * Redisson 客户端：用分布式锁替代本地 synchronized，保证多实例下档案库创建去重依然有效
     */
    @Resource
    private RedissonClient redissonClient;

    public ArchiveServiceImpl(UserService userService, ArchiveUserService archiveUserService,
                            TransactionTemplate transactionTemplate) {
        this.userService = userService;
        this.archiveUserService = archiveUserService;
        this.transactionTemplate = transactionTemplate;
    }

    @Override
    public void validArchive(Archive archive, boolean add) {
        ThrowUtils.throwIf(archive == null, ErrorCode.PARAMS_ERROR);
        // 从对象中取值
        String archiveName = archive.getArchiveName();
        Integer archiveLevel = archive.getArchiveLevel();
        Integer archiveType = archive.getArchiveType();
        ArchiveLevelEnum archiveLevelEnum = ArchiveLevelEnum.getEnumByValue(archiveLevel);
        // 要创建
        if (add) {
            if (StrUtil.isBlank(archiveName)) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "档案库名称不能为空");
            }
            if (archiveLevel == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "档案库级别不能为空");
            }
            if (archiveType == null) {
                throw new BusinessException(ErrorCode.PARAMS_ERROR, "档案库类型不能为空");
            }
        }
        // 修改数据时，如果要改档案库级别
        if (archiveLevel != null && archiveLevelEnum == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "档案库级别不存在");
        }
        // 档案库类型必须是合法枚举值
        if (archiveType != null && ArchiveTypeEnum.getEnumByValue(archiveType) == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "档案库类型不存在");
        }
        if (StrUtil.isNotBlank(archiveName) && archiveName.length() > 30) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "档案库名称过长");
        }
    }


    @Override
    public void fillArchiveByArchiveLevel(Archive archive) {
        // 根据档案库级别，自动填充限额
        //如果archive自带的话就用自带的
        ArchiveLevelEnum archiveLevelEnum = ArchiveLevelEnum.getEnumByValue(archive.getArchiveLevel());
        if (archiveLevelEnum != null) {
            long maxSize = archiveLevelEnum.getMaxSize();
            if (archive.getMaxSize() == null) {
                archive.setMaxSize(maxSize);
            }
            long maxCount = archiveLevelEnum.getMaxCount();
            if (archive.getMaxCount() == null) {
                archive.setMaxCount(maxCount);
            }
        }
    }


    @Override
    public long addArchive(ArchiveAddRequest archiveAddRequest, User loginUser, HttpServletRequest request) {
        ThrowUtils.throwIf(archiveAddRequest == null, ErrorCode.PARAMS_ERROR);
        ThrowUtils.throwIf(loginUser == null || loginUser.getId() == null, ErrorCode.NO_AUTH_ERROR);
        Archive archive = new Archive();
        BeanUtils.copyProperties(archiveAddRequest, archive);
        // 不传类型时默认为私有档案库，保持旧前端调用不变
        if (archive.getArchiveType() == null) {
            archive.setArchiveType(ArchiveTypeEnum.PRIVATE.getValue());
        }
        // 档案库归创建者所有，userId 必须在这里落上，否则数据库的 userId 非空约束会直接插入失败
        archive.setUserId(loginUser.getId());
        validArchive(archive, true);
        fillArchiveByArchiveLevel(archive);
        // 普通用户只能创建普通版档案库：专业版、旗舰版必须由管理员创建
        ArchiveLevelEnum archiveLevelEnum = ArchiveLevelEnum.getEnumByValue(archive.getArchiveLevel());
        if (!userService.isAdmin(loginUser) && !ArchiveLevelEnum.COMMON.equals(archiveLevelEnum)) {
            throw new BusinessException(ErrorCode.FORBIDDEN_ERROR, "非管理员只能创建普通档案库");
        }
        // 同一用户串行创建，避免并发提交绕过去重校验。
        // 用 Redisson 分布式锁替代本地 synchronized：本地锁只能锁住单机，
        // 分布式锁才能保证「一个账号每种类型只能建一个档案库」在集群部署下依然成立。
        RLock lock = redissonClient.getLock("archive:add:" + loginUser.getId());
        boolean locked = false;
        try {
            // 最多等 3 秒拿锁，锁最多持 30 秒（防死锁自动释放）
            locked = lock.tryLock(3, 30, TimeUnit.SECONDS);
            ThrowUtils.throwIf(!locked, ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
            Long newArchiveId = transactionTemplate.execute(status -> {
                // 每个用户每种类型的档案库只能创建一个：一个私有档案库 + 一个团队档案库
                ArchiveQueryRequest archiveQueryRequest = new ArchiveQueryRequest();
                archiveQueryRequest.setUserId(loginUser.getId());
                archiveQueryRequest.setArchiveType(archive.getArchiveType());
                long count = this.count(getQueryWrapper(archiveQueryRequest));
                boolean isTeam = ArchiveTypeEnum.TEAM.getValue() == archive.getArchiveType();
                ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR,
                        isTeam ? "每个用户只能创建一个团队档案库" : "每个用户只能创建一个私有档案库");
                ThrowUtils.throwIf(!save(archive), ErrorCode.SYSTEM_ERROR, "数据库插入失败");
                // 团队档案库：把创建者写入成员表并设为管理员，后续邀请的成员才能基于角色鉴权。
                // 与档案库创建在同一事务中，任何一步失败都会整体回滚
                if (isTeam) {
                    ArchiveUser archiveUser = new ArchiveUser();
                    archiveUser.setArchiveId(archive.getId());
                    archiveUser.setUserId(loginUser.getId());
                    archiveUser.setArchiveRole(ArchiveRoleEnum.ADMIN.getValue());
                    ThrowUtils.throwIf(!archiveUserService.save(archiveUser),
                            ErrorCode.SYSTEM_ERROR, "创建团队成员记录失败");
                }
                return archive.getId();
            });
            long archiveId = Optional.ofNullable(newArchiveId).orElse(-1L);
            // 团队档案库创建独立分表 evidence_{archiveId}。
            // 必须放在事务外面：MySQL 的 DDL 会隐式提交当前事务，
            // 放进 transactionTemplate 里会让整个创建流程失去回滚能力。
            if (archiveId > 0) {
                dynamicShardingManager.createArchiveEvidenceTable(archive);
            }
            return archiveId;
        } catch (InterruptedException e) {
            // tryLock 被打断：恢复中断标记，按「系统繁忙」处理
            Thread.currentThread().interrupt();
            throw new BusinessException(ErrorCode.SYSTEM_ERROR, "系统繁忙，请稍后重试");
        } finally {
            // 只有拿到锁才释放，避免释放别人的锁
            if (locked) {
                lock.unlock();
            }
        }
    }


    @Override
    public ArchiveVO getArchiveVO(Archive archive, HttpServletRequest request) {
        // 实体转封装类统一走 VO 的工厂方法
        ArchiveVO archiveVO = ArchiveVO.entityToVo(archive);
        if (archiveVO == null) {
            return null;
        }
        // 关联查询创建人信息，前端列表要展示档案库归属
        Long userId = archive.getUserId();
        if (userId != null && userId > 0) {
            User user = userService.getById(userId);
            archiveVO.setUser(userService.getUserVO(user));
        }
        return archiveVO;
    }

    @Override
    public Page<ArchiveVO> getArchiveVOPage(Page<Archive> archivePage, HttpServletRequest request) {
        ThrowUtils.throwIf(archivePage == null, ErrorCode.PARAMS_ERROR);
        List<Archive> archiveList = archivePage.getRecords();
        Page<ArchiveVO> archiveVOPage = new Page<>(archivePage.getCurrent(), archivePage.getSize(), archivePage.getTotal());
        if (CollUtil.isEmpty(archiveList)) {
            return archiveVOPage;
        }
        // 实体列表 => 封装对象列表
        List<ArchiveVO> archiveVOList = archiveList.stream().map(ArchiveVO::entityToVo).collect(Collectors.toList());
        // 1. 批量查询创建人信息，避免逐条查询造成 N+1
        Set<Long> userIdSet = archiveList.stream().map(Archive::getUserId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, List<User>> userIdUserListMap = userIdSet.isEmpty() ? Collections.emptyMap()
                : userService.listByIds(userIdSet).stream().collect(Collectors.groupingBy(User::getId));
        // 2. 填充信息
        archiveVOList.forEach(archiveVO -> {
            List<User> userList = userIdUserListMap.get(archiveVO.getUserId());
            archiveVO.setUser(userService.getUserVO(CollUtil.isEmpty(userList) ? null : userList.get(0)));
        });
        archiveVOPage.setRecords(archiveVOList);
        return archiveVOPage;
    }

    @Override
    public QueryWrapper<Archive> getQueryWrapper(ArchiveQueryRequest archiveQueryRequest) {

        QueryWrapper<Archive> queryWrapper = new QueryWrapper<>();
        if (archiveQueryRequest == null) {
            return queryWrapper;
        }
        // 从对象中取值
        Long id = archiveQueryRequest.getId();
        Long userId = archiveQueryRequest.getUserId();
        String archiveName = archiveQueryRequest.getArchiveName();
        Integer archiveLevel = archiveQueryRequest.getArchiveLevel();
        Integer archiveType = archiveQueryRequest.getArchiveType();
        String sortField = archiveQueryRequest.getSortField();
        String sortOrder = archiveQueryRequest.getSortOrder();

        ThrowUtils.throwIf(StrUtil.isNotBlank(sortField) && !SORT_FIELDS.contains(sortField),
                ErrorCode.PARAMS_ERROR, "排序字段不合法");
        ThrowUtils.throwIf(StrUtil.isNotBlank(sortOrder) && !SORT_ORDERS.contains(sortOrder),
                ErrorCode.PARAMS_ERROR, "排序方向不合法");

        queryWrapper.eq(ObjUtil.isNotEmpty(id), "id", id);
        queryWrapper.eq(ObjUtil.isNotEmpty(userId), "userId", userId);
        // 档案库名用精确匹配：创建档案库时的重名校验也走这里，模糊匹配会把「档案库a」和「档案库ab」判成重复
        queryWrapper.eq(StrUtil.isNotBlank(archiveName), "archiveName", archiveName);
        queryWrapper.eq(ObjUtil.isNotEmpty(archiveLevel), "archiveLevel", archiveLevel);
        queryWrapper.eq(ObjUtil.isNotEmpty(archiveType), "archiveType", archiveType);
        // 排序：常量写在前面，sortOrder 为 null 时不会抛 NPE
        queryWrapper.orderBy(StrUtil.isNotBlank(sortField), "ascend".equals(sortOrder), sortField);

        return queryWrapper;
    }

    @Override
    public boolean deleteArchive(ArchiveDeleteRequest archiveDeleteRequest, User loginUser) {
        ThrowUtils.throwIf(archiveDeleteRequest == null || archiveDeleteRequest.getId() == null,
                ErrorCode.PARAMS_ERROR, "档案库 id 不能为空");
        final Long archiveId = archiveDeleteRequest.getId();
        // 权限以库里的数据为准：请求体里的 userId 是前端传的，不能拿它判断归属
        Archive archive = this.getById(archiveId);
        ArchivePermissionUtils.checkArchiveAuth(archive, loginUser, userService);
        transactionTemplate.execute((status -> {
            // 删除档案库
            ThrowUtils.throwIf(!removeById(archiveId), ErrorCode.OPERATION_ERROR, "删除失败");
            // 团队档案库的成员记录一并清理，避免成员表里残留指向已删档案库的脏数据
            archiveUserService.remove(new QueryWrapper<ArchiveUser>().eq("archiveId", archiveId));
            // 删除相关影像：先删库，再清对象存储
            List<Evidence> evidences = evidenceService.getListEvidenceByArchiveId(archive);
            if (!CollectionUtils.isEmpty(evidences)) {
                evidenceService.deleteBatchIds(evidences.stream().map(Evidence::getId).collect(Collectors.toList()));
                evidences.forEach(evidenceService::clearEvidenceFile);
            }
            return null;
        }));
        return true;
    }

    @Override
    public void updateArchiveUsage(Long archiveId, long countDelta, long sizeDelta) {
        if (archiveId == null || (countDelta == 0 && sizeDelta == 0)) {
            return;
        }
        // 用 SQL 自增/自减，避免「先查询再更新」在并发上传时相互覆盖。
        // 这里用 LambdaUpdateWrapper 而不是 lambdaUpdate()：链式 wrapper 的 getSqlFirst 会抛异常，
        // MyBatis-Plus 3.5.9 拼 SQL 时必须能取到它。
        LambdaUpdateWrapper<Archive> updateWrapper = Wrappers.lambdaUpdate(Archive.class)
                .eq(Archive::getId, archiveId)
                .setSql(countDelta != 0, "totalCount = totalCount + (" + countDelta + ")")
                .setSql(sizeDelta != 0, "totalSize = totalSize + (" + sizeDelta + ")");
        boolean updated = this.update(updateWrapper);
        if (!updated) {
            // 档案库可能已被删除，属于可接受情况，不影响影像本身的增删
            log.warn("档案库容量更新失败，档案库可能已被删除，archiveId = {}", archiveId);
        }
    }


}
