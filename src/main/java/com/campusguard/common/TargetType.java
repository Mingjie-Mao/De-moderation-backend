package com.campusguard.common;

/**
 * What a report or a moderation case points at.
 *
 * <p>Lives outside the report package because moderation cases will key on the
 * same pair of values, and a shared vocabulary is what lets a report and the
 * case it feeds be matched without translation.
 */
public enum TargetType {
    POST,
    COMMENT
}
