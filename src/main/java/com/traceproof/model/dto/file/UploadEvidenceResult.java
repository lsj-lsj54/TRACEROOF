package com.traceproof.model.dto.file;

import lombok.Data;

@Data
public class UploadEvidenceResult {  
  
    /**  
     * 影像地址（原图）
     */  
    private String url;  

    /**
     * 缩略图地址（压缩后的 webp）
     * <p>
     * 列表页展示缩略图、点进详情再看原图，可以明显减少首页流量。
     * 压缩失败时会回退成原图地址，不会为空。
     */
    private String thumbnailUrl;

    /**  
     * 影像名称  
     */  
    private String picName;  
  
    /**  
     * 文件体积  
     */  
    private Long picSize;  
  
    /**  
     * 影像宽度  
     */  
    private int picWidth;  
  
    /**  
     * 影像高度  
     */  
    private int picHeight;  
  
    /**  
     * 影像宽高比  
     */  
    private Double picScale;  
  
    /**  
     * 影像格式  
     */  
    private String picFormat;  
  
    /**
     * 主色调（腾讯云数据万象 imageAve 返回，形如 #736246）
     * <p>
     * 上传成功后由 CosManager.getMainColor 回填；获取失败时为 null，
     * 不影响上传主流程。
     */
    private String picColor;

}
