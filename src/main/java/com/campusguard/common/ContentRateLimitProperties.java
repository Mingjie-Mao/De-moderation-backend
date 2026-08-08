package com.campusguard.common;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How fast one account may add content to the forum.
 *
 * <p>Reporting was limited from the start and authoring was not, which read as a
 * decision and was an oversight. The asymmetry is backwards: a report costs a
 * moderator one glance at something a person already chose to flag, while a post
 * costs an engine call, a queue slot and a reviewer's attention if anyone reports
 * it. Registration is open, so "authenticated" is not a brake on anything.
 *
 * <p>Generous on purpose. This is a ceiling on flooding, not a throttle on
 * enthusiasm — a person in an argument at midnight should never meet it, and
 * a script should meet it almost at once.
 *
 * @param postsPerUser how many posts one account may create within {@code window}
 * @param commentsPerUser how many comments one account may create within
 *     {@code window}; higher, because replying is the normal way to use a forum
 *     and posting is not
 * @param window the period both limits are measured over
 */
@ConfigurationProperties(prefix = "campusguard.content")
public record ContentRateLimitProperties(
        @DefaultValue("30") int postsPerUser,
        @DefaultValue("120") int commentsPerUser,
        @DefaultValue("1h") Duration window) {
}
