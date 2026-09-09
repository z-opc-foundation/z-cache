package com.zifang.z.cache.spring;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class ZCacheAutoConfigurationTest {
    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ZCacheAutoConfiguration.class);

    @Test
    void doesNotConnectWhenDisabled() {
        contextRunner
                .withPropertyValues("z.cache.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean("zCacheClient");
                    assertThat(context).doesNotHaveBean(ZCacheTemplate.class);
                    assertThat(context).hasSingleBean(ZCacheProperties.class);
                });
    }

    @Test
    void bindsClientProperties() {
        contextRunner
                .withPropertyValues(
                        "z.cache.enabled=false",
                        "z.cache.host=cache.internal",
                        "z.cache.port=6380",
                        "z.cache.pool-max-size=16")
                .run(context -> {
                    ZCacheProperties properties = context.getBean(ZCacheProperties.class);
                    assertThat(properties.getHost()).isEqualTo("cache.internal");
                    assertThat(properties.getPort()).isEqualTo(6380);
                    assertThat(properties.getPoolMaxSize()).isEqualTo(16);
                });
    }
}
