-- 档案库成员表
create table if not exists archive_user
(
    id         bigint auto_increment comment 'id' primary key,
    archiveId    bigint                                 not null comment '档案库 id',
    userId     bigint                                 not null comment '用户 id',
    archiveRole  varchar(128) default 'viewer'          null comment '档案库角色：viewer/editor/admin',
    createTime datetime     default CURRENT_TIMESTAMP not null comment '创建时间',
    updateTime datetime     default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    -- 索引设计
    UNIQUE KEY uk_archiveId_userId (archiveId, userId), -- 唯一索引，用户在一个档案库中只能有一个角色
    INDEX idx_archiveId (archiveId),                    -- 提升按档案库查询的性能
    INDEX idx_userId (userId)                       -- 提升按用户查询的性能
) comment '档案库用户关联' collate = utf8mb4_unicode_ci;
