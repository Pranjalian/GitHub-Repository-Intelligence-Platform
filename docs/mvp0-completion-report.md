# MVP 0 Completion Report: Project Foundation & Engineering Baseline

**Date:** 2026-11-19
**Plan:** [implementationPlan.md](implementationPlan.md) (v3), Steps 0.0 to 0.6
**Result:** All MVP 0 exit criteria met. No business logic and no AI dependency.

## 1. Workspace state at start (Step 0.0)

The workspace contained `docs/` (problem statement, architecture, implementation plan), a `.gitignore` and a one-line `README.md`, under git with an initial commit and a docs commit. No source code. The repository is hosted at `Pranjalian/GitHub-Repository-Intelligence-Platform` and `main` is the working branch.

## 2. Features implemented

| Step | Delivered |
|---|---|
| 0.1 | Spring Boot backend skeleton: Maven wrapper, web, JPA, validation, actuator, Flyway, PostgreSQL driver, springdoc; env-driven configuration (`application.yml`, `application-dev.yml`); shared test database config |
| 0.2 | Two Docker-free database options (embedded PostgreSQL, native PostgreSQL); `V1__baseline.sql`; `scripts/db/create-dev-db.sql`; `.env.example`; Flyway tests |
| 0.3 | RFC 9457 error handling; `SecretRedactor`; request correlation and structured logging; strict `GitHubUrlParser`; shared enums |
| 0.4 | OpenAPI metadata, Swagger UI, public health endpoint (details hidden outside `dev`) |
| 0.5 | React dashboard shell with navigation, UI primitives, API client that understands the backend error format |
| 0.6 | GitHub Actions CI (backend and frontend jobs), Spotless check, `.gitattributes`, README CI and troubleshooting sections |

## 3. Architecture and important decisions

- **Modular monolith**, packages under `com.repointelligence`: `common.{exception,security,logging,validation,model}` and `config`. Later modules (`repository`, `analysis`, `report`) arrive with MVP 1.
- **No Docker anywhere.** Docker is unavailable on the developer machine, so the plan, architecture and problem statement were revised (plan v3, ADR-15, ADR-16). Database tests use real PostgreSQL started from embedded binaries (zonky `embedded-postgres`, PostgreSQL 16.16). Local runs use either `mvnw spring-boot:test-run` (embedded, ephemeral) or a native PostgreSQL (persistent).
- **Flyway owns the schema** (`ddl-auto=validate`).
- **Single secret-redaction component** (`SecretRedactor`) used by log masking, structured JSON logging and, later, finding evidence and AI prompts.
- **One error format**: RFC 9457 `ProblemDetail` with `timestamp` and `requestId`. Unexpected errors expose no message and no stack trace.
- **SSRF control**: `GitHubUrlParser` produces a `GitHubRepositoryRef`; the backend will build `api.github.com` URLs from it and never fetch the user-supplied URL.
- **Frontend**: untrusted repository text is never rendered as HTML; ESLint forbids `dangerouslySetInnerHTML`.

### Selected versions

Java 25 (LTS), Spring Boot 4.1.2, springdoc-openapi 3.2.0, Flyway (managed by Boot), embedded PostgreSQL 16.16, native PostgreSQL 18.7 (verified), React 19.3, React Router 8.5, Vite 8.3, TypeScript 5.9, Tailwind CSS 4.3, TanStack Query 5.108, Vitest 5.1, jsdom 29.1, MSW 3.0, pnpm 12, Node 22.14 (tested). Deviations from the plan's targets: Java 25 instead of 21 (the plan allows 25), Vite 8 and React Router 8 instead of 7 (current stable lines), jsdom pinned to 29.x because jsdom 31 requires Node 22.22+.

## 4. Files and modules created

- `backend/`: `pom.xml`, Maven wrapper, `RepoIntelligenceApplication`, `config/OpenApiConfig`, `common/**` (7 exceptions plus handler, redactor, logging classes, URL parser, 8 enums), `application*.yml`, `logback-spring.xml`, `db/migration/V1__baseline.sql`, tests (95).
- `frontend/`: Vite/React/TypeScript project, layout, pages, UI primitives, API client, test setup (19 tests).
- `scripts/db/create-dev-db.sql`, `.env.example`, `.gitattributes`, `.github/workflows/ci.yml`, `README.md`.
- `docs/`: this report; docs revised to remove the Docker dependency.

## 5. Commands

Run (details in [README.md](../README.md)):

```sh
# backend, zero-install database (from backend/, JAVA_HOME = JDK 25)
./mvnw spring-boot:test-run
# backend, native PostgreSQL (after scripts/db/create-dev-db.sql)
./mvnw spring-boot:run
# frontend (from frontend/)
pnpm install && pnpm dev          # http://localhost:5173
```

Test:

```sh
./mvnw clean verify               # backend: tests + Spotless
pnpm lint && pnpm typecheck && pnpm test --run && pnpm build   # frontend
```

## 6. External tools and services required

JDK 25, Node 22+, pnpm. Optional: native PostgreSQL 16+. No Docker, no AI API key, no GitHub token (not used until MVP 1).

## 7. Verification

| Exit criterion | Evidence |
|---|---|
| Backend starts on embedded and native PostgreSQL | Both run; Flyway reaches v1; native run verified against PostgreSQL 18.7 |
| Frontend starts, `/api` proxy works | `pnpm dev` serves the shell; proxied request returned the backend's problem+json |
| Flyway runs on startup and in tests | `FlywayMigrationTests`, `flyway_schema_history` queried on both databases |
| Health `UP`, Swagger UI loads | `ApiDocumentationAndHealthTests`; manual check |
| CI builds, lints and tests both projects | CI run #1 on `ae9126a` green |
| No AI key, no Docker | Nothing in the code or CI requires either |

Backend: 95 tests, 0 failures. Frontend: 19 tests, 0 failures.

## 8. Features not implemented and why

By design for MVP 0: no business endpoints, no GitHub client, no analysis, no repository entities. These start in MVP 1.

## 9. Known limitations

- Log redaction covers log messages, not stack traces; a secret inside an exception message could still reach the console.
- No authentication: local single-user tool, not for public exposure.
- Embedded test database is PostgreSQL 16.16 while the verified native server is 18.7.
- The stock embedded PostgreSQL binaries do not include pgvector; the plan lists this as an open decision for MVP 4.
- The CI environment (Linux runner) differs from the Windows development machine; the first CI run passed, but only one run so far.
- `AddRepositoryPage` has an input only; submission arrives in MVP 1.

## 10. Security considerations

- Strict URL validation (host allowlist, no user info, no ports, no query, no path traversal) as the SSRF control.
- 500 responses leak no internals; every error carries a correlation id.
- Secrets are redacted in logs (plain and JSON). No secrets are committed; `.env` is ignored and `.env.example` holds placeholders. The default database password in `application.yml` and the setup script is for local development only.
- Actuator exposes only `health` and `info`; health details are hidden outside the `dev` profile.
- Frontend never renders repository-derived text as HTML.

## 11. Recommended next steps

Proceed to MVP 1, starting with Step 1.1 (database schema for repository and analysis). Keep migrations numbered at write time. Consider an ArchUnit rule early for package boundaries (core must not import `analysis.packs.*`).
