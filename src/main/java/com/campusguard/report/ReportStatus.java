package com.campusguard.report;

/**
 * A report's own lifecycle, which is distinct from the lifecycle of the
 * moderation case it will eventually feed.
 *
 * <p>{@code AGGREGATED} means the report has been folded into a case and is no
 * longer independently actionable; the outcome then belongs to the case, not to
 * each reporter's submission.
 */
public enum ReportStatus {
    PENDING,
    AGGREGATED,
    RESOLVED
}
