-- Analysis runs and everything derived from a snapshot. Every repository-derived table carries
-- snapshot_id (and run_id where it belongs to one run) so data from different commits never mixes.
-- Deleting a snapshot removes all of its derived data.

CREATE TABLE analysis_run (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id       uuid        NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    status            varchar(32) NOT NULL,
    force_rescan      boolean     NOT NULL DEFAULT false,
    -- Hash of the sorted analyzer id@version list; decides whether a completed run can be reused.
    analyzer_set_hash varchar(128),
    failure_reason    text,
    retry_after       timestamptz,
    requested_at      timestamptz NOT NULL DEFAULT now(),
    started_at        timestamptz,
    completed_at      timestamptz
);
CREATE INDEX idx_analysis_run_snapshot_requested ON analysis_run (snapshot_id, requested_at DESC);

CREATE TABLE analyzer_execution (
    id               uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    run_id           uuid         NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    snapshot_id      uuid         NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    analyzer_id      varchar(100) NOT NULL,
    analyzer_version varchar(50)  NOT NULL,
    category         varchar(32)  NOT NULL,
    status           varchar(32)  NOT NULL,
    started_at       timestamptz,
    completed_at     timestamptz,
    duration_ms      bigint,
    config_summary   jsonb,
    -- Redacted; never a stack trace.
    error_summary    text,
    limitations      jsonb
);
CREATE INDEX idx_analyzer_execution_run ON analyzer_execution (run_id);
CREATE INDEX idx_analyzer_execution_snapshot ON analyzer_execution (snapshot_id);

CREATE TABLE file_record (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id    uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    path           varchar(1024) NOT NULL,
    language       varchar(64),
    -- SOURCE / TEST / CONFIG / RESOURCE / BUILD / DOCS / OTHER
    file_type      varchar(32)   NOT NULL,
    size_bytes     bigint        NOT NULL,
    -- SHA-256 of the content
    content_hash   char(64),
    git_blob_sha   char(40),
    is_binary      boolean       NOT NULL DEFAULT false,
    skipped_reason varchar(255),
    CONSTRAINT uq_file_record_snapshot_path UNIQUE (snapshot_id, path)
);

CREATE TABLE technology_finding (
    id            uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id   uuid         NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id        uuid         NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    technology    varchar(100) NOT NULL,
    -- LANGUAGE / FRAMEWORK / BUILD_TOOL / PACKAGE_MANAGER / DATABASE / TEST_FRAMEWORK / DEPLOYMENT
    kind          varchar(32)  NOT NULL,
    version       varchar(100),
    confidence    varchar(32)  NOT NULL,
    -- List of {path, line?, signal}
    evidence      jsonb,
    detector_id   varchar(100) NOT NULL,
    -- Share of the repository for languages
    share_percent numeric(5, 2)
);
CREATE INDEX idx_technology_finding_snapshot ON technology_finding (snapshot_id);
CREATE INDEX idx_technology_finding_run ON technology_finding (run_id);

CREATE TABLE analyzer_capability (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id       uuid         NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id            uuid         NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    technology        varchar(100) NOT NULL,
    category          varchar(32)  NOT NULL,
    -- NULL means no analyzer exists for this technology and category
    analyzer_id       varchar(100),
    analyzer_version  varchar(50),
    capability_level  varchar(32)  NOT NULL,
    execution_status  varchar(32)  NOT NULL,
    limitation_reason text
);
CREATE INDEX idx_analyzer_capability_snapshot ON analyzer_capability (snapshot_id);
CREATE INDEX idx_analyzer_capability_run ON analyzer_capability (run_id);

CREATE TABLE analysis_finding (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id         uuid         NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id              uuid         NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    analyzer_id         varchar(100) NOT NULL,
    category            varchar(32)  NOT NULL,
    rule_id             varchar(255) NOT NULL,
    severity            varchar(16)  NOT NULL,
    confidence          varchar(32)  NOT NULL,
    title               varchar(500) NOT NULL,
    description         text,
    file_path           varchar(1024),
    module_path         varchar(1024),
    start_line          integer,
    end_line            integer,
    -- Redacted excerpt (length-capped) plus scanner metadata; never a raw secret
    evidence            jsonb,
    analysis_method     varchar(32)  NOT NULL,
    -- Stable hash of rule/category + path + normalized location; dedupes across analyzers
    fingerprint         varchar(128) NOT NULL,
    suggested_next_step text
);
CREATE INDEX idx_analysis_finding_run ON analysis_finding (run_id);
CREATE INDEX idx_analysis_finding_snapshot_category_severity ON analysis_finding (snapshot_id, category, severity);
CREATE INDEX idx_analysis_finding_snapshot_fingerprint ON analysis_finding (snapshot_id, fingerprint);

CREATE TABLE dependency (
    id                  uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id         uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id              uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    analyzer_id         varchar(100)  NOT NULL,
    -- MAVEN / NPM / ...
    ecosystem           varchar(32)   NOT NULL,
    manifest_path       varchar(1024) NOT NULL,
    module_path         varchar(1024),
    -- Maven groupId; npm scope
    namespace           varchar(255),
    name                varchar(255)  NOT NULL,
    declared_version    varchar(255),
    resolved_version    varchar(255),
    -- DECLARED / PROPERTY / MANAGED_LOCAL / MANAGED_REMOTE_UNRESOLVED / UNRESOLVED
    version_source      varchar(32)   NOT NULL,
    scope               varchar(64),
    is_direct           boolean       NOT NULL DEFAULT true,
    is_management_entry boolean       NOT NULL DEFAULT false,
    -- NOT_CHECKED / NOT_CHECKABLE / NO_KNOWN_VULNERABILITIES / VULNERABLE
    vulnerability_status varchar(32)  NOT NULL DEFAULT 'NOT_CHECKED',
    vulnerability_ids   jsonb
);
CREATE INDEX idx_dependency_snapshot ON dependency (snapshot_id);
CREATE INDEX idx_dependency_run ON dependency (run_id);

CREATE TABLE module_record (
    id                      uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id             uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id                  uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    module_name             varchar(255)  NOT NULL,
    path                    varchar(1024) NOT NULL,
    build_file_path         varchar(1024),
    ecosystem               varchar(32),
    detected_responsibility varchar(255),
    evidence                jsonb
);
CREATE INDEX idx_module_record_snapshot ON module_record (snapshot_id);
CREATE INDEX idx_module_record_run ON module_record (run_id);

CREATE TABLE test_record (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id       uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id            uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    test_file_path    varchar(1024) NOT NULL,
    -- Fully qualified name of the test class
    test_symbol       varchar(500),
    test_method_count integer,
    framework         varchar(100),
    production_symbol varchar(500),
    -- NAMING_CONVENTION / ...
    mapping_method    varchar(32),
    confidence        varchar(32),
    evidence          jsonb
);
CREATE INDEX idx_test_record_snapshot ON test_record (snapshot_id);
CREATE INDEX idx_test_record_run ON test_record (run_id);

-- Populated only from a real coverage report; never estimated.
CREATE TABLE coverage_record (
    id             uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id    uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id         uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    report_path    varchar(1024) NOT NULL,
    -- CLASS / PACKAGE / BUNDLE
    scope          varchar(16)   NOT NULL,
    name           varchar(500)  NOT NULL,
    line_covered   integer,
    line_missed    integer,
    branch_covered integer,
    branch_missed  integer
);
CREATE INDEX idx_coverage_record_snapshot ON coverage_record (snapshot_id);
CREATE INDEX idx_coverage_record_run ON coverage_record (run_id);
