package com.traceproof.manager.sharding;

import com.traceproof.model.entity.Archive;
import com.traceproof.model.enums.ArchiveTypeEnum;
import com.traceproof.service.ArchiveService;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.jdbc.core.connection.ShardingSphereConnection;
import org.apache.shardingsphere.infra.metadata.database.rule.ShardingSphereRuleMetaData;
import org.apache.shardingsphere.mode.manager.ContextManager;
import org.apache.shardingsphere.sharding.api.config.ShardingRuleConfiguration;
import org.apache.shardingsphere.sharding.api.config.rule.ShardingTableRuleConfiguration;
import org.apache.shardingsphere.sharding.rule.ShardingRule;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 动态分表管理器：为**团队档案库**维护"一个档案库一张表"的 evidence 分表。
 * <p>
 * 逻辑表 {@code evidence} 被 ShardingSphere 按 archiveId 路由，真实表规则是 {@code evidence_<archiveId>}。
 * 真实表不是静态写在 yml 里的，而是：
 * <ol>
 *     <li>团队档案库创建时由 {@link #createArchiveEvidenceTable(Archive)} 建表；</li>
 *     <li>启动时由 {@link #initialize()} 补齐历史团队档案库缺失的表；</li>
 *     <li>每次表集合变化后由 {@link #updateShardingTableNodes()} 把表名列表写回 actual-data-nodes。</li>
 * </ol>
 * 私有档案库（archiveType = 0）和公共案例库（archiveId 为 null）不建表，查询由
 * {@link EvidenceShardingAlgorithm} 回退到原表 {@code evidence}。
 *
 * @author lsj
 */
@Component
@Slf4j
public class DynamicShardingManager {

    @Resource
    private DataSource dataSource;

    @Resource
    private ArchiveService archiveService;

    private static final String LOGIC_TABLE_NAME = "evidence";

    private static final String DATABASE_NAME = "logic_db"; // 配置文件中的数据库名称

    @PostConstruct
    public void initialize() {
        log.info("初始化动态分表配置...");
        // 先补建历史团队档案库缺失的分表，再刷新路由节点。
        // 顺序不能反：actual-data-nodes 里一旦出现不存在的表，查询该档案库会直接报 Table doesn't exist。
        int created = createMissingTables();
        if (created > 0) {
            log.info("启动补建团队档案库分表 {} 张", created);
        }
        updateShardingTableNodes();
    }

    /**
     * 启动时为缺失分表的团队档案库补建物理表。
     * <p>
     * 这个方法是为了兼容"分表功能上线前就已经存在的团队档案库"：
     * 它们的 evidence_&lt;archiveId&gt; 从来没有被创建过，而 fetchAllEvidenceTableNames
     * 会把它们写进 actual-data-nodes，不补建就会查询报错。
     *
     * @return 本次实际补建的表数量
     */
    private int createMissingTables() {
        Set<Long> teamArchiveIds = fetchTeamArchiveIds();
        if (teamArchiveIds.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (Long archiveId : teamArchiveIds) {
            // CREATE TABLE IF NOT EXISTS 对已存在的表是空操作，不需要先查询判断
            if (executeCreateTable(LOGIC_TABLE_NAME + "_" + archiveId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 获取所有未删除团队档案库的 id。
     * <p>
     * {@code Archive.isDelete} 上有 {@code @TableLogic}，lambdaQuery 会自动带上 isDelete = 0，
     * 所以已逻辑删除的档案库不会进入路由节点。
     */
    private Set<Long> fetchTeamArchiveIds() {
        return archiveService.lambdaQuery()
                .eq(Archive::getArchiveType, ArchiveTypeEnum.TEAM.getValue())
                .list()
                .stream()
                .map(Archive::getId)
                .collect(Collectors.toSet());
    }

    /**
     * 获取所有动态表名，包括初始表 evidence 和分表 evidence_{archiveId}
     */
    private Set<String> fetchAllEvidenceTableNames() {
        Set<String> tableNames = fetchTeamArchiveIds().stream()
                .map(archiveId -> LOGIC_TABLE_NAME + "_" + archiveId)
                .collect(Collectors.toSet());
        tableNames.add(LOGIC_TABLE_NAME); // 添加初始逻辑表，公共案例库和私有档案库都用它
        return tableNames;
    }

    /**
     * 更新 ShardingSphere 的 actual-data-nodes 动态表名配置
     */
    private void updateShardingTableNodes() {
        Set<String> tableNames = fetchAllEvidenceTableNames();
        String newActualDataNodes = tableNames.stream()
                // 前缀是 yml 里 shardingsphere.datasource.names 配的数据源名，不是数据库名。
                // 教程里库名就叫 yu_evidence 所以两者相同，本项目库名是 evidence，
                // 沿用 yu_evidence 会报 Missing the data source name: 'yu_evidence'
                .map(tableName -> "evidence." + tableName)
                .collect(Collectors.joining(","));
        log.info("动态分表 actual-data-nodes 配置: {}", newActualDataNodes);

        ContextManager contextManager = getContextManager();
        ShardingSphereRuleMetaData ruleMetaData = contextManager.getMetaDataContexts()
                .getMetaData()
                .getDatabases()
                .get(DATABASE_NAME)
                .getRuleMetaData();

        Optional<ShardingRule> shardingRule = ruleMetaData.findSingleRule(ShardingRule.class);
        if (shardingRule.isPresent()) {
            ShardingRuleConfiguration ruleConfig = (ShardingRuleConfiguration) shardingRule.get().getConfiguration();
            List<ShardingTableRuleConfiguration> updatedRules = ruleConfig.getTables()
                    .stream()
                    .map(oldTableRule -> {
                        if (LOGIC_TABLE_NAME.equals(oldTableRule.getLogicTable())) {
                            ShardingTableRuleConfiguration newTableRuleConfig = new ShardingTableRuleConfiguration(LOGIC_TABLE_NAME, newActualDataNodes);
                            newTableRuleConfig.setDatabaseShardingStrategy(oldTableRule.getDatabaseShardingStrategy());
                            newTableRuleConfig.setTableShardingStrategy(oldTableRule.getTableShardingStrategy());
                            newTableRuleConfig.setKeyGenerateStrategy(oldTableRule.getKeyGenerateStrategy());
                            newTableRuleConfig.setAuditStrategy(oldTableRule.getAuditStrategy());
                            return newTableRuleConfig;
                        }
                        return oldTableRule;
                    })
                    .collect(Collectors.toList());
            ruleConfig.setTables(updatedRules);
            contextManager.alterRuleConfiguration(DATABASE_NAME, Collections.singleton(ruleConfig));
            contextManager.reloadDatabase(DATABASE_NAME);
            log.info("动态分表规则更新成功！");
        } else {
            log.error("未找到 ShardingSphere 的分片规则配置，动态分表更新失败。");
        }
    }

    /**
     * 获取 ShardingSphere ContextManager
     */
    private ContextManager getContextManager() {
        try (ShardingSphereConnection connection = dataSource.getConnection().unwrap(ShardingSphereConnection.class)) {
            return connection.getContextManager();
        } catch (SQLException e) {
            throw new RuntimeException("获取 ShardingSphere ContextManager 失败", e);
        }
    }

    /**
     * 为团队档案库创建分表，并在表集合变化后刷新路由。
     * <p>
     * 只对团队档案库生效：私有档案库和公共案例库共用原表 {@code evidence}。
     * 必须在事务**提交之后**调用——MySQL 的 DDL 会隐式提交当前事务，
     * 放在事务里会让"档案库入库"变成不可回滚的半步操作。
     *
     * @param archive 刚创建的团队档案库
     */
    public void createArchiveEvidenceTable(Archive archive) {
        if (archive == null || archive.getId() == null) {
            return;
        }
        if (!isTeamArchive(archive)) {
            return;
        }
        String tableName = LOGIC_TABLE_NAME + "_" + archive.getId();
        if (!executeCreateTable(tableName)) {
            return;
        }
        // 建表是幂等的（IF NOT EXISTS），重复调用不会重复刷新路由
        updateShardingTableNodes();
    }

    /**
     * 判断是否为团队档案库。用 equals 而不是 ==，避免 Integer 与 int 混用时拆箱 NPE
     */
    private boolean isTeamArchive(Archive archive) {
        Integer archiveType = archive.getArchiveType();
        return archiveType != null && ArchiveTypeEnum.TEAM.getValue() == archiveType;
    }

    /**
     * 执行 CREATE TABLE IF NOT EXISTS，返回是否新建成功
     * <p>
     * 直接用 DataSource 执行而不是 SqlRunner：本方法可能在 Spring 启动早期
     * （@PostConstruct）被调用，此时 MyBatis 的 SqlSessionFactory 未必就绪。
     * 表名由 archiveId（Long）拼出，不存在 SQL 注入面。
     */
    private boolean executeCreateTable(String tableName) {
        String sql = "CREATE TABLE IF NOT EXISTS " + tableName + " LIKE " + LOGIC_TABLE_NAME;
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute(sql);
            return true;
        } catch (SQLException e) {
            log.error("创建影像档案库分表失败，表名 = {}", tableName, e);
            return false;
        }
    }
}
