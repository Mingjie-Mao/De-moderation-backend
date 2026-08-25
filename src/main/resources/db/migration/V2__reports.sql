-- V2: user-submitted reports.
--
-- Moderation cases, rules, the audit log and AI invocation records deliberately
-- stay out of this migration. Nothing consumes them until the moderation
-- workflow lands, and a migration that ships tables with no caller cannot be
-- reviewed against real usage.

CREATE TABLE reports (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type VARCHAR(20) NOT NULL,
    target_id   UUID        NOT NULL,
    reporter_id UUID        NOT NULL REFERENCES users (id),
    reason      VARCHAR(30) NOT NULL,
    status      VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT reports_target_type_check CHECK (target_type IN ('POST', 'COMMENT')),
    CONSTRAINT reports_reason_check      CHECK (reason IN ('SPAM', 'ABUSE', 'ILLEGAL', 'OTHER')),
    CONSTRAINT reports_status_check      CHECK (status IN ('PENDING', 'AGGREGATED', 'RESOLVED'))
);

-- target_id points at either a post or a comment, so it cannot carry a foreign
-- key. The alternative is two nullable columns guarded by a check constraint,
-- which preserves referential integrity but forces every read path to branch on
-- whichever column is populated. Existence of the target is checked in the
-- service layer instead. Recording the trade-off here keeps it a decision
-- rather than an oversight.
CREATE INDEX idx_reports_target ON reports (target_type, target_id);

-- One report per account per target. Repeated reports from the same account
-- carry no additional signal, and they would inflate the aggregate count that
-- drives moderation priority once cases exist.
CREATE UNIQUE INDEX idx_reports_reporter_target
    ON reports (reporter_id, target_type, target_id);
