package com.traceproof.model.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

/**
 * 档案库用户关联（团队成员）
 * <p>
 * 仅团队档案库（archiveType = 1）才有成员记录；私有档案库不落这张表。
 * (archiveId, userId) 有唯一索引，一个用户在一个档案库里只能有一个角色。
 *
 * @author lsj
 */
@TableName(value = "archive_user")
@Data
public class ArchiveUser implements Serializable {

    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
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

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
