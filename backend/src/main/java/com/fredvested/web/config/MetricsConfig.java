package com.fredvested.web.config;

import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;

@Configuration
public class MetricsConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
            .meterFilter(MeterFilter.denyNameStartsWith("system.cpu"))
            .meterFilter(MeterFilter.denyNameStartsWith("process.cpu"))
            .meterFilter(MeterFilter.denyNameStartsWith("process.uptime"))
            .meterFilter(MeterFilter.denyNameStartsWith("system.load"));
    }
}