-- V5: a record of every call made to a model.
--
-- This table is what makes claims about an engine checkable. Without it,
-- "the model costs about this much" and "it answers in about this long" are
-- recollections; with it they are queries. Failed calls are recorded as
-- carefully as successful ones, because the failure rate is the number that
-- decides whether the fallback is load-bearing or decorative.

CREATE TABLE ai_invocations (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    case_id           UUID        NOT NULL REFERENCES moderation_cases (id),

    engine            VARCHAR(40) NOT NULL,
    -- Null when the call never reached a model, which is the case for a request
    -- rejected by an open circuit.
    model             VARCHAR(80),
    prompt_version    VARCHAR(20) NOT NULL,

    -- SHA-256 of the exact text sent. Two invocations agreeing on case, prompt
    -- version and hash were asked the identical question.
    -- VARCHAR rather than CHAR: CHAR pads to width with spaces, and a hash that
    -- compares unequal to itself because of trailing whitespace is a bad day.
    content_hash      VARCHAR(64) NOT NULL,
    attempt           INT         NOT NULL,

    status            VARCHAR(20) NOT NULL,
    prompt_tokens     INT,
    completion_tokens INT,
    latency_ms        INT         NOT NULL,

    -- The model's answer exactly as it arrived, so a disputed verdict can be
    -- re-read rather than reconstructed. Wrapped in an object when the response
    -- was not itself valid JSON, which is one of the failures worth keeping.
    raw_response      JSONB,
    error             TEXT,

    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ai_invocations_status_check CHECK (status IN (
        'SUCCESS', 'INVALID_RESPONSE', 'TIMEOUT', 'CIRCUIT_OPEN', 'ERROR')),
    CONSTRAINT ai_invocations_attempt_positive CHECK (attempt >= 1)
);

-- Asking a model the same question twice for the same case costs money and
-- produces a second answer that has to be reconciled with the first. The state
-- machine is the primary guard, since a case leaves ANALYSING the moment a
-- verdict is recorded; this constraint is the one that still holds if that ever
-- stops being true.
--
-- Attempt is part of the key because a corrective retry asks the same question
-- again on purpose, and that second call is a distinct event worth its own row.
CREATE UNIQUE INDEX idx_ai_invocations_idempotency
    ON ai_invocations (case_id, prompt_version, content_hash, attempt);

-- Cost and latency are always read per engine over a period.
CREATE INDEX idx_ai_invocations_engine_created
    ON ai_invocations (engine, created_at DESC);
