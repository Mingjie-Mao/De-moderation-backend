-- Production-facing capabilities shared by the API, the Android client and the
-- reviewer console. Existing rows remain valid; every new user-facing feature
-- is nullable or has a safe default so this migration is deployable in place.

ALTER TABLE users ADD COLUMN email VARCHAR(254);
ALTER TABLE users ADD COLUMN display_name VARCHAR(100);
ALTER TABLE users ADD COLUMN bio TEXT;
ALTER TABLE users ADD COLUMN token_version INT NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX idx_users_email_unique
    ON users (lower(email)) WHERE email IS NOT NULL;

CREATE TABLE refresh_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens (user_id, expires_at DESC);

CREATE TABLE password_reset_tokens (
    id         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    token_hash VARCHAR(64) NOT NULL UNIQUE,
    user_id    UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    expires_at TIMESTAMPTZ NOT NULL,
    used_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_password_reset_user ON password_reset_tokens (user_id, expires_at DESC);

-- Fixed-window counters updated through one atomic UPSERT. The key is a SHA-256
-- digest, so raw IP addresses and submitted usernames are not retained here.
CREATE TABLE request_rate_limits (
    bucket_key   VARCHAR(64) PRIMARY KEY,
    window_start TIMESTAMPTZ NOT NULL,
    request_count INT        NOT NULL,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT request_rate_limits_count_positive CHECK (request_count >= 1)
);

CREATE TABLE media_objects (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    owner_id     UUID         NOT NULL REFERENCES users (id),
    content_type VARCHAR(80)  NOT NULL,
    size_bytes   BIGINT       NOT NULL,
    sha256       VARCHAR(64)  NOT NULL,
    storage_key  VARCHAR(255) NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT media_objects_size_positive CHECK (size_bytes > 0)
);

ALTER TABLE posts ADD COLUMN media_id UUID REFERENCES media_objects (id);
ALTER TABLE comments ADD COLUMN media_id UUID REFERENCES media_objects (id);

ALTER TABLE reports ADD COLUMN details VARCHAR(1000);

ALTER TABLE moderation_cases ADD COLUMN assigned_to UUID REFERENCES users (id);
ALTER TABLE moderation_cases ADD COLUMN assigned_at TIMESTAMPTZ;
ALTER TABLE moderation_cases ADD COLUMN review_due_at TIMESTAMPTZ NOT NULL
    DEFAULT (now() + interval '24 hours');
ALTER TABLE moderation_cases ADD CONSTRAINT moderation_cases_assignment_complete
    CHECK ((assigned_to IS NULL) = (assigned_at IS NULL));
CREATE INDEX idx_moderation_cases_review_due
    ON moderation_cases (review_due_at) WHERE status = 'AWAITING_REVIEW';

CREATE TABLE appeals (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id     UUID         NOT NULL REFERENCES moderation_cases (id),
    appellant_id UUID        NOT NULL REFERENCES users (id),
    reason      VARCHAR(2000) NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    response    VARCHAR(2000),
    decided_by UUID         REFERENCES users (id),
    decided_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT appeals_status_check CHECK (status IN ('PENDING', 'UPHELD', 'OVERTURNED')),
    CONSTRAINT appeals_resolution_complete CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR
        (status <> 'PENDING' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    )
);
CREATE UNIQUE INDEX idx_appeals_one_pending_per_case_user
    ON appeals (case_id, appellant_id) WHERE status = 'PENDING';
CREATE INDEX idx_appeals_status_created ON appeals (status, created_at);

CREATE TABLE notifications (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    type       VARCHAR(40)  NOT NULL,
    title      VARCHAR(160) NOT NULL,
    body       VARCHAR(1000) NOT NULL,
    reference_type VARCHAR(30),
    reference_id UUID,
    read_at    TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_user_created
    ON notifications (user_id, created_at DESC);
