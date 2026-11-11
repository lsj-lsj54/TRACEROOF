package com.traceproof.model.dto.evidence;

import com.traceproof.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.util.List;

@EqualsAndHashCode(callSuper = true)
@Data
public class EvidenceQueryRequest extends PageRequest implements Serializable {
  
    /**  
     * id  
     */  
    private Long id;  
  
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
     * 标签  
     */  
    private List<String> tags;
  
    /**  
     * 文件体积  
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
     * 影像比例  
     */  
    private Double picScale;  
  
    /**  
     * 影像格式  
     */  
    private String picFormat;  
  
    /**  
     * 搜索词（同时搜名称、简介等）  
     */  
    private String searchText;  
  
    /**  
     * 用户 id  
     */  
    private Long userId;

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
     * 档案库 id
     * <p>
     * 为空表示只查公共档案库的影像；不为空表示查该私有档案库下的影像，
     * 私有档案库只允许档案库创建者（或管理员）查询。
     */
    private Long archiveId;

    /**
     * 主色调查询（腾讯云 imageAve 返回的颜色），形如 #736246 / 0x736246 / 736246
     * <p>
     * 传入后按「颜色距离」筛选并排序：返回与该颜色最接近、且距离不超过
     * {@code colorDistance} 的影像。仅对已有主色调的影像生效。
     */
    private String picColor;

    /**
     * 颜色距离阈值（0 ~ 441，值越大返回的颜色范围越宽）
     * <p>
     * 不传或传负数时取默认值（见 EvidenceServiceImpl.DEFAULT_COLOR_DISTANCE）。
     */
    private Integer colorDistance;



    private static final long serialVersionUID = 1L;  
}

