package com.traceproof.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.traceproof.model.dto.archiveuser.ArchiveUserAddRequest;
import com.traceproof.model.dto.archiveuser.ArchiveUserEditRequest;
import com.traceproof.model.dto.archiveuser.ArchiveUserQueryRequest;
import com.traceproof.model.entity.ArchiveUser;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.ArchiveUserVO;

import java.util.List;

/**
 * @author lsj
 * @description 针对表【archive_user(档案库用户关联)】的数据库操作Service
 * @createDate 2026-10-22
 */
public interface ArchiveUserService extends IService<ArchiveUser> {

    /**
     * 邀请用户加入团队档案库
     * <p>
     * 仅团队档案库支持邀请；仅档案库创建者、团队管理员或系统管理员可操作。
     *
     * @param archiveUserAddRequest 邀请请求（archiveId / userId / archiveRole）
     * @param loginUser           登录用户
     * @return 新成员记录 id
     */
    long addArchiveUser(ArchiveUserAddRequest archiveUserAddRequest, User loginUser);

    /**
     * 移除团队成员
     * <p>
     * 档案库创建者、团队管理员、系统管理员可移除他人；成员也可移除自己（退出团队）。
     * 档案库创建者本人的记录不允许移除。
     *
     * @param id        成员记录 id
     * @param loginUser 登录用户
     * @return 是否成功
     */
    boolean deleteArchiveUser(long id, User loginUser);

    /**
     * 修改团队成员角色
     * <p>
     * 仅档案库创建者或系统管理员可调整；创建者本人的角色不可修改。
     *
     * @param archiveUserEditRequest 编辑请求（id / archiveRole）
     * @param loginUser            登录用户
     * @return 是否成功
     */
    boolean editArchiveUser(ArchiveUserEditRequest archiveUserEditRequest, User loginUser);

    /**
     * 查询档案库成员列表（封装类，含用户与档案库信息）
     * <p>
     * 仅该档案库的成员、创建者或系统管理员可见。
     *
     * @param archiveUserQueryRequest 查询请求（archiveId 必填）
     * @param loginUser             登录用户
     * @return 成员封装列表
     */
    List<ArchiveUserVO> listArchiveUserVO(ArchiveUserQueryRequest archiveUserQueryRequest, User loginUser);

    /**
     * 查询当前用户加入的团队档案库列表（封装类，含档案库信息）
     *
     * @param loginUser 登录用户
     * @return 我加入的团队档案库列表
     */
    List<ArchiveUserVO> listMyTeamArchive(User loginUser);

    /**
     * 校验档案库成员对象
     *
     * @param archiveUser 档案库成员
     * @param add       是否为创建时校验
     */
    void validArchiveUser(ArchiveUser archiveUser, boolean add);

    /**
     * 获取查询对象
     *
     * @param archiveUserQueryRequest 查询请求
     * @return QueryWrapper
     */
    QueryWrapper<ArchiveUser> getQueryWrapper(ArchiveUserQueryRequest archiveUserQueryRequest);

    /**
     * 单条记录转封装类（填充用户与档案库信息）
     *
     * @param archiveUser 档案库成员
     * @return 封装类
     */
    ArchiveUserVO getArchiveUserVO(ArchiveUser archiveUser);

    /**
     * 查询某用户在某档案库中的成员记录
     *
     * @param archiveId 档案库 id
     * @param userId  用户 id
     * @return 成员记录，不存在返回 null
     */
    ArchiveUser getArchiveUser(Long archiveId, Long userId);
}
