# MVP 1 Completion Report: Deterministic Repository Analysis

**Date:** 2026-11-20
**Plan:** [implementationPlan.md](implementationPlan.md), Steps 1.1 to 1.18
**Result:** All steps implemented. Two items from the plan were **not** done: the manual smoke test on real public repositories and the Semgrep-CLI integration test (see section 8).

## 1. Features implemented

| Area | Delivered |
|---|---|
| Submission | `POST /api/v1/repositories/analyze` resolves the repository and the exact commit SHA before analysis, reuses completed runs for the same commit and analyzer set, supports `forceRescan`, rate limits and a job cap |
| Ingestion | GitHub tarball download, safe extraction (no traversal, links, devices; bomb and size limits), per-snapshot workspace removed after the run |
| Orchestration | Fault-isolated detectors and analyzers, per-run time budget, cancellation, startup recovery, run status API |
| Generic analysis | Language distribution, manifest-driven technology detection (rules in YAML), structure (modules, entry points, size statistics), secret scanner, test-file discovery |
| Java pack | Spring Boot/test/database/Java-version detection (JavaParser), Maven dependency inventory (own POM reader, nothing downloaded), PMD code-quality findings, Semgrep rules (when installed), test inventory and JaCoCo coverage from committed reports |
| npm | Direct dependency inventory from every `package.json` |
| Vulnerabilities | Optional OSV.dev lookup, off by default |
| Report API | Consolidated report, findings (filters, sort, paging), dependencies, tests, coverage; every endpoint scoped to one snapshot |
| Frontend | Add repository, progress with cancel, repository list, overview with snapshot selector, tabs for Dependencies, Code Quality, Security, Tests & Coverage, finding detail |

## 2. Architecture and key decisions

- **Analyzers never persist.** They return plain results; only the orchestrator writes to the database, redacting and fingerprinting findings first.
- **Honest statuses everywhere.** A category nobody analyzed is `NOT_SUPPORTED`; its counts are `null`, never 0. Disabled vulnerability lookup shows dependencies as *Not checked*. Without a JaCoCo report coverage is *unavailable*, never 0%.
- **Data-driven rules.** Technology signatures (`technology-signatures.yml`), secret rules (`rules/secrets.yml`), the PMD ruleset and vendored Semgrep rules are files; adding a rule needs no Java change.
- **Maven without resolution.** POMs are read with `maven-model` only. Versions from remote parents/BOMs are labelled *managed ... (not resolved)*; BOM imports are recorded, not fetched. A new `dependency.note` column carries this wording.
- **Vulnerability lookup inside the dependency analyzers** (analyzers cannot update each other's rows); a small marker analyzer makes the capability visible as *not configured* when off.
- **External tools run as processes with a minimal environment**, capped output and process-tree kill on cancel/deadline (Semgrep). PMD and JavaParser run in-process and only parse.
- **Secrets are never stored, logged or returned.** The scanner redacts every secret on a line before building evidence, and the result writer redacts again. Test secrets are assembled from fragments so no token-shaped literal is committed.
- **Packs plug in.** A test fails the build if core code imports `analysis.packs.*` or if pack code touches persistence types.
- **No Docker.** Embedded PostgreSQL for tests, as in MVP 0.

## 3. Files and modules created

- Backend packages: `repository` (submission, GitHub client, ingestion, workspace), `analysis` (`spi`, `registry`, `capability`, `orchestration`, `generic`, `packs.java`, `packs.javascript`, `vulnerability`, `tools`), `report`, plus Flyway `V2` to `V4`.
- Resources: `technology-signatures.yml`, `rules/secrets.yml`, the PMD ruleset `rulesets/repointel-java-quality.xml`, `semgrep-rules/java/repointel-java.yml`.
- Frontend: pages (Add, Dashboard, Overview), progress, report, findings, dependency and test components, hooks and API client.
- Test fixtures under `backend/src/test/resources/fixtures/` (`spring-boot-simple`, `maven-multi-module`, `react-typescript`); `security-smells` and the malicious archive are built in test code.

## 4. Run commands

```sh
# backend (from backend/)        ./mvnw spring-boot:test-run      # embedded PostgreSQL, no install
# frontend (from frontend/)      pnpm install && pnpm dev
```

## 5. Test commands

```sh
./mvnw -B clean verify                                                  # backend, includes the Spotless check
pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test --run && pnpm build   # frontend
```

## 6. External tools

None are required. Optional: Semgrep CLI (security rules), outbound access to OSV.dev (vulnerability lookup), a `GITHUB_TOKEN` (higher API limits).

## 7. Security considerations

URL parsing is strict and the backend never fetches a user-supplied URL (SSRF tests assert zero outbound requests). Archives are extracted safely; nothing is written outside the workspace. Repository text is untrusted: it is rendered as plain text, and prompt-injection text in a README is just data. No repository code is executed. There is no authentication: do not expose the application publicly.

## 8. Not implemented or not verified

- **Manual smoke test** on one real Spring Boot and one real React repository with a `GITHUB_TOKEN`: not run (needs network and a token). Recommended before relying on the results.
- **Semgrep integration test** (`@Tag("semgrep")`): not written; Semgrep was not installed. Parsing, unavailability and process-kill behaviour are tested with fixtures.
- **Demo recording** for the slice checkpoint: not recorded.
- **Duplication (CPD)** is not enabled; stated as a limitation, not shown as a matrix row.
- **Outdated-dependency detection**, transitive dependencies, lockfile parsing, test execution: out of scope for MVP 1.
- Rate-limit and job-cap behaviour is covered by the repository API tests, not repeated end to end.

## 9. Known limitations

See the README section "Known limitations".

## 10. Recommended next steps

Run the smoke test with a token; install Semgrep and add the tagged test and a CI job; consider OWASP Dependency-Check as a second vulnerability source; then start MVP 2 (architecture intelligence), reusing the JavaParser setup.
