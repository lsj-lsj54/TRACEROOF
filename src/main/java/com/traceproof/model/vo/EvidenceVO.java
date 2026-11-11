package com.traceproof.model.vo;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.traceproof.model.entity.Evidence;

import lombok.Data;
import org.springframework.beans.BeanUtils;
import java.util.Date;
import java.io.Serializable;
import java.util.List;

@Data
public class EvidenceVO implements Serializable {
  
    /**  
     * id  
     */  
    private Long id;  
  
    /**  
     * 影像 url  
     */  
    private String url;  

    /**
     * 缩略图 url（压缩后的 webp）
     * <p>
     * 列表页展示缩略图、详情页展示原图。
     * 历史数据可能没有这个字段，前端需要回退到 url。
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
     * 标签  
     */  
    private List<String> tags;
  
    /**  
     * 分类  
     */  
    private String category;  
  
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
     * 用户 id  
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
     * 审核状态：0-待审核; 1-通过; 2-拒绝
     * <p>
     * 前端「我的影像」用它在卡片角标上显示审核状态，
     * 缺了它角标会一直落在「未知」分支。
     */
    private Integer reviewStatus;

    /**
     * 档案库 id
     * <p>
     * 为空表示公共档案库（公共案例库），不为空表示该影像属于对应的私有档案库。
     * 前端据此区分公共案例库和私有档案库。
     */
    private Long archiveId;

    /**
     * 主色调（腾讯云数据万象 imageAve 返回，形如 #736246）
     * <p>
     * 前端可据此渲染颜色色块、或在「按颜色查图」结果中高亮匹配色。
     * 历史数据可能没有这个字段，前端需要判空。
     */
    private String picColor;

    /**  
     * 创建用户信息  
     */  
    private UserVO user;  
  
    private static final long serialVersionUID = 1L;  
  
    /**
     * 封装类转实体类
     *
     * @param evidenceVO 影像封装类
     * @return 影像实体类，入参为 null 时返回 null
     */
    public static Evidence voToEntity(EvidenceVO evidenceVO) {
        if (evidenceVO == null) {  
            return null;  
        }  
        Evidence evidence = new Evidence();  
        BeanUtils.copyProperties(evidenceVO, evidence);
        // 类型不同，需要转换  
        evidence.setTags(JSONUtil.toJsonStr(evidenceVO.getTags()));
        return evidence;  
    }  
  
    /**
     * 实体类转封装类
     *
     * @param evidence 影像实体类
     * @return 影像封装类，入参为 null 时返回 null
     */
    public static EvidenceVO entityToVo(Evidence evidence) {  
        if (evidence == null) {  
            return null;  
        }  
        EvidenceVO evidenceVO = new EvidenceVO();  
        BeanUtils.copyProperties(evidence, evidenceVO);  
        // 类型不同，需要转换  
        evidenceVO.setTags(JSONUtil.toList(evidence.getTags(), String.class));  
        // 历史数据没有缩略图，回退成原图，避免前端列表出现裂图
        if (StrUtil.isBlank(evidenceVO.getThumbnailUrl())) {
            evidenceVO.setThumbnailUrl(evidence.getUrl());
        }
        return evidenceVO;  
    }  
}

