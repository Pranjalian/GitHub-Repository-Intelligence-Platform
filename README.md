# GitHub Repository Intelligence Platform

Evidence-backed analysis of public GitHub repositories. Submit a public repository URL and get a commit-scoped report: technologies, structure, dependencies, code quality, secrets and security rules, tests and (real) coverage, plus an honest capability matrix that says what the platform could not analyze and why. It is deterministic: **no AI and no AI key are needed**, and no code from the analyzed repository is ever executed.

See [docs/](docs/) for the problem statement, architecture, implementation plan and the [MVP 1 completion report](docs/mvp1-completion-report.md).

> Local single-user tool. There is no authentication yet: do not expose it to the public internet.

## Prerequisites

- JDK 25 (`JAVA_HOME` must point to it; Maven uses `JAVA_HOME`, not `PATH`)
- Optional: native PostgreSQL 16+ if you want data to persist between runs
- **No Docker required** for building, testing or running

Maven itself is not needed; use the wrapper (`mvnw` / `mvnw.cmd`) in `backend/`.

## How to analyze a repository

1. Start the backend and the frontend (below), open http://localhost:5173 and choose **Add Repository**.
2. Paste a public GitHub URL (for example `https://github.com/spring-projects/spring-petclinic`, optionally `/tree/<branch>`) and press **Analyze**. Tick *Force full rescan* to re-analyze a commit that was already analyzed.
3. Watch the progress page, then read the report: Overview (technologies, structure, capability matrix, limitations), Dependencies, Code Quality, Security, Tests & Coverage. Use the snapshot selector to switch between analyzed commits.

API documentation: http://localhost:8080/swagger-ui.html. Flow: `POST /api/v1/repositories/analyze` returns a run id; poll `GET /api/v1/analysis-runs/{runId}`; read `GET /api/v1/repositories/{repositoryId}/reports/{snapshotId}` and the findings, dependencies, tests and coverage endpoints under `/snapshots/{snapshotId}/`.

## GitHub token

Set `GITHUB_TOKEN` to a **fine-grained token with read-only access to public repositories** (no scopes needed for public data). Without it GitHub allows only 60 API requests per hour per IP; with it, 5,000. The token is sent only to GitHub (`api.github.com`, `codeload.github.com`), never logged, and redacted from logs and reports.

## Optional tools and features

- **Semgrep** (security rules for Java). Not required. If the `semgrep` CLI is not found the analyzer is reported as *Unavailable* with the reason, and everything else still runs. Install natively (`pip install semgrep`; on Windows run it through WSL if native support is insufficient) or point to it with `analysis.analyzers.semgrep-security-analyzer.options.command`. Rules are vendored in `backend/src/main/resources/semgrep-rules/`; nothing is downloaded at runtime and metrics are off.
- **OSV.dev vulnerability lookup** for dependencies. Off by default (`analysis.vulnerabilities.osv.enabled=false`) because it needs outbound network access. When off, every dependency shows *Not checked*, which never means "no known vulnerabilities". When on, only package names and versions are sent, and only for dependencies with a concrete resolved version.

## Configuration reference

All keys live in `backend/src/main/resources/application.yml` and can be overridden with environment variables or `-D` properties.

| Area | Keys |
|---|---|
| Limits | `repository.ingestion.max-repository-size-mb`, `max-tarball-size-mb`, `max-extracted-size-mb`, `max-file-size-kb`, `max-file-count`, `excluded-directories` |
| Workspace | `repository.workspace.root` (`WORKSPACE_ROOT`), `retain-after-analysis`, `sweep-interval` |
| Jobs and rate limits | `analysis.jobs.max-concurrent-jobs`, `queue-capacity`, `max-analysis-duration`; `repository.submission.max-pending-jobs`, `rate-limit.capacity`, `rate-limit.refill-period` |
| Results | `analysis.results.max-excerpt-length`, `max-findings-per-analyzer` |
| GitHub | `github.token`, `api-base-url`, `allowed-hosts`, timeouts, `retry.*` |
| Analyzer toggles | `analysis.analyzers.<analyzer-id>.enabled` and `.options.*` (for example `java-code-analyzer` option `include-tests`) |
| Vulnerability lookup | `analysis.vulnerabilities.osv.*` |

## Known limitations

