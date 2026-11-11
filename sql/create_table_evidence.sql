-- 影像表
create table if not exists evidence
(
    id           bigint auto_increment comment 'id' primary key,
    url          varchar(512)                       not null comment '影像 url',
    thumbnailUrl varchar(512)                       null comment '缩略图 url（压缩后的 webp），列表页使用',
    name         varchar(128)                       not null comment '影像名称',
    introduction varchar(512)                       null comment '简介',
    category     varchar(64)                        null comment '分类',
    tags         varchar(512)                       null comment '标签（JSON 数组）',
    picSize      bigint                             null comment '影像体积',
    picWidth     int                                null comment '影像宽度',
    picHeight    int                                null comment '影像高度',
    picScale     double                             null comment '影像宽高比例',
    picFormat    varchar(32)                        null comment '影像格式',
    userId       bigint                             not null comment '创建用户 id',
    createTime   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    editTime     datetime default CURRENT_TIMESTAMP not null comment '编辑时间',
    updateTime   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    isDelete     tinyint  default 0                 not null comment '是否删除',
    INDEX idx_name (name),                 -- 提升基于影像名称的查询性能
    INDEX idx_introduction (introduction), -- 用于模糊搜索影像简介
    INDEX idx_category (category),         -- 提升基于分类的查询性能
    INDEX idx_tags (tags),                 -- 提升基于标签的查询性能
    INDEX idx_userId (userId)              -- 提升基于用户 ID 的查询性能
) comment '影像' collate = utf8mb4_unicode_ci;

use evidence;

ALTER TABLE evidence
    -- 添加新列
    ADD COLUMN reviewStatus  INT DEFAULT 0 NOT NULL COMMENT '审核状态：0-待审核; 1-通过; 2-拒绝',
    ADD COLUMN reviewMessage VARCHAR(512)  NULL COMMENT '审核信息',
    ADD COLUMN reviewerId    BIGINT        NULL COMMENT '审核人 ID',
    ADD COLUMN reviewTime    DATETIME      NULL COMMENT '审核时间';

-- 创建基于 reviewStatus 列的索引
CREATE INDEX idx_reviewStatus ON evidence (reviewStatus);


-- 添加新列
ALTER TABLE evidence
    ADD COLUMN archiveId bigint null comment '档案库 id（为空表示公共档案库）';

-- 创建索引
CREATE INDEX idx_archiveId ON evidence (archiveId);


-- 主色调（腾讯云数据万象 imageAve 返回），用于「按颜色查图」
-- 历史数据该字段为空，上传时由后端回填；查询会按 RGB 欧氏距离筛选。
ALTER TABLE evidence
    ADD COLUMN picColor  varchar(7)       null comment '主色调（腾讯云 imageAve 返回，形如 #736246）',
    ADD COLUMN picColorR tinyint unsigned null comment '主色调 R 分量（0-255）',
    ADD COLUMN picColorG tinyint unsigned null comment '主色调 G 分量（0-255）',
    ADD COLUMN picColorB tinyint unsigned null comment '主色调 B 分量（0-255）';

