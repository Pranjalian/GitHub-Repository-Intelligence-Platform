-- MVP 3 AI insights: usage accounting and the response cache.
--
-- ai_usage_record has one row per provider call and per cache hit, including failures. Token counts
-- are null unless the provider reported them (usage_source REPORTED); the local estimate is kept in
-- separate columns and labelled ESTIMATED. Cost is null unless pricing is configured. Rows outlive the
-- snapshot they were made for (the references are set to null), so usage history is not lost when a
-- repository is deleted.

CREATE TABLE ai_usage_record (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    created_at              timestamptz   NOT NULL DEFAULT now(),
    provider                varchar(50)   NOT NULL,
    model                   varchar(200),
    -- explain-class / explain-method / explain-finding / suggest-refactoring / summarize-module / tech-debt-summary
    feature                 varchar(50)   NOT NULL,
    prompt_version          varchar(100)  NOT NULL,
    repository_id           uuid REFERENCES repository (id) ON DELETE SET NULL,
    snapshot_id             uuid REFERENCES repository_snapshot (id) ON DELETE SET NULL,
    -- the analysis run whose call budget the call counts against
    run_id                  uuid REFERENCES analysis_run (id) ON DELETE SET NULL,
    -- OK / RATE_LIMITED / TIMEOUT / INVALID_OUTPUT / ERROR / UNAVAILABLE
    status                  varchar(32)   NOT NULL,
    cache_hit               boolean       NOT NULL DEFAULT false,
    -- provider-reported counts; null when not reported
    input_tokens            integer,
    output_tokens           integer,
    -- local characters-per-token estimate, always computed for the prompt
    estimated_input_tokens  integer,
    estimated_output_tokens integer,
    -- REPORTED / ESTIMATED / UNKNOWN
    usage_source            varchar(16)   NOT NULL,
    -- only when pricing is configured; never invented
    estimated_cost          numeric(14, 6),
    -- REPORTED_TOKENS / ESTIMATED_TOKENS; null when there is no cost
    cost_source             varchar(32),
    currency                varchar(8),
    latency_ms              bigint,
    -- why a call failed, in words; never a prompt, key or response body
    detail                  varchar(500)
);
CREATE INDEX idx_ai_usage_created ON ai_usage_record (created_at DESC);
CREATE INDEX idx_ai_usage_run ON ai_usage_record (run_id, cache_hit);
CREATE INDEX idx_ai_usage_snapshot ON ai_usage_record (snapshot_id);

-- Validated answers, keyed by sha256 of {provider, model, prompt version, commit SHA, prompt hash}.
-- The prompt is hashed after secret scrubbing, so the key never depends on a secret value.
CREATE TABLE ai_cache (
    cache_key      varchar(64)  PRIMARY KEY,
    provider       varchar(50)  NOT NULL,
    model          varchar(200),
    prompt_version varchar(100) NOT NULL,
    feature        varchar(50)  NOT NULL,
    commit_sha     varchar(64)  NOT NULL,
    content        text         NOT NULL,
    structured     jsonb,
    answered_model varchar(200),
    created_at     timestamptz  NOT NULL DEFAULT now(),
    expires_at     timestamptz  NOT NULL
);
CREATE INDEX idx_ai_cache_expires ON ai_cache (expires_at);
