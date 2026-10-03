package com.zifang.z.cache.spring.host;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;

/**
 * 宿主侧胶水装配 (2026-10-03 自 z-opc main-starter 平移):
 * CacheProxyController (/api/cache/** 自省面) + ZCacheEmbeddedServerConfig (内嵌 RedisServer).
 *
 * <p>跟随 z.cache.host.enabled 开关 (默认关): 寄生 all-in-one 模式由宿主打开,
 * standalone 分布式模式 (z-cache 独立容器) 不开 —— 控制面走独立容器, 宿主只留 client.
 * ZCacheAutoConfiguration (client 模板) 不受此开关影响, 始终可用.
 */
@Configuration
@ConditionalOnProperty(prefix = "z.cache.host", name = "enabled", havingValue = "true", matchIfMissing = false)
@ComponentScan(basePackages = "com.zifang.z.cache.spring.host")
public class CacheHostAutoConfiguration {
}
