package com.traceproof.service;


import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.IService;
import com.traceproof.model.dto.archive.ArchiveAddRequest;
import com.traceproof.model.dto.archive.ArchiveDeleteRequest;
import com.traceproof.model.dto.archive.ArchiveQueryRequest;
import com.traceproof.model.entity.Evidence;
import com.traceproof.model.entity.Archive;
import com.traceproof.model.entity.User;
import com.traceproof.model.vo.ArchiveVO;

import javax.servlet.http.HttpServletRequest;
import java.util.List;

/**
 * @author lsj
 * @description 针对表【archive(档案库)】的数据库操作Service
 * @createDate 2026-10-13 23:36:58
 */
public interface ArchiveService extends IService<Archive> {





    /**
     * 校验档案库
     *
     * @param archive archive
     * @param add   是否为创建时检验
     */

    void validArchive(Archive archive, boolean add);

    long addArchive(ArchiveAddRequest archiveAddRequest, User loginUser, HttpServletRequest request);

    /**
     * 获取档案库包装类（单条）
     *
     * @param archive
     * @param request
     * @return
     */

    ArchiveVO getArchiveVO(Archive archive, HttpServletRequest request);

    /**
     * 获取档案库包装类（分页）
     *
     * @param archivePage
     * @param request
     * @return
     */

    Page<ArchiveVO> getArchiveVOPage(Page<Archive> archivePage, HttpServletRequest request);

    /**
     * 获取查询对象
     *
     * @param archiveQueryRequest
     * @return
     */

    QueryWrapper<Archive> getQueryWrapper(ArchiveQueryRequest archiveQueryRequest);

    /**
     * 根据档案库级别填充档案库对象
     */

    void fillArchiveByArchiveLevel(Archive archive);

    /**
     * 删除档案库
     *
     * @param archiveDeleteRequest archiveId
     * @param loginUser 登录用户
     * @return boolean true 删除成功 、false 删除失败
     */
    boolean deleteArchive(ArchiveDeleteRequest archiveDeleteRequest, User loginUser);

    /**
     * 增减档案库已用容量与影像数
     * <p>
     * 影像上传、删除后必须调用，否则档案库的 totalSize / totalCount 永远停在 0，
     * 容量校验也就形同虚设。用 SQL 自增自减而不是「查出来再更新」，
     * 避免同一个档案库并发上传时互相覆盖。
     *
     * @param archiveId    档案库 id
     * @param countDelta 影像数量增量，可为负数
     * @param sizeDelta  影像总大小增量（字节），可为负数
     */
    void updateArchiveUsage(Long archiveId, long countDelta, long sizeDelta);

}
