package com.zifang.z.cache.spring;

import com.zifang.z.cache.client.ZCacheClient;
import com.zifang.z.cache.client.ZCacheClientConfig;
import com.zifang.z.cache.client.pool.ZCachePool;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * z-cache Spring Boot 自动配置。
 * <p>
 * 默认关闭远程客户端，应用设置 {@code z.cache.enabled=true} 后启用。
 */
@Configuration
@EnableConfigurationProperties(ZCacheProperties.class)
public class ZCacheAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "z.cache", name = "enabled", havingValue = "true")
    public ZCacheClientConfig zCacheClientConfig(ZCacheProperties properties) {
        return new ZCacheClientConfig()
                .withHost(properties.getHost())
                .withPort(properties.getPort())
                .withPassword(properties.getPassword())
                .withDatabase(properties.getDatabase())
                .withUseSsl(properties.isSsl())
                .withPoolMaxSize(properties.getPoolMaxSize())
                .withConnectTimeout(properties.getConnectTimeout())
                .withReadTimeout(properties.getReadTimeout());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "z.cache", name = "enabled", havingValue = "true")
    public ZCacheClient zCacheClient(ZCacheClientConfig config) {
        ZCacheClient client = new ZCacheClient(config);
        client.connect();
        return client;
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "z.cache", name = "enabled", havingValue = "true")
    public ZCacheTemplate zCacheTemplate(ZCacheClient client) {
        return new ZCacheTemplate(client);
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "z.cache", name = "enabled", havingValue = "true")
    public ZCachePool zCachePool(ZCacheClientConfig config) {
        return new ZCachePool(config);
    }
}
