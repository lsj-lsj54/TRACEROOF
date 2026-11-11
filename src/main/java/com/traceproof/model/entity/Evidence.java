package com.traceproof.model.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.io.Serializable;
import java.util.Date;

@TableName(value = "evidence")
@Data
public class Evidence implements Serializable {
    /**
     * id
     */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 影像 url
     */
    private String url;

    /**
     * 缩略图 url（压缩后的 webp）
     * <p>
     * 列表页用它、详情页用 url，兼顾加载速度与画质。
     * 历史数据该字段可能为空，前端需要回退到 url。
     */
    private String thumbnailUrl;

    /**
     * 影像名称
     */
    private String name;

    /**
     * 简介
     */
    private String introduction;

    /**
     * 分类
     */
    private String category;

    /**
     * 标签（JSON 数组）
     */
    private String tags;

    /**
     * 影像体积
     */
    private Long picSize;

    /**
     * 影像宽度
     */
    private Integer picWidth;

    /**
     * 影像高度
     */
    private Integer picHeight;

    /**
     * 影像宽高比例
     */
    private Double picScale;

    /**
     * 影像格式
     */
    private String picFormat;

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

    /**
     * 状态：0-待审核; 1-通过; 2-拒绝
     */
    private Integer reviewStatus;

    /**
     * 审核信息
     */
    private String reviewMessage;

    /**
     * 审核人 id
     */
    private Long reviewerId;

    /**
     * 审核时间
     */
    private Date reviewTime;

    /**
     * 档案库 id（为空表示公共档案库）
     */
    private Long archiveId;

    /**
     * 主色调（腾讯云数据万象 imageAve 返回，形如 #736246）
     * <p>
     * 用于「按颜色查图」。历史数据该字段可能为空，查询时会自动排除。
     */
    private String picColor;

    /**
     * 主色调 R 分量（0-255），配合 picColorG / picColorB 做颜色距离查询
     */
    private Integer picColorR;

    /**
     * 主色调 G 分量（0-255）
     */
    private Integer picColorG;

    /**
     * 主色调 B 分量（0-255）
     */
    private Integer picColorB;


    @TableField(exist = false)
    private static final long serialVersionUID = 1L;
}

