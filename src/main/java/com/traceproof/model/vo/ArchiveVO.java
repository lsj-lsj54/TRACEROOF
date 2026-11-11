package com.traceproof.model.vo;

import com.traceproof.model.entity.Archive;
import lombok.Data;
import org.springframework.beans.BeanUtils;

import java.io.Serializable;
import java.util.Date;

@Data
public class ArchiveVO implements Serializable {
    /**
     * id
     */
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
     * 创建用户信息
     */
    private UserVO user;

    private static final long serialVersionUID = 1L;

    /**
     * 封装类转实体类
     * <p>
     * 只搬运两边同名的字段，{@code user} 这类关联字段不在实体里，会被忽略。
     *
     * @param archiveVO 档案库封装类
     * @return 档案库实体类，入参为 null 时返回 null
     */
    public static Archive voToEntity(ArchiveVO archiveVO) {
        if (archiveVO == null) {
            return null;
        }
        Archive archive = new Archive();
        BeanUtils.copyProperties(archiveVO, archive);
        return archive;
    }

    /**
     * 实体类转封装类
     *
     * @param archive 档案库实体类
     * @return 档案库封装类，入参为 null 时返回 null
     */
    public static ArchiveVO entityToVo(Archive archive) {
        if (archive == null) {
            return null;
        }
        ArchiveVO archiveVO = new ArchiveVO();
        BeanUtils.copyProperties(archive, archiveVO);
        return archiveVO;
    }
}