- Only **direct** dependencies are inventoried; transitive dependencies are not resolved.
- Versions supplied by **remote parents or BOMs** (for example `spring-boot-starter-parent`) are not resolved and are labelled as such.
- Tests are **never executed**. Coverage appears only when a JaCoCo report is committed in the repository and may be stale; otherwise it says "unavailable", never 0%.
- "Outdated dependency" detection is not implemented.
- Deep analysis exists for Java/Maven; other stacks get generic analysis (structure, secrets, test files, npm dependencies) and an honest "unsupported" matrix.
- There is **no authentication**: this is a local single-user tool. Do not expose it to the public internet.

## Running the backend

All commands run from `backend/`.

### Option A: zero-install (embedded PostgreSQL)

```sh
./mvnw spring-boot:test-run
```

Starts the app on a real PostgreSQL started from embedded binaries. No database install is needed. Data is discarded when the process stops.

### Option B: native PostgreSQL (persistent)

1. Install PostgreSQL 16+ (Windows: the EDB installer).
2. Create the role and database once (idempotent):

   ```sh
   psql -U postgres -f scripts/db/create-dev-db.sql
   # or with your own password:
   psql -U postgres -v db_password=yourpassword -f scripts/db/create-dev-db.sql
   ```

   Run this from the repository root.
3. Start the app:

   ```sh
   ./mvnw spring-boot:run
   ```

   It connects with the defaults `jdbc:postgresql://localhost:5432/repointelligence`, user `repointel`, password `repointel_dev`. Override them with `DB_URL`, `DB_USERNAME` and `DB_PASSWORD`. See [.env.example](.env.example) for all variables.

Flyway applies the migrations in `backend/src/main/resources/db/migration/` on startup in both options.

Check it is running: `http://localhost:8080/actuator/health` should return `{"status":"UP"}`.

## Running the frontend

Requires Node.js 22+ and pnpm (`npm i -g pnpm`). All commands run from `frontend/`.

```sh
pnpm install
pnpm dev        # http://localhost:5173, proxies /api to http://localhost:8080
```

Start the backend first (see above) so the `/api` proxy has something to reach.

Repository-derived text (file paths, code excerpts, findings) is untrusted: it must never be rendered as HTML. Do not use `dangerouslySetInnerHTML`; ESLint fails the build if it appears.

## Running tests

```sh
# backend (from backend/)
./mvnw clean verify

# frontend (from frontend/)
pnpm lint && pnpm typecheck && pnpm test --run && pnpm build
```

Backend database tests use embedded PostgreSQL (no H2, no Docker).

`./mvnw verify` also runs a Spotless check (unused imports, trailing whitespace, final newline). Fix violations with `./mvnw spotless:apply`.

## Continuous integration

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on pushes to `main` and on pull requests:

- **Backend:** JDK 25, `./mvnw -B clean verify` (tests on embedded PostgreSQL, no Docker, plus the Spotless check). Test reports are uploaded as an artifact.
- **Frontend:** Node 22, `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm typecheck`, `pnpm test --run`, `pnpm build`.

Tests that need external CLIs (for example Semgrep) are tagged and excluded by default; the Semgrep parser, unavailability and process-kill behaviour are covered without the CLI.

## Troubleshooting

- **Maven fails with "release version 25 not supported":** `JAVA_HOME` points to an older JDK. Set it to JDK 25 and open a new terminal.
- **Port 8080 or 5173 already in use:** stop the previous run of the backend or `pnpm dev`.
- **Frontend tests crash on startup with an undici error:** your Node is older than a dependency requires; jsdom is pinned to a release that supports Node 22.12+.
- **Backend cannot connect to PostgreSQL:** use `./mvnw spring-boot:test-run` (embedded database), or run `scripts/db/create-dev-db.sql` against your native PostgreSQL and check `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`.
- **`mvnw: Permission denied` on Linux/macOS:** run `chmod +x mvnw`.

## Selected versions

| Component | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.2 |
| PMD (in-process) | 7.29.0 |
| JavaParser | 3.28.2 |
| maven-model (POM reading only) | 3.9.16 |
| springdoc-openapi | 3.2.0 |
| PostgreSQL (embedded, tests) | 16.16 |
| zonky embedded-postgres | 2.2.2 |
| Node.js (tested) | 22.14 |
| pnpm | 12 |
| React / React DOM | 19.3 |
| React Router | 8.5 |
| Vite | 8.3 |
| TypeScript | 5.9 |
| Tailwind CSS | 4.3 |
| TanStack Query | 5.108 |
| Vitest / jsdom / MSW | 5.1 / 29.1 / 3.0 |

jsdom is pinned to 29.x because jsdom 31 requires Node 22.22+.
