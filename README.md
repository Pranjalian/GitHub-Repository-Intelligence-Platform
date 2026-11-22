# GitHub Repository Intelligence Platform

Evidence-backed analysis of public GitHub repositories. Submit a public repository URL and get a commit-scoped report: technologies, structure, dependencies, code quality, secrets and security rules, tests and (real) coverage, plus an honest capability matrix that says what the platform could not analyze and why. For Java/Spring Boot it also discovers the **architecture**: layers, REST endpoints with request/response types, JPA entities and relationships, dependency injection chains, Maven module dependencies, dependency cycles and design-pattern candidates, with Mermaid diagrams. It is deterministic: **no AI and no AI key are needed**, and no code from the analyzed repository is ever executed.

See [docs/](docs/) for the problem statement, architecture, implementation plan and the completion reports for [MVP 1](docs/mvp1-completion-report.md) and [MVP 2](docs/mvp2-completion-report.md).

> Local single-user tool. There is no authentication yet: do not expose it to the public internet.

## Prerequisites

- **To run with Docker (recommended):** Docker Desktop (or Docker Engine) with Docker Compose v2. Nothing else.
- **To build, test or run without Docker:** JDK 25 (`JAVA_HOME` must point to it; Maven uses `JAVA_HOME`, not `PATH`), Node.js 22+ and pnpm. Optional: native PostgreSQL 16+ if you want data to persist between runs.
- Docker is **not** required for building or testing; CI runs the tests without it.

Maven itself is not needed; use the wrapper (`mvnw` / `mvnw.cmd`) in `backend/`.

## Run with Docker (recommended)

From the repository root:

```sh
cp .env.example .env          # then set GITHUB_TOKEN in .env (optional, strongly recommended)
docker compose up --build     # first build takes a few minutes
```

Open http://localhost:5173 when all three services are healthy (`docker compose ps`). The API is on http://localhost:8080 (Swagger UI at http://localhost:8080/swagger-ui.html).

| Command | What it does |
|---|---|
| `docker compose up --build -d` | Start in the background (rebuild after code changes) |
| `docker compose ps` | Service status and health |
| `docker compose logs -f backend` | Follow backend logs |
| `docker compose down` | Stop; **keeps** the database and workspace volumes |
| `docker compose down -v` | Stop and **delete** all analysis data |
| `docker compose up -d postgres` | Database only, for `mvnw spring-boot:run` + `pnpm dev` with hot reload (no native PostgreSQL needed) |

What runs: `postgres` (PostgreSQL 16, data in the `pgdata` volume), `backend` (Spring Boot on JRE 25, with a pinned Semgrep CLI so the Semgrep analyzer is available) and `frontend` (the built UI on nginx, which proxies `/api` to the backend). Containers run as non-root users, and every port is bound to `127.0.0.1` only, because there is no authentication. Analyzed repository code is still never executed.

Host ports can be changed with `POSTGRES_PORT`, `BACKEND_PORT` and `FRONTEND_PORT` in `.env`.

## How to analyze a repository

1. Start the app (with Docker above, or the backend and frontend separately below), open http://localhost:5173 and choose **Add Repository**.
2. Paste a public GitHub URL (for example `https://github.com/spring-projects/spring-petclinic`, optionally `/tree/<branch>`) and press **Analyze**. Tick *Force full rescan* to re-analyze a commit that was already analyzed.
3. Watch the progress page, then read the report: Overview (technologies, structure, capability matrix, limitations), Dependencies, Code Quality, Security, Tests & Coverage and, for Java/Spring Boot, Architecture. Use the snapshot selector to switch between analyzed commits.
4. The architecture also has its own page: `/repositories/{id}/arch?snapshot={snapshotId}` (link from the Architecture tab). Every statement shows its confidence (*Detected*, *Likely*, *Possible*) and how it was found; relationships to code outside the repository are labelled *unresolved*, never guessed.

API documentation: http://localhost:8080/swagger-ui.html. Flow: `POST /api/v1/repositories/analyze` returns a run id; poll `GET /api/v1/analysis-runs/{runId}`; read `GET /api/v1/repositories/{repositoryId}/reports/{snapshotId}` and the findings, dependencies, tests, coverage and architecture endpoints under `/snapshots/{snapshotId}/`.

## GitHub token

Set `GITHUB_TOKEN` to a **fine-grained token with read-only access to public repositories** (no scopes needed for public data). Without it GitHub allows only 60 API requests per hour per IP; with it, 5,000. The token is sent only to GitHub (`api.github.com`, `codeload.github.com`), never logged, and redacted from logs and reports.

## Optional tools and features

- **Semgrep** (security rules for Java). Already included in the Docker backend image. Otherwise not required: if the `semgrep` CLI is not found the analyzer is reported as *Unavailable* with the reason, and everything else still runs. Install natively (`pip install semgrep`; on Windows run it through WSL if native support is insufficient) or point to it with `analysis.analyzers.semgrep-security-analyzer.options.command`. Rules are vendored in `backend/src/main/resources/semgrep-rules/`; nothing is downloaded at runtime and metrics are off.
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
| Architecture | `analysis.analyzers.spring-architecture-analyzer.options.include-tests` (default false), `max-symbols` (50000), `max-edges` (200000), `max-file-kb` (512); layer, repository and package rules in `backend/src/main/resources/architecture/spring-architecture.yml` |
| Vulnerability lookup | `analysis.vulnerabilities.osv.*` |

