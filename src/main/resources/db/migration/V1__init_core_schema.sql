-- V1: core forum schema (users, posts, comments).
-- Moderation tables (reports, moderation_cases, rules, audit_log, ai_invocations)
-- arrive in a later migration so the two concerns stay separately reviewable.

CREATE TABLE users (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(50)  NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL DEFAULT 'MEMBER',
    status        VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT users_role_check   CHECK (role   IN ('MEMBER', 'ADMIN')),
    CONSTRAINT users_status_check CHECK (status IN ('ACTIVE', 'SUSPENDED', 'BANNED'))
);

CREATE TABLE posts (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    forum_key  VARCHAR(50)  NOT NULL,
    author_id  UUID         NOT NULL REFERENCES users (id),
    title      VARCHAR(200) NOT NULL,
    body       TEXT         NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at TIMESTAMPTZ
);

-- The feed query is always "latest live posts in one forum". A partial index
-- keeps soft-deleted rows out of the index entirely.
CREATE INDEX idx_posts_forum_created
    ON posts (forum_key, created_at DESC)
    WHERE deleted_at IS NULL;

CREATE TABLE comments (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    post_id           UUID        NOT NULL REFERENCES posts (id) ON DELETE CASCADE,
    parent_comment_id UUID        REFERENCES comments (id) ON DELETE CASCADE,
    author_id         UUID        NOT NULL REFERENCES users (id),
    body              TEXT        NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    deleted_at        TIMESTAMPTZ
);

-- Threaded replies are loaded per post and then assembled in memory, so the
-- post index carries the main read path and the parent index supports subtree
-- lookups.
CREATE INDEX idx_comments_post   ON comments (post_id, created_at);
CREATE INDEX idx_comments_parent ON comments (parent_comment_id);
