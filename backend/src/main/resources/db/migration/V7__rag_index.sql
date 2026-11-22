-- MVP 4 (RAG): code chunks with a keyword index, embeddings, and the index status of a snapshot.
--
-- pgvector is optional. It is created when the server has it installed (the Docker Compose database
-- does) and the role may create it; otherwise this migration still succeeds and semantic search ranks
-- the stored vectors in the application instead (the embedded PostgreSQL used by the tests has no
-- pgvector). The schema is the same either way: vectors are stored as real[] and cast to vector in
-- queries when the extension exists, so nothing has to be migrated when pgvector is added later.
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'vector') THEN
        BEGIN
            CREATE EXTENSION IF NOT EXISTS vector;
        EXCEPTION WHEN insufficient_privilege THEN
            RAISE NOTICE 'pgvector is installed but this role cannot create the extension; vectors are ranked in the application.';
        END;
    END IF;
END
$$;

-- One index job per snapshot (re-indexing replaces the row's numbers).
CREATE TABLE snapshot_index (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id             uuid         NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    repository_id           uuid         NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    -- QUEUED / INDEXING / COMPLETED / COMPLETED_WITH_WARNINGS / FAILED
    status                  varchar(32)  NOT NULL,
    -- NOT_CONFIGURED / UNAVAILABLE / COMPLETED / PARTIAL / FAILED / PENDING: embeddings are optional
    embedding_status        varchar(32)  NOT NULL,
    embedding_detail        varchar(1000),
    embedding_provider      varchar(50),
    embedding_model         varchar(200),
    embedding_model_version varchar(200),
    embedding_dimensions    integer,
    files_indexed           integer,
    files_skipped           integer,
    chunk_count             integer,
    embedded_chunks         integer,
    -- embeddings found by content hash (no provider call) vs newly requested in this job
    reused_embeddings       integer,
    new_embeddings          integer,
    detail                  varchar(1000),
    warnings                jsonb,
    requested_at            timestamptz  NOT NULL DEFAULT now(),
    started_at              timestamptz,
    completed_at            timestamptz,
    CONSTRAINT uq_snapshot_index_snapshot UNIQUE (snapshot_id)
);
CREATE INDEX idx_snapshot_index_status ON snapshot_index (status);

-- Meaningful units of one snapshot's files: a Java class header (with fields) or method, a YAML or
-- properties section, a Markdown section, or a labelled line window for other languages. Content is
-- secret-scrubbed before it is stored. Every query filters by snapshot_id (and repository_id).
CREATE TABLE code_chunk (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id   uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    repository_id uuid          NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    file_path     varchar(1024) NOT NULL,
    language      varchar(64),
    -- SOURCE / TEST / CONFIG / BUILD / DOCS (file_record classification)
    file_type     varchar(16),
    module_path   varchar(1024),
    class_name    varchar(1024),
    method_name   varchar(512),
    -- CLASS / METHOD / CONFIG_SECTION / DOC_SECTION / LINE_WINDOW
    chunk_type    varchar(32)   NOT NULL,
    -- e.g. the YAML key or Markdown heading of a section
    section       varchar(512),
    start_line    integer       NOT NULL,
    end_line      integer       NOT NULL,
    content       text          NOT NULL,
    -- sha256 (hex) of the text that is embedded (metadata header + content): the embedding cache key
    content_hash  char(64)      NOT NULL,
    -- lines withheld or masked because a possible secret was detected
    redacted      boolean       NOT NULL DEFAULT false,
    -- Keyword index. Identifiers are indexed whole and split at camelCase/underscores, so
    -- "OwnerRepository" matches "OwnerRepository", "owner" and "repository". Symbol names weigh most.
    tsv           tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple'::regconfig,
            regexp_replace(regexp_replace(coalesce(class_name, '') || ' ' || coalesce(method_name, '') || ' ' || coalesce(section, ''),
                '([a-z0-9])([A-Z])', '\1 \2', 'g'), '[^A-Za-z0-9]+', ' ', 'g')
            || ' ' || regexp_replace(coalesce(class_name, '') || ' ' || coalesce(method_name, ''), '[^A-Za-z0-9]+', ' ', 'g')), 'A')
        || setweight(to_tsvector('simple'::regconfig, regexp_replace(file_path, '[^A-Za-z0-9]+', ' ', 'g')), 'B')
        || setweight(to_tsvector('simple'::regconfig,
            regexp_replace(regexp_replace(content, '([a-z0-9])([A-Z])', '\1 \2', 'g'), '[^A-Za-z0-9]+', ' ', 'g')
            || ' ' || regexp_replace(content, '[^A-Za-z0-9]+', ' ', 'g')), 'C')
    ) STORED,
    CONSTRAINT ck_code_chunk_lines CHECK (start_line >= 1 AND end_line >= start_line)
);
CREATE INDEX idx_code_chunk_snapshot ON code_chunk (snapshot_id, repository_id);
CREATE INDEX idx_code_chunk_snapshot_hash ON code_chunk (snapshot_id, content_hash);
CREATE INDEX idx_code_chunk_hash ON code_chunk (content_hash);
CREATE INDEX idx_code_chunk_tsv ON code_chunk USING gin (tsv);

-- Embeddings keyed by content hash, model and model version, so unchanged chunks are reused across
-- commits and a different model (or a new version of it) gets its own rows without touching the old
-- ones. Vectors of different models are never compared: every query names the model and version.
-- One table for all models: the dimension is a column and checked per row (pgvector casts real[] of
-- any length; an approximate index per model could be added later as a partial expression index).
CREATE TABLE chunk_embedding (
    content_hash            char(64)     NOT NULL,
    embedding_model         varchar(200) NOT NULL,
    embedding_model_version varchar(200) NOT NULL,
    dimensions              integer      NOT NULL CHECK (dimensions BETWEEN 1 AND 4096),
    embedding               real[]       NOT NULL,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    PRIMARY KEY (content_hash, embedding_model, embedding_model_version),
    CONSTRAINT ck_chunk_embedding_dimensions CHECK (cardinality(embedding) = dimensions)
);
