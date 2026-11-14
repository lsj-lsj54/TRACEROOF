package com.traceproof.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import cn.hutool.core.util.RandomUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.auth.StpKit;
import com.traceproof.model.constant.UserConstant;
import com.traceproof.model.dto.user.UserQueryRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.enums.UserRoleEnum;
import com.traceproof.model.vo.LoginUserVO;
import com.traceproof.service.UserService;
import com.traceproof.mapper.UserMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;
import org.springframework.util.DigestUtils;
import com.traceproof.model.vo.UserVO;


import javax.servlet.http.HttpServletRequest;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import static com.traceproof.model.constant.UserConstant.USER_LOGIN_STATE;

/**
 * @author lsj
 * @description 针对表【user(用户)】的数据库操作Service实现
 * @createDate 2026-10-11 16:45:54
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User>
        implements UserService {

    private static final Set<String> SORT_FIELDS = Set.of(
            "id", "userAccount", "userName", "userAvatar", "userProfile", "userRole",
            "createTime", "editTime", "updateTime");

    private static final Set<String> SORT_ORDERS = Set.of("ascend", "descend");

    /**
     * 用户注册
     *
     * @param userAccount   用户账户
     * @param userPassword  用户密码
     * @param checkPassword 校验密码
     * @param userName      用户昵称，为空时自动生成
     * @return 新用户 id
     */
    @Override
    public long userRegister(String userAccount, String userPassword, String checkPassword, String userName) {
        // 1. 校验
        ThrowUtils.throwIf(StrUtil.hasBlank(userAccount, userPassword, checkPassword), ErrorCode.PARAMS_ERROR, "参数为空");
        ThrowUtils.throwIf(userAccount.length() < 2, ErrorCode.PARAMS_ERROR, "用户账号过短");
        // 与登录共用同一个最小长度常量，避免两处阈值不一致导致「能注册不能登录」
        if (userPassword.length() < UserConstant.MIN_PASSWORD_LENGTH
                || checkPassword.length() < UserConstant.MIN_PASSWORD_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "用户密码过短，至少 " + UserConstant.MIN_PASSWORD_LENGTH + " 位");
        }
        ThrowUtils.throwIf(!userPassword.equals(checkPassword), ErrorCode.PARAMS_ERROR, "两次输入的密码不一致");
        // 2. 检查是否重复
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        long count = this.baseMapper.selectCount(queryWrapper);
        ThrowUtils.throwIf(count > 0, ErrorCode.PARAMS_ERROR, "账号重复");
        // 3. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 4. 插入数据
        User user = new User();
        user.setUserAccount(userAccount);
        user.setUserPassword(encryptPassword);
        // 昵称由用户自己填，没填才兜底生成，不能写死成同一个名字
        user.setUserName(resolveUserName(userName));
        user.setUserRole(UserRoleEnum.USER.getValue());
        boolean saveResult = this.save(user);
        ThrowUtils.throwIf(!saveResult, ErrorCode.SYSTEM_ERROR, "注册失败，数据库错误");
        return user.getId();
    }

    /**
     * 规整注册时提交的昵称
     *
     * @param userName 用户填写的昵称，可为空
     * @return 最终写入数据库的昵称
     */
    private String resolveUserName(String userName) {
        // 留空兜底：用户 + 6 位随机数，保证每个新用户都有可区分的昵称
        if (StrUtil.isBlank(userName)) {
            return UserConstant.DEFAULT_USER_NAME_PREFIX + RandomUtil.randomNumbers(6);
        }
        return checkUserName(userName);
    }

    /**
     * 校验昵称是否合法，并返回去除首尾空白后的结果
     *
     * @param userName 待校验的昵称
     * @return 规整后的昵称
     */
    private String checkUserName(String userName) {
        String finalUserName = userName == null ? "" : userName.trim();
        if (StrUtil.isBlank(finalUserName)) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "昵称不能为空");
        }
        if (finalUserName.length() > UserConstant.MAX_USER_NAME_LENGTH) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR,
                    "昵称过长，最多 " + UserConstant.MAX_USER_NAME_LENGTH + " 个字符");
        }
        return finalUserName;
    }

    /**
     * 修改当前登录用户的昵称
     *
     * @param userId   当前登录用户 id
     * @param userName 新昵称
     * @return 是否修改成功
     */
    @Override
    public boolean updateUserName(long userId, String userName) {
        if (userId <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 只设置 id 和 userName，MyBatis-Plus 只更新非 null 字段，
        // 不会把密码、角色、头像等字段一起覆盖掉
        User user = new User();
        user.setId(userId);
        user.setUserName(checkUserName(userName));
        boolean result = this.updateById(user);
        if (!result) {
            throw new BusinessException(ErrorCode.OPERATION_ERROR, "昵称修改失败");
        }
        // 同步刷新会话里的 User 快照，否则 getLoginUser 改为快照优先后会一直返回旧昵称
        try {
            StpKit.ARCHIVE.getSession().set(USER_LOGIN_STATE, this.getById(userId));
        } catch (Exception ignored) {
            // 无请求上下文（如单元测试 / 异步任务）时忽略，快照会在下次登录时刷新
        }
        return true;
    }

    /**
     * 给密码加盐并加密
     *
     * @param userPassword 加密前的明文密码
     * @return 加密后的密码
     */
    @Override
    public String getEncryptPassword(String userPassword) {
        // 盐值，混淆密码
        final String SALT = "lsj";
        // 显式指定 UTF-8：getBytes() 用平台默认字符集，本地（GBK）与服务器（UTF-8）
        // 加密结果会不一致，表现为「本地注册的账号在服务器上登录不了」，
        // 密码里含中文等非 ASCII 字符时必现。
        return DigestUtils.md5DigestAsHex((SALT + userPassword).getBytes(StandardCharsets.UTF_8));
    }


    /**
     * 用户登录
     *
     * @param userAccount  用户账户
     * @param userPassword 用户密码
     * @param request      request 请求
     * @return 脱敏后的用户信息
     */
    @Override
    public LoginUserVO userLogin(String userAccount, String userPassword, HttpServletRequest request) {
        // 1. 校验
        ThrowUtils.throwIf(StrUtil.hasBlank(userAccount, userPassword), ErrorCode.PARAMS_ERROR, "参数为空");
        ThrowUtils.throwIf(userAccount.length() < 2, ErrorCode.PARAMS_ERROR, "账号格式错误");

        // 2. 加密
        String encryptPassword = getEncryptPassword(userPassword);
        // 查询用户是否存在
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("userAccount", userAccount);
        queryWrapper.eq("userPassword", encryptPassword);
        User user = this.baseMapper.selectOne(queryWrapper);
        // 用户不存在
        if (user == null) {
            log.info("user login failed, userAccount cannot match userPassword");
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "用户不存在或密码错误");
        }
        // 3. 记录登录态：全程只用 archive 这一套 Sa-Token 账号体系
        //    - login 写 token（Cookie + Redis），后续 getLoginUser 都从这套会话里取
        //    - 同时把 User 放进会话，供 StpInterfaceImpl 计算档案库权限时直接取用，避免每次再查库
        try {
            StpKit.ARCHIVE.login(user.getId());
            //todo这里的USER_LOGIN_STATE字面意思是状态，而user其实是包括不止状态的，后续改名
            StpKit.ARCHIVE.getSession().set(USER_LOGIN_STATE, user);
        } catch (org.springframework.dao.DataAccessException e) {
            // Redis 不可用（连接失败/超时）时 Sa-Token 无法写入会话，
            // 这里不把异常吞成「系统错误」，而是明确告知是服务不可用，方便排查与重试。
            log.error("登录失败：Redis 不可用，无法写入登录态", e);
            throw new BusinessException(ErrorCode.SERVICE_UNAVAILABLE, "登录服务暂不可用，请稍后重试");
        }
        return LoginUserVO.entityToVo(user);
    }

    /**
     * 获取当前登录用户
     *
     * @param request request 请求
     * @return 当前登录用户
     */
    @Override
    public User getLoginUser(HttpServletRequest request) {
        // 登录态由 archive 这套 Sa-Token 会话管理：未登录会抛 NotLoginException，
        // 由全局异常处理器统一转成 NOT_LOGIN_ERROR，前端无需感知实现差异。
        long userId = StpKit.ARCHIVE.getLoginIdAsLong();
        // 优先读会话里的 User 快照（登录时已写入），避免每次查库；
        // 会话里没有（旧 token / 快照被清理）再退化查库
        User currentUser = (User) StpKit.ARCHIVE.getSession().get(USER_LOGIN_STATE);
        if (currentUser == null) {
            currentUser = this.getById(userId);
        }
        ThrowUtils.throwIf(currentUser == null, ErrorCode.NOT_LOGIN_ERROR, "未登录");
        return currentUser;
    }

    /**
     * 获取当前登录用户，未登录时返回 null（不抛异常）
     *
     * @param request request 请求
     * @return 当前登录用户，未登录或用户已被删除时返回 null
     */
    //todo这个函数的参数是没有用的，后续为了代码整洁可以删除
    @Override
    public User getLoginUserOrNull(HttpServletRequest request) {
        // 未登录时返回 null 而不抛异常，供「登录可见全部、未登录只看公开内容」的接口使用
        try {
            long userId = StpKit.ARCHIVE.getLoginIdAsLong();
            User currentUser = (User) StpKit.ARCHIVE.getSession().get(USER_LOGIN_STATE);
            if (currentUser == null) {
                //todo这里应该是直接返回return null吧因为satoken在登录时就把user完整保存进去了,id就是在user里取出来的，如果user空了，那id也肯定为空，没必要再查了
                //currentUser = this.getById(userId);
                return null;
            }
            return currentUser;
        } catch (Exception e) {
            // 未登录或 token 失效都返回 null
            return null;
        }
    }

    /**
     * 用户注销
     *
     * @param request request 请求
     * @return 注销结果
     */
    @Override
    public boolean userLogout(HttpServletRequest request) {
        // 未登录时 logout 不会抛异常，直接返回 true 即可；
        // 两套会话都清一次，避免历史 token 残留（旧版本曾用默认体系登录）
        StpKit.ARCHIVE.logout();
        StpUtil.logout();
        return true;
    }

    /**
     * 获取用户脱敏信息
     *
     * @param user 脱敏前的信息
     * @return 脱敏后的信息
     */
    @Override
    public UserVO getUserVO(User user) {
        // 实体转封装类统一走 VO 自己的工厂方法，避免各处重复写 BeanUtils
        return UserVO.entityToVo(user);
    }

    /**
     * 批量获取用户脱敏信息
     *
     * @param userList 脱敏前的信息
     * @return 脱敏后的 List 列表
     */
    @Override
    public List<UserVO> getUserVOList(List<User> userList) {
        if (CollectionUtils.isEmpty(userList)) {
            return new ArrayList<>();
        }
        return userList.stream().map(this::getUserVO).collect(Collectors.toList());
    }

    /**
     * 获取查询条件
     *
     * @param userQueryRequest 查询条件
     * @return 可用来查询的 queryWrapper
     */
    @Override
    public QueryWrapper<User> getQueryWrapper(UserQueryRequest userQueryRequest) {
        if (userQueryRequest == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR, "请求参数为空");
        }
        Long id = userQueryRequest.getId();
        String userAccount = userQueryRequest.getUserAccount();
        String userName = userQueryRequest.getUserName();
        String userProfile = userQueryRequest.getUserProfile();
        String userRole = userQueryRequest.getUserRole();
        String sortField = userQueryRequest.getSortField();
        String sortOrder = userQueryRequest.getSortOrder();
        ThrowUtils.throwIf(StrUtil.isNotBlank(sortField) && !SORT_FIELDS.contains(sortField),
                ErrorCode.PARAMS_ERROR, "排序字段不合法");
        ThrowUtils.throwIf(StrUtil.isNotBlank(sortOrder) && !SORT_ORDERS.contains(sortOrder),
                ErrorCode.PARAMS_ERROR, "排序方向不合法");
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq(Objects.nonNull(id), "id", id);
        queryWrapper.eq(StrUtil.isNotBlank(userRole), "userRole", userRole);
        queryWrapper.like(StrUtil.isNotBlank(userAccount), "userAccount", userAccount);
        queryWrapper.like(StrUtil.isNotBlank(userName), "userName", userName);
        queryWrapper.like(StrUtil.isNotBlank(userProfile), "userProfile", userProfile);
        // 常量写在前面：sortOrder 为 null 时不会抛 NPE
        // （写成 sortOrder.equals("ascend") 会在 isNotBlank 拦下之前就抛异常）
        queryWrapper.orderBy(StrUtil.isNotBlank(sortField), "ascend".equals(sortOrder), sortField);
        return queryWrapper;
    }

    /**
     * 是否为管理员
     *
     * @param user 当前登录的 user
     * @return 是否是管理员 true 表示 是 、false 表示 否
     */
    @Override
    public boolean isAdmin(User user) {
        return user != null && UserRoleEnum.ADMIN.getValue().equals(user.getUserRole());
    }

    /**
     * 按照关键词搜索用户
     *
     * @param keyword 搜索关键字（匹配昵称或账号）
     * @param limit   返回条数上限
     * @return
     */
    @Override
    public List<UserVO> searchUserVO(String keyword, int limit) {
        if (StrUtil.isBlank(keyword)) {
            return Collections.emptyList();
        }
        int safeLimit = Math.max(1, Math.min(limit, 20));
        QueryWrapper<User> queryWrapper = new QueryWrapper<>();
        // 昵称或账号任一命中即可
        queryWrapper.and(w -> w.like("userName", keyword).or().like("userAccount", keyword));
        queryWrapper.last("limit " + safeLimit);
        List<User> userList = this.list(queryWrapper);
        return getUserVOList(userList);
    }
}




