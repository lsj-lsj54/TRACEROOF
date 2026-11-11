package com.traceproof.model.vo;

import com.traceproof.model.entity.User;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

@Data
public class UserVO implements Serializable {

    /**
     * id
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

    private static final long serialVersionUID = 1L;

    /**
     * 封装类转实体类
     * <p>
     * VO 里没有密码字段，转换出来的实体不会带上 userPassword，
     * 不会出现「脱敏信息回写把密码覆盖成 null」的情况。
     *
     * @param userVO 用户封装类
     * @return 用户实体类，入参为 null 时返回 null
     */
    public static User voToEntity(UserVO userVO) {
        if (userVO == null) {
            return null;
        }
        User user = new User();
        BeanUtils.copyProperties(userVO, user);
        return user;
    }

    /**
     * 实体类转封装类
     *
     * @param user 用户实体类
     * @return 用户封装类，入参为 null 时返回 null
     */
    public static UserVO entityToVo(User user) {
        if (user == null) {
            return null;
        }
        UserVO userVO = new UserVO();
        BeanUtils.copyProperties(user, userVO);
        return userVO;
    }
}

