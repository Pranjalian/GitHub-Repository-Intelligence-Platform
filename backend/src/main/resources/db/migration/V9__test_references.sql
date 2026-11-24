-- MVP 5 impact analysis: which production types and methods a test class refers to, read from the
-- test's own syntax tree (a field, parameter or local variable declared with the production type,
-- `new T(...)`, `T.staticCall()`). Raw facts only, like the MVP 2 architecture tables: the impact service
-- resolves them against code_symbol at query time. Tests are never executed, and a reference is not
-- proof that a test exercises a method, only that it names it.
CREATE TABLE test_reference (
    id                uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    snapshot_id       uuid          NOT NULL REFERENCES repository_snapshot (id) ON DELETE CASCADE,
    run_id            uuid          NOT NULL REFERENCES analysis_run (id) ON DELETE CASCADE,
    -- Fully qualified name of the test class and its file
    test_symbol       varchar(500)  NOT NULL,
    test_file_path    varchar(1024) NOT NULL,
    -- The test method that contains the reference; null for class-level ones (fields, imports of a type)
    test_method       varchar(255),
    -- Fully qualified name of a production type of this snapshot
    referenced_type   varchar(1024) NOT NULL,
    -- Method name for calls; null for type uses
    referenced_member varchar(255),
    argument_count    integer,
    -- CALL / STATIC_CALL / INSTANTIATION / TYPE_USE
    reference_kind    varchar(16)   NOT NULL,
    line_number       integer,
    -- DETECTED: the receiver's type is written in the test; LIKELY: resolved through a wildcard import
    confidence        varchar(32)   NOT NULL
);
CREATE INDEX idx_test_reference_run_type ON test_reference (snapshot_id, run_id, referenced_type);
CREATE INDEX idx_test_reference_run ON test_reference (run_id);
