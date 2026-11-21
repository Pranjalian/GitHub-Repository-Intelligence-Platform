-- MVP 2 architecture intelligence. Every row carries snapshot_id and run_id: architecture data is
-- commit-scoped like every other analysis result, and deleting a snapshot or run removes it.
--
-- Raw parser facts (code_symbol, dependency_edge, api_endpoint, entity_relationship) are kept apart
-- from inferred descriptions (layer_assignment, pattern_candidate), so the report can always say
-- which statements were read from the code and which were inferred.

-- ── raw parser facts ────────────────────────────────────────────────────────────────────────────

CREATE TABLE code_symbol (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id          uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id               uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    -- CLASS / INTERFACE / ENUM / RECORD / ANNOTATION / METHOD / CONSTRUCTOR / FIELD
    symbol_type          varchar(16)   NOT NULL,
    fully_qualified_name varchar(1024) NOT NULL,
    -- Method/constructor signature (name and parameter types) or field type; null for types
    signature            varchar(2048),
    -- Enclosing type for members and nested types
    parent_symbol_id     uuid REFERENCES code_symbol (id) ON DELETE CASCADE,
    file_path            varchar(1024) NOT NULL,
    start_line           integer,
    end_line             integer,
    -- List of {name, attributes?}: annotation names as written, with literal attribute values only
    annotations          jsonb,
    -- List of modifier keywords (public, static, final, abstract, ...)
    modifiers            jsonb,
    module_path          varchar(1024)
);
CREATE INDEX idx_code_symbol_run ON code_symbol (run_id);
CREATE INDEX idx_code_symbol_snapshot_run_type ON code_symbol (snapshot_id, run_id, symbol_type);
CREATE INDEX idx_code_symbol_parent ON code_symbol (parent_symbol_id);

CREATE TABLE dependency_edge (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id            uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id                 uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    source_symbol_id       uuid          NOT NULL REFERENCES code_symbol (id) ON DELETE CASCADE,
    -- Set only when the target is a symbol of this snapshot
    target_symbol_id       uuid REFERENCES code_symbol (id) ON DELETE CASCADE,
    -- The name as far as it is known (imported name for third-party types); never a guess
    target_unresolved_name varchar(1024),
    -- IMPORTS / INJECTS / CALLS / EXTENDS / IMPLEMENTS / FIELD_TYPE / PARAM_TYPE / RETURN_TYPE
    relationship_type      varchar(16)   NOT NULL,
    -- RESOLVED / UNRESOLVED / AMBIGUOUS
    resolution             varchar(16)   NOT NULL,
    -- {file, line, ...}: where the relationship was read
    evidence               jsonb,
    CONSTRAINT ck_dependency_edge_target CHECK (target_symbol_id IS NOT NULL OR target_unresolved_name IS NOT NULL)
);
CREATE INDEX idx_dependency_edge_run_type ON dependency_edge (run_id, relationship_type);
CREATE INDEX idx_dependency_edge_snapshot ON dependency_edge (snapshot_id);
CREATE INDEX idx_dependency_edge_source ON dependency_edge (source_symbol_id);
CREATE INDEX idx_dependency_edge_target ON dependency_edge (target_symbol_id);

CREATE TABLE api_endpoint (
    id                   uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id          uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id               uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    -- GET / POST / PUT / DELETE / PATCH / HEAD / OPTIONS / ANY
    http_method          varchar(16)   NOT NULL,
    route                varchar(2048) NOT NULL,
    -- False when a path element is not a literal (e.g. a constant from another type); the route keeps its source text
    route_resolved       boolean       NOT NULL DEFAULT true,
    controller_symbol_id uuid          NOT NULL REFERENCES code_symbol (id) ON DELETE CASCADE,
    handler_symbol_id    uuid          NOT NULL REFERENCES code_symbol (id) ON DELETE CASCADE,
    request_type         varchar(1024),
    response_type        varchar(1024),
    file_path            varchar(1024) NOT NULL,
    line_number          integer
);
CREATE INDEX idx_api_endpoint_snapshot_run ON api_endpoint (snapshot_id, run_id);

-- JPA relationships and repository-to-entity mappings, read from annotations and generic arguments.
CREATE TABLE entity_relationship (
    id                     uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id            uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id                 uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    source_symbol_id       uuid          NOT NULL REFERENCES code_symbol (id) ON DELETE CASCADE,
    target_symbol_id       uuid REFERENCES code_symbol (id) ON DELETE CASCADE,
    target_unresolved_name varchar(1024),
    -- ONE_TO_MANY / MANY_TO_ONE / ONE_TO_ONE / MANY_TO_MANY / REPOSITORY_FOR
    relationship_type      varchar(32)   NOT NULL,
    field_name             varchar(255),
    -- Literal annotation attributes such as mappedBy, joinColumn, fetch, cascade; id type for repositories
    attributes             jsonb,
    evidence               jsonb,
    CONSTRAINT ck_entity_relationship_target CHECK (target_symbol_id IS NOT NULL OR target_unresolved_name IS NOT NULL)
);
CREATE INDEX idx_entity_relationship_snapshot_run ON entity_relationship (snapshot_id, run_id);

CREATE TABLE module_dependency (
    id                 uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id        uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id             uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    source_module_path varchar(1024) NOT NULL,
    target_module_path varchar(1024) NOT NULL,
    -- List of {path, line?, signal}
    evidence           jsonb
);
CREATE INDEX idx_module_dependency_snapshot_run ON module_dependency (snapshot_id, run_id);

-- ── inferred descriptions ───────────────────────────────────────────────────────────────────────

CREATE TABLE layer_assignment (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id uuid        NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id      uuid        NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    symbol_id   uuid        NOT NULL REFERENCES code_symbol (id) ON DELETE CASCADE,
    -- CONTROLLER / SERVICE / REPOSITORY / ENTITY / DTO / CONFIGURATION / INFRASTRUCTURE
    layer       varchar(32) NOT NULL,
    confidence  varchar(32) NOT NULL,
    -- List of {path, line?, signal}
    evidence    jsonb,
    -- ANNOTATION / PACKAGE_HEURISTIC / TYPE_HIERARCHY / API_SIGNATURE
    method      varchar(32) NOT NULL
);
CREATE INDEX idx_layer_assignment_snapshot_run ON layer_assignment (snapshot_id, run_id);

CREATE TABLE pattern_candidate (
    id          uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id uuid        NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id      uuid        NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    -- STRATEGY / FACTORY / ADAPTER / REPOSITORY
    pattern     varchar(32) NOT NULL,
    -- List of code_symbol ids taking part in the pattern
    symbol_ids  jsonb       NOT NULL,
    -- POSSIBLE / LIKELY / DETECTED
    confidence  varchar(32) NOT NULL,
    -- {summary, signals: [...]}: why the candidate was proposed
    evidence    jsonb
);
CREATE INDEX idx_pattern_candidate_snapshot_run ON pattern_candidate (snapshot_id, run_id);
