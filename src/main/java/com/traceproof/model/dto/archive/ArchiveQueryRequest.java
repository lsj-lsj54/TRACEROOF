package com.traceproof.model.dto.archive;

import com.traceproof.common.PageRequest;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
public class ArchiveQueryRequest extends PageRequest implements Serializable {

    /**
     * id
     */
    private Long id;

    /**
     * 用户 id
     */
    private Long userId;

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

    private static final long serialVersionUID = 1L;
}
