package com.traceproof.manager.sharding;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.Properties;

/**
 * evidence 表的分表算法：一个团队档案库一张表，表名规则 {@code evidence_<archiveId>}。
 * <p>
 * 真实表由 {@link DynamicShardingManager} 在档案库创建时动态建立
 * （{@code CREATE TABLE evidence_xxx LIKE evidence}），并把表名注册进 actual-data-nodes。
 * 本类由 application.yml 里 evidence_sharding_algorithm 的 CLASS_BASED 配置引用，
 * 运行时由 ShardingSphere 反射实例化，不是 Spring Bean。
 *
 * @author lsj
 */
public class EvidenceShardingAlgorithm implements StandardShardingAlgorithm<Long> {

    /**
     * 逻辑表名，同时也是公共案例库（archiveId 为 null）和未分表档案库的落点
     */
    private static final String LOGIC_TABLE_NAME = "evidence";

    /**
     * ShardingSphere 5.2.0 的 ShardingSphereAlgorithm 要求提供 getProps()
     */
    private Properties props = new Properties();

    /**
     * 精确查询（where archiveId = ?）：定位到该档案库的独立表
     */
    @Override
    public String doSharding(Collection<String> availableTargetNames, PreciseShardingValue<Long> shardingValue) {
        Long archiveId = shardingValue.getValue();
        if (archiveId == null || archiveId == 0) {
            // 公共案例库（archiveId=0）没有档案库，走原表
            return LOGIC_TABLE_NAME;
        }
        String targetTableName = LOGIC_TABLE_NAME + "_" + archiveId;
        // 必须判断表是否真的在 actual-data-nodes 里。
        // 5.2.0 的 StandardShardingStrategy 会校验返回的表名是否属于该集合，不在就直接抛
        // ShardingRouteAlgorithmException；这里主动回退到原表，让两种情况都能正常读写：
        //   1. 私有档案库 —— 本来就不分表，数据留在原表 evidence；
        //   2. 团队档案库刚建好、分表还没建出来的极短窗口期。
        return availableTargetNames.contains(targetTableName) ? targetTableName : LOGIC_TABLE_NAME;
    }

    /**
     * 范围查询（where archiveId between ? and ?）：无法定位单表，返回全部由 ShardingSphere 归并
     */
    @Override
    public Collection<String> doSharding(Collection<String> availableTargetNames, RangeShardingValue<Long> shardingValue) {
        return availableTargetNames;
    }

    /**
     * 固定返回 CLASS_BASED，与 yml 里的 type 对应
     */
    @Override
    public String getType() {
        return "CLASS_BASED";
    }

    @Override
    public void init(Properties props) {
        this.props = props;
    }

    @Override
    public Properties getProps() {
        return props;
    }
}
