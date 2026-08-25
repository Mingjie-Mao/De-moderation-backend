package com.campusguard.report;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param perUserLimit how many reports one account may file within
 *     {@code perUserWindow}. Set for a person using the forum normally, not for a
 *     person trying to bury someone.
 * @param perUserWindow the period the limit is measured over
 */
@ConfigurationProperties(prefix = "campusguard.reports")
public record ReportProperties(
        @DefaultValue("20") int perUserLimit,
        @DefaultValue("1h") Duration perUserWindow) {
}
