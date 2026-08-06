package com.campusguard.report;

/**
 * A report's own lifecycle, which is shorter than that of the case it feeds.
 *
 * <p>There is no pending state. Filing a report opens or joins its case in the
 * same transaction, so a report without a case never exists for anyone to
 * observe, and a value nothing is ever found in would only mislead whoever read
 * this next.
 */
public enum ReportStatus {

    /** Folded into a case. The outcome now belongs to the case, not to each reporter's submission. */
    AGGREGATED,

    /** The case this report fed has been decided. */
    RESOLVED
}
