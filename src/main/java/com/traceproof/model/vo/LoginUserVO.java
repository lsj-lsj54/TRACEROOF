package com.traceproof.model.vo;

import com.traceproof.model.entity.User;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

@Data
public class LoginUserVO implements Serializable {

    /**
     * 用户 id
     */
    private Long id;

    /**
     * 账号
     */
    private String userAccount;

    /**
     * 用户昵称
     */
    private String userName;

    /**
     * 用户头像
     */
    private String userAvatar;

    /**
     * 用户简介
     */
    private String userProfile;

    /**
     * 用户角色：user/admin
     */
    private String userRole;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    private static final long serialVersionUID = 1L;

    /**
     * 封装类转实体类
     * <p>
     * VO 里没有密码字段，转换出来的实体不会带上 userPassword。
     *
     * @param loginUserVO 登录用户封装类
     * @return 用户实体类，入参为 null 时返回 null
     */
    public static User voToEntity(LoginUserVO loginUserVO) {
        if (loginUserVO == null) {
            return null;
        }
        User user = new User();
        BeanUtils.copyProperties(loginUserVO, user);
        return user;
    }

    /**
     * 实体类转封装类
     *
     * @param user 用户实体类
     * @return 登录用户封装类，入参为 null 时返回 null
     */
    public static LoginUserVO entityToVo(User user) {
        if (user == null) {
            return null;
        }
        LoginUserVO loginUserVO = new LoginUserVO();
        BeanUtils.copyProperties(user, loginUserVO);
        return loginUserVO;
    }
}
