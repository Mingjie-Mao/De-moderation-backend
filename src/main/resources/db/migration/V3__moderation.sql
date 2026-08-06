-- V3: the moderation workflow.
--
-- Reports were already being collected; this is what turns them into work an
-- administrator can act on, and the record of what was done.

CREATE TABLE moderation_rules (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    code       VARCHAR(30)  NOT NULL UNIQUE,
    title      VARCHAR(120) NOT NULL,
    body       TEXT         NOT NULL,
    severity   VARCHAR(20)  NOT NULL,
    -- Terms the keyword engine matches on. Kept beside the rule so that a rule
    -- and the signal that fires it cannot drift apart.
    terms      JSONB        NOT NULL DEFAULT '[]'::jsonb,
    active     BOOLEAN      NOT NULL DEFAULT true,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT moderation_rules_severity_check CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH'))
);

CREATE TABLE moderation_cases (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    target_type  VARCHAR(20) NOT NULL,
    target_id    UUID        NOT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    report_count INT         NOT NULL DEFAULT 0,

    -- Filled in by whichever engine analysed the case. Nullable because a case
    -- exists from the moment it is reported, before anything has judged it.
    engine       VARCHAR(40),
    decision     VARCHAR(20),
    confidence   NUMERIC(4, 3),
    rationale    TEXT,
    rule_codes   JSONB       NOT NULL DEFAULT '[]'::jsonb,
    analysed_at  TIMESTAMPTZ,

    -- Filled in by the administrator who closed it.
    decided_by   UUID        REFERENCES users (id),
    decided_at   TIMESTAMPTZ,
    final_action VARCHAR(20),

    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT moderation_cases_target_type_check
        CHECK (target_type IN ('POST', 'COMMENT')),
    CONSTRAINT moderation_cases_status_check
        CHECK (status IN ('QUEUED', 'ANALYSING', 'AWAITING_REVIEW', 'RESOLVED')),
    CONSTRAINT moderation_cases_decision_check
        CHECK (decision IS NULL OR decision IN ('ALLOW', 'REMOVE', 'ESCALATE')),
    CONSTRAINT moderation_cases_final_action_check
        CHECK (final_action IS NULL OR final_action IN ('NONE', 'HIDE', 'DELETE', 'BAN')),
    CONSTRAINT moderation_cases_confidence_range
        CHECK (confidence IS NULL OR (confidence >= 0 AND confidence <= 1)),
    -- A case is only closed once, and only with an outcome and an author.
    CONSTRAINT moderation_cases_resolution_complete
        CHECK ((status = 'RESOLVED') = (final_action IS NOT NULL AND decided_by IS NOT NULL))
);

-- At most one open case per piece of content. This is the constraint the whole
-- aggregation design rests on: several people reporting the same post must
-- produce one case and therefore one analysis, and two of their requests can
-- arrive at the same instant. A check in application code cannot promise that,
-- because two transactions can both read "no open case" before either inserts.
--
-- Partial, so that a target reported again after an earlier case was resolved
-- gets a fresh case rather than colliding with the closed one.
CREATE UNIQUE INDEX idx_moderation_cases_open_target
    ON moderation_cases (target_type, target_id)
    WHERE status <> 'RESOLVED';

-- The queue read: oldest queued cases first.
CREATE INDEX idx_moderation_cases_queue
    ON moderation_cases (status, created_at)
    WHERE status IN ('QUEUED', 'ANALYSING');

-- Reports now belong to a case. Nullable for rows written before this migration.
ALTER TABLE reports ADD COLUMN case_id UUID REFERENCES moderation_cases (id);
CREATE INDEX idx_reports_case ON reports (case_id);

CREATE TABLE audit_log (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    actor_type  VARCHAR(20) NOT NULL,
    -- Null for ENGINE and SYSTEM, which are not accounts. No foreign key: an
    -- audit entry has to outlive the account it describes, or the log stops
    -- being evidence the moment someone is deleted.
    actor_id    UUID,
    action      VARCHAR(60) NOT NULL,
    target_type VARCHAR(20) NOT NULL,
    target_id   UUID        NOT NULL,
    payload     JSONB       NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT audit_log_actor_type_check
        CHECK (actor_type IN ('USER', 'ENGINE', 'ADMIN', 'SYSTEM'))
);

-- Reading the history of one object is the only access pattern that matters
-- here; the log is append-only and never updated.
CREATE INDEX idx_audit_log_target ON audit_log (target_type, target_id, created_at DESC);

-- Starter rule set. Unlike development accounts, these are real configuration:
-- the system has nothing to moderate against without them, and they are meant
-- to exist in every environment.
INSERT INTO moderation_rules (code, title, body, severity, terms) VALUES
    ('SPAM', 'Unsolicited advertising',
     'Commercial promotion, referral links or bulk-posted offers.', 'LOW',
     '["free money","click here","buy now","limited offer","代刷","加微信","兼职日结"]'),
    ('ABUSE', 'Harassment or personal attacks',
     'Insults, threats or targeted harassment of another person.', 'HIGH',
     '["idiot","kill yourself","stupid bitch","滚出去","傻逼","去死"]'),
    ('ILLEGAL', 'Illegal goods or services',
     'Trade in controlled substances, weapons, or academic fraud services.', 'HIGH',
     '["buy weed","fake id","essay for sale","代写论文","出售答案","枪支"]');
