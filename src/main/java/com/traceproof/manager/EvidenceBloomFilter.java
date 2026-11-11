package com.traceproof.manager;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

/**
 * 影像 id 布隆过滤器：防缓存穿透
 * <p>
 * 场景：攻击者用大量不存在的影像 id 反复查询，每次都会穿透缓存、打到数据库。
 * 布隆过滤器用一个「可能存在的 id 集合」挡在前面：查询前先判
 * {@link #mightContain(Long)}，返回 false 表示「一定不存在」，直接短路、不查库。
 * <p>
 * 实现要点：
 * <ul>
 *     <li>基于 Redisson 的分布式 RBloomFilter，多实例共享同一份，避免各建各的；</li>
 *     <li>上传新影像时写入 id（{@link #add(Long)}），启动时全量回填存量 id；</li>
 *     <li>布隆过滤器只能加不能删，影像删除后 id 仍残留，属于「可接受的误判」
 *     （误判只是多查一次库、返回空，与没加过滤器时行为一致，不影响正确性）；</li>
 *     <li>回填失败时 {@code ready} 保持 false，查询退化为直接查库，绝不错杀合法查询。</li>
 * </ul>
 *
 * @author lsj
 */
@Slf4j
@Component
public class EvidenceBloomFilter implements ApplicationRunner {

    private static final String BLOOM_KEY = "evidence:id:bloom";

    /** 预计元素数量，按 10 万张图预留 */
    private static final long EXPECTED_INSERTIONS = 100_000L;

    /** 误判率 1%，即「不存在却被判成可能存在」的概率，会多一次空查询 */
    private static final double FALSE_POSITIVE_RATE = 0.01;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private DataSource dataSource;

    /** 是否已完成回填；未就绪时退化为「可能命中」，避免误拒合法查询 */
    private volatile boolean ready = false;

    private volatile RBloomFilter<Long> bloomFilter;

    @Override
    public void run(ApplicationArguments args) {
        ready = backfill(getBloomFilter());
        log.info("影像布隆过滤器初始化完成，ready = {}", ready);
    }

    /**
     * 懒加载 + 双重检查，拿到 Redis 中的布隆过滤器实例
     */
    private RBloomFilter<Long> getBloomFilter() {
        if (bloomFilter == null) {
            synchronized (this) {
                if (bloomFilter == null) {
                    RBloomFilter<Long> filter = redissonClient.getBloomFilter(BLOOM_KEY);
                    // tryInit 只在首次生效；过滤器已存在时保留原配置，避免重复初始化覆盖
                    filter.tryInit(EXPECTED_INSERTIONS, FALSE_POSITIVE_RATE);
                    bloomFilter = filter;
                }
            }
        }
        return bloomFilter;
    }

    /**
     * 新影像上传成功后写入，保证后续查询能命中
     */
    public void add(Long evidenceId) {
        if (evidenceId != null) {
            getBloomFilter().add(evidenceId);
        }
    }

    /**
     * 判断影像 id 是否可能存在。
     *
     * @return false 表示「一定不存在」，可直接短路不查库；true 表示「可能存在」，
     *         仍需查库确认（可能命中也可能因误判而空查询）
     */
    public boolean mightContain(Long evidenceId) {
        if (!ready) {
            return true;
        }
        return evidenceId != null && getBloomFilter().contains(evidenceId);
    }

    /**
     * 启动时把全量影像 id 回填进布隆过滤器。
     * <p>
     * 通过 ShardingSphere 数据源执行不带 archiveId 的查询，会广播到所有分表
     * （evidence + evidence_&lt;archiveId&gt;），拿到全量 id。
     *
     * @return 回填是否成功
     */
    private boolean backfill(RBloomFilter<Long> filter) {
        String sql = "SELECT id FROM evidence WHERE isDelete = 0";
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            int count = 0;
            while (resultSet.next()) {
                filter.add(resultSet.getLong(1));
                count++;
            }
            log.info("布隆过滤器回填完成，共 {} 个影像 id", count);
            return true;
        } catch (Exception e) {
            log.error("布隆过滤器回填失败，查询将退化为直接查库", e);
            return false;
        }
    }
}
