package com.traceproof.model.vo;

import com.traceproof.model.entity.ArchiveUser;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

@Data
public class ArchiveUserVO implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 档案库 id
     */
    private Long archiveId;

    /**
     * 用户 id
     */
    private Long userId;

    /**
     * 档案库角色：viewer/editor/admin
     */
    private String archiveRole;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 用户信息
     */
    private UserVO user;

    /**
     * 档案库信息
     */
    private ArchiveVO archive;

    private static final long serialVersionUID = 1L;

    /**
     * 封装类转对象
     *
     * @param archiveUserVO
     * @return
     */
    public static ArchiveUser voToObj(ArchiveUserVO archiveUserVO) {
        if (archiveUserVO == null) {
            return null;
        }
        ArchiveUser archiveUser = new ArchiveUser();
        BeanUtils.copyProperties(archiveUserVO, archiveUser);
        return archiveUser;
    }

    /**
     * 对象转封装类
     *
     * @param archiveUser
     * @return
     */
    public static ArchiveUserVO objToVo(ArchiveUser archiveUser) {
        if (archiveUser == null) {
            return null;
        }
        ArchiveUserVO archiveUserVO = new ArchiveUserVO();
        BeanUtils.copyProperties(archiveUser, archiveUserVO);
        return archiveUserVO;
    }
}
