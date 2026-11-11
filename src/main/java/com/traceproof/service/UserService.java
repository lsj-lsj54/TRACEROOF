package com.traceproof.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.IService;
import com.traceproof.model.dto.user.UserQueryRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.LoginUserVO;
import com.traceproof.model.vo.UserVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @author lsj
 * @description 针对表【user(用户)】的数据库操作Service
 * @createDate 2026-10-11 16:45:54
 */
public interface UserService extends IService<User> {
    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @param userName      用户昵称，可为空
     * @return 新用户 id
     */
    long userRegister(String userAccount, String userPassword, String checkPassword, String userName);

    /**
     * 修改当前登录用户的昵称
     * <p>
     * 与注册共用同一套昵称校验规则：不能为空、不能超过最大长度。
     *
     * @param userId   当前登录用户 id
     * @param userName 新昵称
     * @return 是否修改成功
     */
    boolean updateUserName(long userId, String userName);

    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request
     * @return 脱敏后的用户信息
     */
    LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request);

    /**
     * 获取当前登录用户
     *
     * @param request request
     * @return 当前登录用户
     */
    User getLoginUser(HttpServletRequest request);

    /**
     * 获取当前登录用户，未登录时返回 null（不抛异常）
     * <p>
     * 用于「登录可见全部、未登录只能看公开内容」的接口，
     * 例如查看公共档案库的影像详情时未登录也应放行。
     *
     * @param request request
     * @return 当前登录用户，未登录或用户已被删除时返回 null
     */
    User getLoginUserOrNull(HttpServletRequest request);

    /**
     * 用户注销
     *
     * @param request request
     * @return 注销结果
     */
    boolean userLogout(HttpServletRequest request);

    /**
     * 获取用户脱敏信息
     *
     * @param user 脱敏前的信息
     * @return 脱敏后的信息
     */
    UserVO getUserVO(User user);

    /**
     * 批量获取用户脱敏信息
     *
     * @param userList 脱敏前的信息
     * @return 脱敏后的 List 列表
     */
    List<UserVO> getUserVOList(List<User> userList);

    /**
     * 获取查询条件
     *
     * @param userQueryRequest 查询条件
     * @return 查询条件
     */
    QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest);

    /**
     * 给密码加盐
     * @param defaultPassword
     * @return 加密后的密码
     */
    String getEncryptPassword(String defaultPassword);

    /**
     * 是否为管理员
     *
     * @param user 当前登录的 user
     * @return  是否是管理员 true 表示 是 、false 表示 否
     */
    boolean isAdmin(User user);

    /**
     * 按昵称或账号模糊搜索用户（脱敏返回）
     * <p>
     * 供邀请团队成员时按昵称/账号匹配候选使用，仅登录用户可调用。
     * 不返回密码等敏感字段，最多返回 limit 条。
     *
     * @param keyword 搜索关键字（匹配昵称或账号）
     * @param limit   返回条数上限
     * @return 脱敏用户列表
     */
    List<UserVO> searchUserVO(String keyword, int limit);

}
