package com.traceproof.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value ="archive")
@Data
public class Archive implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 档案库名称
     */
    private String archiveName;

    /**
     * 档案库级别：0-普通版 1-专业版 2-旗舰版
     */
    private Integer archiveLevel;
    /**
     * 档案库类型：0-私有 1-团队
     */
    private Integer archiveType;


    /**
     * 档案库影像的最大总大小
     */
    private Long maxSize;

    /**
     * 档案库影像的最大数量
     */
    private Long maxCount;

    /**
     * 当前档案库下影像的总大小
     */
    private Long totalSize;

    /**
     * 当前档案库下的影像数量
     */
    private Long totalCount;

    /**
     * 创建用户 id
     */
    private Long userId;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 编辑时间
     */
    private Date editTime;

    /**
     * 更新时间
     */
    private Date updateTime;

    /**
     * 是否删除
     */
    @TableLogic
    private Integer isDelete;

    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}
