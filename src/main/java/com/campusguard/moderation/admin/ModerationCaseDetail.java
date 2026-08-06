package com.campusguard.moderation.admin;

import java.util.List;

/**
 * Everything a reviewer needs on one screen: the case, the content it concerns,
 * and the full history of what has happened to it.
 *
 * @param content null when the reported item has since been removed, which is
 *     itself information the reviewer needs rather than an error
 */
public record ModerationCaseDetail(
        ModerationCaseView moderationCase, ReportedContentView content, List<CaseAuditEntryView> auditTrail) {
}
