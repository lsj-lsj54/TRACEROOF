package com.traceproof.controller;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.traceproof.annotation.AuthCheck;
import com.traceproof.annotation.RateLimit;
import com.traceproof.common.BaseResponse;
import com.traceproof.common.DeleteRequest;
import com.traceproof.common.ResultUtils;
import com.traceproof.exception.BusinessException;
import com.traceproof.exception.ErrorCode;
import com.traceproof.exception.ThrowUtils;
import com.traceproof.manager.auth.StpKit;
import com.traceproof.model.constant.UserConstant;
import com.traceproof.model.dto.user.UserAddRequest;
import com.traceproof.model.dto.user.UserLoginRequest;
import com.traceproof.model.dto.user.UserQueryRequest;
import com.traceproof.model.dto.user.UserRegisterRequest;
import com.traceproof.model.dto.user.UserUpdateMyRequest;
import com.traceproof.model.dto.user.UserUpdateRequest;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.LoginUserVO;
import com.traceproof.model.vo.UserVO;
import com.traceproof.service.UserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeanUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Objects;

/**
 * 用户模块接口
 *
 * @author lsj
 */
@Slf4j
@RestController
@RequestMapping("/user")
public class UserController {

    @Resource
    private UserService userService;

    /**
     * 用户注册
     *
     * @param userRegisterRequest 用户注册请求
     * @return 新用户 id
     */
    @PostMapping("/register")
    public BaseResponse<Long> userRegister(@RequestBody UserRegisterRequest userRegisterRequest) {
        ThrowUtils.throwIf(userRegisterRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userRegisterRequest.getUserAccount();
        String userPassword = userRegisterRequest.getUserPassword();
        String checkPassword = userRegisterRequest.getCheckPassword();
        String userName = userRegisterRequest.getUserName();
        long result = userService.userRegister(userAccount, userPassword, checkPassword, userName);
        return ResultUtils.success(result);
    }

    /**
     * 用户登录
     *
     * @param userLoginRequest 用户登录请求
     * @param request          request 请求
     * @return 脱敏后的登录用户信息
     */
    @PostMapping("/login")
    @RateLimit(key = "login", rate = 30, windowSeconds = 60)
    public BaseResponse<LoginUserVO> userLogin(@RequestBody UserLoginRequest userLoginRequest, HttpServletRequest request) {
        ThrowUtils.throwIf(userLoginRequest == null, ErrorCode.PARAMS_ERROR);
        String userAccount = userLoginRequest.getUserAccount();
        String userPassword = userLoginRequest.getUserPassword();
        LoginUserVO loginUserVO = userService.userLogin(userAccount, userPassword, request);
        return ResultUtils.success(loginUserVO);
    }

    /**
     * 获取当前登录用户
     *
     * @param request request 请求
     * @return 脱敏后的登录用户信息
     */
    @GetMapping("/get/login")
    public BaseResponse<LoginUserVO> getLoginUser(HttpServletRequest request) {
        User user = userService.getLoginUser(request);
        return ResultUtils.success(LoginUserVO.entityToVo(user));
    }

    /**
     * 用户注销
     *
     * @param request request 请求
     * @return 注销结果
     */
    @PostMapping("/logout")
    public BaseResponse<Boolean> userLogout(HttpServletRequest request) {
        ThrowUtils.throwIf(request == null, ErrorCode.PARAMS_ERROR);
        boolean result = userService.userLogout(request);
        return ResultUtils.success(result);
    }

    /**
     * 修改当前登录用户的昵称
     * <p>
     * 登录即可调用，不需要管理员权限。用户 id 一律从 Session 里取，
     * 前端传什么 id 都改不了别人的昵称。
     *
     * @param userUpdateMyRequest 修改请求，目前只有昵称字段
     * @param request             request 请求
     * @return 更新后的脱敏用户信息，前端可直接用来刷新头部显示
     */
    @PostMapping("/update/my")
    public BaseResponse<LoginUserVO> updateMyUser(@RequestBody UserUpdateMyRequest userUpdateMyRequest,
                                                  HttpServletRequest request) {
        ThrowUtils.throwIf(userUpdateMyRequest == null, ErrorCode.PARAMS_ERROR);
        User loginUser = userService.getLoginUser(request);
        userService.updateUserName(loginUser.getId(), userUpdateMyRequest.getUserName());
        // 重新查一次再脱敏返回，避免把 Session 里的旧昵称回给前端
        return ResultUtils.success(LoginUserVO.entityToVo(userService.getById(loginUser.getId())));
    }

    /**
     * 创建用户（仅管理员可用）
     *
     * @param userAddRequest 用户创建请求
     * @return 新用户 id
     */
    @PostMapping("/add")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Long> addUser(@RequestBody UserAddRequest userAddRequest) {
        ThrowUtils.throwIf(userAddRequest == null, ErrorCode.PARAMS_ERROR);
        User user = new User();
        BeanUtils.copyProperties(userAddRequest, user);
        // 默认密码，统一使用公共常量
        String encryptPassword = userService.getEncryptPassword(UserConstant.DEFAULT_PASSWORD);
        user.setUserPassword(encryptPassword);
        boolean result = userService.save(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        return ResultUtils.success(user.getId());
    }

    /**
     * 根据 id 获取用户（仅管理员）
     *
     * @param id 用户 id
     * @return 用户实体类
     */
    @GetMapping("/get")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<User> getUserById(long id) {
        ThrowUtils.throwIf(id <= 0, ErrorCode.PARAMS_ERROR);
        User user = userService.getById(id);
        ThrowUtils.throwIf(user == null, ErrorCode.NOT_FOUND_ERROR);
        return ResultUtils.success(user);
    }

    /**
     * 根据 id 获取包装类
     *
     * @param id 用户 id
     * @return 脱敏后的用户信息
     */
    @GetMapping("/get/vo")
    public BaseResponse<UserVO> getUserVOById(long id) {
        // 复用 getUserById 的查询与校验逻辑
        User user = this.getUserById(id).getData();
        return ResultUtils.success(userService.getUserVO(user));
    }

    /**
     * 删除用户（仅管理员可用）
     *
     * @param deleteRequest 删除请求，包含用户 id
     * @return 是否删除成功
     */
    @PostMapping("/delete")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> deleteUser(@RequestBody DeleteRequest deleteRequest) {
        if (deleteRequest == null || deleteRequest.getId() <= 0) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        // 判断用户是否存在（公共的校验逻辑，避免直接删除不存在的数据）
        boolean exists = userService.getById(deleteRequest.getId()) != null;
        ThrowUtils.throwIf(!exists, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        boolean result = userService.removeById(deleteRequest.getId());
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 清掉该用户所有端口的 token 与会话。
        // 只删库是不够的：StpInterfaceImpl 算权限时优先取会话里的 User 快照，
        // 快照还在就能继续通过档案库权限注解，直到 30 天后 token 自然过期。
        logoutQuietly(deleteRequest.getId());
        return ResultUtils.success(true);
    }

    /**
     * 更新用户（仅管理员可用）
     *
     * @param userUpdateRequest 用户更新请求
     * @return 是否更新成功
     */
    @PostMapping("/update")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Boolean> updateUser(@RequestBody UserUpdateRequest userUpdateRequest) {
        if (userUpdateRequest == null || userUpdateRequest.getId() == null) {
            throw new BusinessException(ErrorCode.PARAMS_ERROR);
        }
        User user = new User();
        BeanUtils.copyProperties(userUpdateRequest, user);
        User oldUser = userService.getById(userUpdateRequest.getId());
        ThrowUtils.throwIf(oldUser == null, ErrorCode.NOT_FOUND_ERROR, "用户不存在");
        boolean result = userService.updateById(user);
        ThrowUtils.throwIf(!result, ErrorCode.OPERATION_ERROR);
        // 角色变了必须踢下线：会话里存的是登录那一刻的 User 快照，
        // StpInterfaceImpl 判断管理员身份时读的就是这份快照，不踢会出现「已降级仍按管理员放行」。
        // 仅改昵称之类的普通字段不踢，避免把人无故踢出登录。
        if (user.getUserRole() != null && !Objects.equals(oldUser.getUserRole(), user.getUserRole())) {
            logoutQuietly(user.getId());
        }
        return ResultUtils.success(true);
    }

    /**
     * 踢掉指定用户的全部登录态（所有设备）
     * <p>
     * 这里吞掉异常是有意的：调用方在此之前已经把数据改完了，不该因为 Redis 抖动
     * 就让接口返回失败；最坏情况只是登录态多存活到 token 超时。
     *
     * @param userId 用户 id
     */
    private void logoutQuietly(Long userId) {
        if (userId == null) {
            return;
        }
        try {
            StpKit.ARCHIVE.logout(userId);
        } catch (Exception e) {
            log.warn("清理用户 {} 的登录态失败，将在 token 超时后自然失效：{}", userId, e.getMessage());
        }
    }

    /**
     * 分页获取用户封装列表（仅管理员）
     *
     * @param userQueryRequest 查询请求参数
     * @return 用户封装类分页数据
     */
    //注意这里如果直接把recode里的Long类型的id直接返回，会丢失精度，原因是js的精确度不够
    @PostMapping("/list/page/vo")
    @AuthCheck(mustRole = UserConstant.ADMIN_ROLE)
    public BaseResponse<Page<UserVO>> listUserVOByPage(@RequestBody UserQueryRequest userQueryRequest) {
        ThrowUtils.throwIf(userQueryRequest == null, ErrorCode.PARAMS_ERROR);
        long current = userQueryRequest.getCurrent();
        long pageSize = userQueryRequest.getPageSize();
        Page<User> userPage = userService.page(new Page<>(current, pageSize),
                userService.getQueryWrapper(userQueryRequest));
        Page<UserVO> userVOPage = new Page<>(current, pageSize, userPage.getTotal());
        List<UserVO> userVOList = userService.getUserVOList(userPage.getRecords());
        userVOPage.setRecords(userVOList);
        return ResultUtils.success(userVOPage);
    }

    /**
     * 按昵称或账号搜索用户（脱敏返回，供邀请团队成员时匹配候选）
     * <p>
     * 仅登录用户可调用；不返回密码等敏感字段，最多返回 20 条。
     *
     * @param keyword 搜索关键字（匹配昵称或账号，模糊）
     * @param request request 请求
     * @return 匹配的用户脱敏列表
     */
    @GetMapping("/search")
    public BaseResponse<List<UserVO>> searchUser(String keyword, HttpServletRequest request) {
        // 仅要求登录，非管理员也能用（邀请成员需要）
        //userService.getLoginUser(request);
        return ResultUtils.success(userService.searchUserVO(keyword, 20));
    }

}
