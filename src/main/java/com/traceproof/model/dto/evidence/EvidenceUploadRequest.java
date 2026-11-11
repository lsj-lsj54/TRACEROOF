package com.traceproof.model.dto.evidence;

import lombok.Data;

import java.io.Serializable;

/**
 * 影像上传请求
 *
 * @author lsj
 */
@Data
public class EvidenceUploadRequest implements Serializable {

    /**
     * 影像 id（用于修改）
     */
    private Long id;

    /**
     * 影像地址（通过 URL 上传时必填，与文件上传二选一）
     */
    private String evidenceUrl;

    /**
     * 上传方式：file-本地文件；url-远程地址。
     * 不传时按「有 evidenceUrl 走 url，否则走 file」自动判断
     */
    private String uploadType;

    private String picName;

    /**
     * 档案库 id
     * <p>
     * 为空表示上传到公共档案库；不为空表示上传到该私有档案库，
     * 只允许档案库创建者（或管理员）上传，并会校验档案库容量。
     */
    private Long archiveId;

    private static final long serialVersionUID = 1L;
}
