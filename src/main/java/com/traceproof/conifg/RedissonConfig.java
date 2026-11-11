package com.traceproof.conifg;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * Redisson 客户端配置
 * <p>
 * 与 Spring Data Redis 的 Lettuce 并存，互不干扰：Lettuce 服务于缓存 / Sa-Token 会话，
 * Redisson 服务于分布式锁（RLock）、布隆过滤器（RBloomFilter）、限流器（RRateLimiter）。
 * <p>
 * 连接信息复用 spring.redis.* 配置，避免在仓库里再写一份 Redis 地址。
 *
 * @author lsj
 */
@Configuration
public class RedissonConfig {

    @Value("${spring.redis.host:127.0.0.1}")
    private String host;

    @Value("${spring.redis.port:6379}")
    private int port;

    @Value("${spring.redis.database:0}")
    private int database;

    @Value("${spring.redis.password:}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        SingleServerConfig serverConfig = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database);
        if (StringUtils.hasText(password)) {
            serverConfig.setPassword(password);
        }
        return Redisson.create(config);
    }
}
