-- MVP 4 (Q&A): conversations about one snapshot of one repository. A follow-up question can only continue a
-- conversation of the same repository and snapshot, and prior turns reach a prompt only as a bounded summary.
CREATE TABLE conversation (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id uuid         NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    snapshot_id   uuid         NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    -- the first question, shortened (secret-redacted)
    title         varchar(200) NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    updated_at    timestamptz  NOT NULL DEFAULT now()
);
CREATE INDEX idx_conversation_scope ON conversation (repository_id, snapshot_id);

CREATE TABLE conversation_message (
    id              uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    conversation_id uuid         NOT NULL REFERENCES conversation (id) ON DELETE CASCADE,
    seq             integer      NOT NULL,
    -- USER (the question) / ASSISTANT (the answer or the reason there is none)
    role            varchar(16)  NOT NULL,
    -- secret-redacted text
    content         text         NOT NULL,
    -- ASSISTANT: OK / INSUFFICIENT_EVIDENCE / UNAVAILABLE / SKIPPED / RATE_LIMITED / TIMEOUT / INVALID_OUTPUT / ERROR
    status          varchar(32),
    ai_generated    boolean      NOT NULL DEFAULT false,
    -- ASSISTANT: cited sources [{ref, filePath, startLine, endLine}]
    sources         jsonb,
    provider        varchar(50),
    model           varchar(200),
    prompt_version  varchar(100),
    created_at      timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_conversation_message_seq UNIQUE (conversation_id, seq)
);
