package com.campusguard.common;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ContentRateLimitProperties.class)
public class ContentRateLimitConfig {
}
