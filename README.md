# GitHub Repository Intelligence Platform

Evidence-backed analysis of public GitHub repositories. See [docs/](docs/) for the problem statement, architecture and implementation plan.

> Local single-user tool. There is no authentication yet: do not expose it to the public internet.

## Prerequisites

- JDK 25 (`JAVA_HOME` must point to it; Maven uses `JAVA_HOME`, not `PATH`)
- Optional: native PostgreSQL 16+ if you want data to persist between runs
- **No Docker required** for building, testing or running

Maven itself is not needed; use the wrapper (`mvnw` / `mvnw.cmd`) in `backend/`.

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

## Selected versions

| Component | Version |
|---|---|
| Java | 25 |
| Spring Boot | 4.1.2 |
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