## Known limitations

- Only **direct** dependencies are inventoried; transitive dependencies are not resolved.
- Versions supplied by **remote parents or BOMs** (for example `spring-boot-starter-parent`) are not resolved and are labelled as such.
- Tests are **never executed**. Coverage appears only when a JaCoCo report is committed in the repository and may be stale; otherwise it says "unavailable", never 0%.
- "Outdated dependency" detection is not implemented.
- Deep analysis exists for Java/Maven; other stacks get generic analysis (structure, secrets, test files, npm dependencies) and an honest "unsupported" matrix, including for architecture.
- Architecture discovery reads source only, with **no classpath**: types and inherited methods from libraries are not resolved (kept by name, labelled *unresolved*); calls are resolved only when the receiver's declared type is visible; reflection, proxies, conditional beans, `@Bean` methods, XML configuration, interface-declared or functional endpoints and Gradle modules are not analysed; test sources are excluded by default. Layers from package names alone and all pattern candidates except Spring Data repositories are *Likely* or *Possible*, never *Detected*.
- There is **no authentication**: this is a local single-user tool. Do not expose it to the public internet.

## Run without Docker

### Running the backend

All commands run from `backend/`.

#### Option A: zero-install (embedded PostgreSQL)

```sh
./mvnw spring-boot:test-run
```

Starts the app on a real PostgreSQL started from embedded binaries. No database install is needed. Data is discarded when the process stops.

#### Option B: native PostgreSQL (persistent)

1. Install PostgreSQL 16+ (Windows: the EDB installer). Or skip steps 1–2 and run `docker compose up -d postgres`, which creates the same role and database.
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

### Running the frontend

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

Architecture evaluation fixtures live in `backend/src/test/resources/fixtures/spring-boot-architecture` and `spring-multi-module`, each with an `EXPECTED.md` that the tests assert. The Mermaid diagrams generated for them are golden files in `backend/src/test/resources/golden/`: `ArchitectureE2eTests` fails when the output changes, and the frontend test `src/lib/mermaid.test.ts` parses every golden file with the real Mermaid parser. After an intended change, regenerate them with `./mvnw test -Dtest=ArchitectureE2eTests -Dgolden.update=true` and review the diff.

## Continuous integration

[.github/workflows/ci.yml](.github/workflows/ci.yml) runs on pushes to `main` and on pull requests:

- **Backend:** JDK 25, `./mvnw -B clean verify` (tests on embedded PostgreSQL, no Docker, plus the Spotless check). Test reports are uploaded as an artifact.
- **Frontend:** Node 22, `pnpm install --frozen-lockfile`, `pnpm lint`, `pnpm typecheck`, `pnpm test --run`, `pnpm build`.
- **Docker images:** `docker compose config` and `docker compose build`, so the Docker run keeps working. No tests run in containers.

Tests that need external CLIs (for example Semgrep) are tagged and excluded by default; the Semgrep parser, unavailability and process-kill behaviour are covered without the CLI.

## Troubleshooting

- **`docker compose` cannot connect to the Docker API:** start Docker Desktop and wait until it reports that the engine is running.
- **Docker: "port is already allocated":** a native PostgreSQL, an earlier `mvnw spring-boot:run` or `pnpm dev` is using 5432, 8080 or 5173. Stop it or change `POSTGRES_PORT`/`BACKEND_PORT`/`FRONTEND_PORT` in `.env`.
- **Docker: backend fails with "password authentication failed":** `DB_USERNAME`/`DB_PASSWORD` changed after the database volume was created. Restore the old values or run `docker compose down -v` (deletes the data).
- **Docker: code changes do not show up:** rebuild with `docker compose up --build`.
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
| PostgreSQL (Docker Compose) | `postgres:16-alpine` |
| Semgrep (Docker backend image) | 1.186.0 |
| zonky embedded-postgres | 2.2.2 |
| Node.js (tested) | 22.14 |
| pnpm | 12 |
| React / React DOM | 19.3 |
| React Router | 8.5 |
| Vite | 8.3 |
| TypeScript | 5.9 |
| Tailwind CSS | 4.3 |
| TanStack Query | 5.108 |
| Mermaid | 11.17.2 (pinned) |
| Vitest / jsdom / MSW | 5.1 / 29.1 / 3.0 |

jsdom is pinned to 29.x because jsdom 31 requires Node 22.22+.

## Implementing the next MVP with Claude Code

The project includes a Claude Code agent, [`.claude/agents/mvp-executor.md`](.claude/agents/mvp-executor.md), that implements one MVP of [docs/implementationPlan.md](docs/implementationPlan.md) end to end. It:

- creates a branch `mvp<N>` and commits each step on it;
- runs the full backend and frontend checks after every step and fixes problems at the cause;
- verifies the result in the Docker stack with one real analysis;
- writes `docs/mvp<N>-completion-report.md` and updates the README, plan and overview;
- returns a developer-oriented report (design walkthrough, issues and fixes, decisions, considerations, recommendations).

It never pushes or merges. In Claude Code, opened in this repository, ask for example:

```text
Use the mvp-executor agent to execute MVP 3
```

Then review the branch and merge it yourself (`git merge --ff-only mvp<N>` on `main`, then `git push`). The agent works without Docker if the daemon is not running, and without `GITHUB_TOKEN` (it keeps live GitHub calls to one analysis).
