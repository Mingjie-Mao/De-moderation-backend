package com.campusguard.report;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReportProperties.class)
public class ReportConfig {
}
