package com.traceproof.manager.auth.model;

import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.ArchiveUser;
import lombok.Data;

/**
 * ArchiveUserAuthContext
 * 表示用户在特定档案库内的授权上下文，包括关联的影像、档案库和用户信息。
 */
@Data
public class  ArchiveUserAuthContext{

    /**
     * 临时参数，不同请求对应的 id 可能不同
     */
    private Long id;

    /**
     * 影像 ID
     */
    private Long evidenceId;

    /**
     * 档案库 ID
     */
    private Long archiveId;

    /**
     * 档案库用户 ID
     */
    private Long archiveUserId;

    /**
     * 影像信息
     */
    private Evidence evidence;

    /**
     * 档案库信息
     */
    private Archive archive;

    /**
     * 档案库用户信息
     */
    private ArchiveUser archiveUser;
}
