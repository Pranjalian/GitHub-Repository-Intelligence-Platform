# MVP 1 Completion Report: Deterministic Repository Analysis

**Date:** 2026-11-20
**Plan:** [implementationPlan.md](implementationPlan.md), Steps 1.1 to 1.19
**Result:** All steps implemented. Two items from the plan were **not** done: the manual smoke test on real public repositories and the Semgrep-CLI integration test (see section 8). Step 1.19 (Docker Compose run, added 2026-11-21) is implemented and verified on the development machine (see section 5a).

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
- **Docker for running only.** `docker compose up --build` runs PostgreSQL, the backend (with a pinned Semgrep CLI) and the nginx-served frontend, non-root and bound to 127.0.0.1 (Step 1.19). Build and tests need no Docker: embedded PostgreSQL for tests, as in MVP 0.

## 3. Files and modules created

- Backend packages: `repository` (submission, GitHub client, ingestion, workspace), `analysis` (`spi`, `registry`, `capability`, `orchestration`, `generic`, `packs.java`, `packs.javascript`, `vulnerability`, `tools`), `report`, plus Flyway `V2` to `V4`.
- Resources: `technology-signatures.yml`, `rules/secrets.yml`, the PMD ruleset `rulesets/repointel-java-quality.xml`, `semgrep-rules/java/repointel-java.yml`.
- Frontend: pages (Add, Dashboard, Overview), progress, report, findings, dependency and test components, hooks and API client.
- Test fixtures under `backend/src/test/resources/fixtures/` (`spring-boot-simple`, `maven-multi-module`, `react-typescript`); `security-smells` and the malicious archive are built in test code.

## 4. Run commands

```sh
# with Docker (repo root)        cp .env.example .env && docker compose up --build
# backend (from backend/)        ./mvnw spring-boot:test-run      # embedded PostgreSQL, no install
# frontend (from frontend/)      pnpm install && pnpm dev
```

Files for the Docker run: `compose.yaml`, `backend/Dockerfile`, `frontend/Dockerfile`, `frontend/nginx.conf`, `.dockerignore` files, plus a `Docker images` CI job.

## 5. Test commands

```sh
./mvnw -B clean verify                                                  # backend, includes the Spotless check
pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test --run && pnpm build   # frontend
```

## 5a. Docker Compose verification (Step 1.19, 2026-11-21)

Run on the development machine (Windows 11, Docker Desktop on WSL2) with `docker compose up --build`, no `.env` and no `GITHUB_TOKEN`. CI also built both images (`Docker images` job green on `17069c4`).

| Check | Result |
|---|---|
| Services | `postgres`, `backend`, `frontend` all healthy; ports bound to `127.0.0.1` only |
| Migrations | Flyway applied V1 to V4 on PostgreSQL 16.16 in the container |
| Backend health | `/actuator/health` returns `UP` |
| UI | `http://localhost:5173` returns 200; a deep link returns 200 (SPA fallback) |
| API via nginx | `/api/v1/repositories` through port 5173 returns 200 |
| Headers | CSP, `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy` present |
| Non-root | backend runs as `app` (uid 999), frontend as `nginx` (uid 101) |
| Semgrep | 1.186.0 available in the backend image |
| End to end | `spring-projects/spring-petclinic` (commit `500158f`) analyzed through the UI proxy: run `COMPLETED` in about 19 s; 10 analyzers completed, including PMD (about 11 s) and **Semgrep (about 14 s, first real Semgrep run)**; vulnerability lookup `NOT_CONFIGURED` as expected |
| Persistence | after `docker compose down` and `up`, both analyzed repositories are still listed |

Not checked: `docker compose down -v` data removal, reachability from another machine on the network, and token redaction in logs/image history with a real `GITHUB_TOKEN` (none was set).

## 6. External tools

None are required. Optional: Semgrep CLI (security rules; included in the Docker backend image), outbound access to OSV.dev (vulnerability lookup), a `GITHUB_TOKEN` (higher API limits).

## 7. Security considerations

URL parsing is strict and the backend never fetches a user-supplied URL (SSRF tests assert zero outbound requests). Archives are extracted safely; nothing is written outside the workspace. Repository text is untrusted: it is rendered as plain text, and prompt-injection text in a README is just data. No repository code is executed. There is no authentication: do not expose the application publicly.

## 8. Not implemented or not verified

- **Manual smoke test**: partly done. One real Spring Boot repository (`spring-petclinic`) was analyzed end to end in Docker without a token (section 5a); its findings were not reviewed in detail. A real React repository and a run with a `GITHUB_TOKEN` are still open.
- **Semgrep integration test** (`@Tag("semgrep")`): not written. Semgrep now runs for real in the Docker image (section 5a), but there is still no automated test against the real CLI. Parsing, unavailability and process-kill behaviour are tested with fixtures.
- **Demo recording** for the slice checkpoint: not recorded.
- **Duplication (CPD)** is not enabled; stated as a limitation, not shown as a matrix row.
- **Outdated-dependency detection**, transitive dependencies, lockfile parsing, test execution: out of scope for MVP 1.
- Rate-limit and job-cap behaviour is covered by the repository API tests, not repeated end to end.

## 9. Known limitations

See the README section "Known limitations".

## 10. Recommended next steps

Finish the smoke test with a token and a React repository; add the tagged Semgrep test and run it in CI inside the backend image (Semgrep is already there); consider OWASP Dependency-Check as a second vulnerability source; then start MVP 2 (architecture intelligence), reusing the JavaParser setup.
