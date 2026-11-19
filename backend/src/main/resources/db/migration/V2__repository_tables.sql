-- Repository identity and per-commit snapshots.

CREATE TABLE repository (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    host           varchar(255) NOT NULL DEFAULT 'github.com',
    owner          varchar(100) NOT NULL,
    name           varchar(255) NOT NULL,
    canonical_url  varchar(500) NOT NULL,
    default_branch varchar(255),
    visibility     varchar(32),
    github_repo_id bigint,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    updated_at     timestamptz  NOT NULL DEFAULT now()
);

-- GitHub owner and repository names are case-insensitive.
CREATE UNIQUE INDEX uq_repository_identity ON repository (host, lower(owner), lower(name));

CREATE TABLE repository_snapshot (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    repository_id    uuid        NOT NULL REFERENCES repository (id) ON DELETE CASCADE,
    commit_sha       char(40)    NOT NULL CHECK (commit_sha ~ '^[0-9a-f]{40}$'),
    branch           varchar(255),
    ingestion_status varchar(32) NOT NULL DEFAULT 'PENDING',
    ingestion_error  text,
    file_count       integer,
    total_bytes      bigint,
    ingested_at      timestamptz,
    created_at       timestamptz NOT NULL DEFAULT now(),
    -- One snapshot per commit; the SHA is resolved before the snapshot is created.
    CONSTRAINT uq_snapshot_repository_commit UNIQUE (repository_id, commit_sha)
);
