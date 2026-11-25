# Deployment Plan: Railway (backend + database) and Vercel (frontend)

**Document type:** Deployment blueprint (plan only; nothing in this file has been executed)
**Targets:** Backend, PostgreSQL and (optionally) the analysis workspace volume on **Railway**. Static frontend on **Vercel**.
**Based on:** `compose.yaml`, `backend/Dockerfile`, `db/Dockerfile`, `frontend/nginx.conf`, `.env.example`, `backend/src/main/resources/application*.yml`, [implementationPlan.md](implementationPlan.md) (ground rule: "No auth ... must not be exposed to the public internet until authentication is added").

> Platform limits, prices and UI labels change. Every item marked **(verify)** must be checked against the current Railway or Vercel documentation when you reach that step.

---

## 1. Decision you must make first: the app has no authentication

The product is a single-user local tool. Putting the backend on a public Railway URL means **anyone who finds the URL can**:

- submit analyses of any public repository (CPU, memory and disk on your Railway bill),
- spend your **GitHub token's** 5,000 requests per hour,
- spend your **Gemini / OpenRouter** quota if a hosted AI provider is enabled,
- read every report, finding and index you stored.

The plan therefore adds a **two-layer gate** (Phase 1). This is a deliberate change to the "no auth" ground rule and must be recorded in the README and in `docs/architecture.md`.

| Layer | What it protects | How |
|---|---|---|
| **A. Protect the site** | The UI and what a visitor can reach | Vercel **Deployment Protection** (Vercel Authentication, or Password Protection if your plan has it) **(verify plan)**. Only people you allow can open the app |
| **B. Protect the backend origin** | The Railway URL that must be public for Vercel to reach it | A **shared secret header** that only the Vercel proxy function adds. The backend answers `401` to every `/api/**` request without it |

With both layers, a stranger can neither open the UI nor call the Railway API directly. Without layer B, protecting the site alone is **not enough**, because the Railway domain is public.

**Fallback if you accept the risk** (not recommended): skip layer B and use a plain Vercel rewrite (Option 2 in 4.3). Then rely on Railway not being discoverable. Do **not** enable hosted AI in that case.

---

## 2. Target architecture

```
Browser ──HTTPS──> Vercel (static SPA from frontend/dist)
                      │  same-origin request  /api/**
                      ▼
                   Vercel Function  api/[...path]   (adds X-Repointel-Proxy-Secret, forwards body)
                      │  HTTPS
                      ▼
                   Railway: backend (Spring Boot, Semgrep, PMD)  ── private network ──>  Railway: postgres (+ pgvector)
                      │                                                     
                      ├──> api.github.com / codeload.github.com   (repository download)
                      └──> optional: Gemini / OpenRouter           (hosted AI, off by default)
```

Why same-origin (`/api` on the Vercel domain) instead of calling Railway from the browser:

- The frontend already calls relative `/api/...` URLs (`frontend/src/api/client.ts`), so **no frontend code changes**.
- The CSP (`connect-src 'self'`) stays exactly as it is.
- No CORS configuration on the backend, no Railway URL baked into the bundle.
- The shared secret never reaches the browser.

What does **not** move: Ollama. A local Ollama cannot be reached from Railway. In the cloud, AI is either **off** (default, the app is fully useful) or a **hosted provider** (Gemini / OpenRouter) after you set its `*_DATA_HANDLING_ACKNOWLEDGED=true` (excerpts of public repositories, secrets scrubbed, leave your infrastructure).

---

## 3. Railway (backend and database)

### 3.1 Services

One Railway project, **three** services (names matter because variables reference them):

| Service | Source | Notes |
|---|---|---|
| `postgres` | Option A: Railway's PostgreSQL template. Option B: this repo's `db/` folder (Dockerfile = postgres:16 + pgvector 0.8.7) | See 3.2 |
| `backend` | GitHub repo, **Root Directory `/backend`**, Dockerfile build | See 3.3 |
| (volume) | Attached to `backend`, mount path `/workspace` | See 3.4 |

The `frontend` Dockerfile and `nginx.conf` are **not** deployed (Vercel serves the static build and proxies `/api`).

### 3.2 PostgreSQL: with or without pgvector

pgvector is optional. Migration `V7__rag_index.sql` creates the extension only if the database offers it; otherwise vectors are ranked inside the application (`RAG_VECTOR_STORE=auto`, up to 50,000 vectors). Keyword and symbol search never need it.

| Option | Choose when | Setup |
|---|---|---|
| **A. Railway PostgreSQL template** | You use AI off or hosted embeddings only occasionally; simplest, managed | Add the Postgres service. Done. Semantic search uses the in-application fallback |
| **B. `db/Dockerfile` as a service** | You want pgvector (semantic search at scale) | New service from the repo, Root Directory `/db`. Attach a volume at `/var/lib/postgresql/data`. Set `PGDATA=/var/lib/postgresql/data/pgdata` (a Railway volume root contains `lost+found`, which stops `initdb`). Set `POSTGRES_DB=repointelligence`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (generated, 32+ chars). No public TCP proxy and no public domain: reach it only over the private network (`postgres.railway.internal:5432`) |

**Recommendation:** start with **A**. Move to B only if you actually use Q&A with embeddings and notice slow semantic search. The schema is the same in both, so no data migration is needed (stated in the V7 migration).

Rules for both:
- Never enable a public TCP proxy for the database.
- Flyway runs at backend startup and needs a role that can create tables (the default owner role does).
- Turn on Railway backups for the volume or schedule `pg_dump` **(verify what your plan offers)**; see 8.

### 3.3 Backend service

**Build:** Railway builds `backend/Dockerfile` as it is (multi-stage: Temurin 25 JDK build, Temurin 25 JRE runtime with Python venv + Semgrep 1.186.0, non-root user `app`). No code change is needed to build it. Expect a large image and a long first build (Semgrep install); keep Docker layer caching on.

**Config file** (new, `backend/railway.toml`):

```toml
[build]
builder = "DOCKERFILE"
dockerfilePath = "Dockerfile"

[deploy]
healthcheckPath = "/actuator/health"
healthcheckTimeout = 180          # JVM + Flyway start; the Dockerfile's own HEALTHCHECK is ignored by Railway
restartPolicyType = "ON_FAILURE"
restartPolicyMaxRetries = 5
numReplicas = 1                   # must stay 1, see below
```

**Must stay at one replica.** AI call budgets and the Q&A budget in progress are held in memory (README, "AI insights", "Search and Q&A"), and analysis jobs run in an in-process pool. Do not scale horizontally.

**Port.** `application.yml` fixes `server.port: 8080`. Set `PORT=8080` as a variable so Railway's public networking targets 8080 (do not rely on Railway's injected random port).

**Variables** (Railway "Variables" tab; `${{...}}` are Railway reference variables):

| Variable | Value | Purpose |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | `prod` | JSON logging with secret redaction (`application-prod.yml`) |
| `PORT` | `8080` | Public networking target |
| `DB_URL` | `jdbc:postgresql://${{postgres.RAILWAY_PRIVATE_DOMAIN}}:5432/${{postgres.PGDATABASE}}` | The app needs a JDBC URL, not Railway's `DATABASE_URL` |
| `DB_USERNAME` | `${{postgres.PGUSER}}` | |
| `DB_PASSWORD` | `${{postgres.PGPASSWORD}}` | |
| `GITHUB_TOKEN` | fine-grained token, public repositories read-only, no permissions | Raises the GitHub limit from 60 to 5,000/hour. **Required in practice**: unauthenticated cloud egress IPs are shared and hit the limit fast (Appendix C.1) |
| `WORKSPACE_ROOT` | `/workspace` | Extracted snapshots |
| `ACCESS_PROXY_SECRET` | random 48+ chars (`openssl rand -base64 48`) | The shared secret (Phase 1). Same value goes into Vercel |
| `AI_ENABLED` | `false` (default) | Keep AI off in the first release |
| `ANALYSIS_VULNERABILITIES_OSV_ENABLED` | `false` | Keep off unless you want outbound OSV lookups |
| `REPOSITORY_INGESTION_MAX_REPOSITORY_SIZE_MB` | `200` (lower to `100` to save cost) | Pre-check on repository size |
| `ANALYSIS_JOBS_MAX_ANALYSIS_DURATION` | `PT15M` | Per-run deadline |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` (already the image default) | Leaves about 25% of memory for the Semgrep child process |
| `RAILWAY_RUN_UID` | `0` | Only if the volume is not writable by the image's non-root `app` user (see 3.4) **(verify)** |

Hosted AI later (only after reading the provider's current terms): `AI_ENABLED=true`, `AI_DEFAULT_PROVIDER=gemini`, `AI_PROVIDERS_GEMINI_ENABLED=true`, `GEMINI_API_KEY`, `GEMINI_CHAT_MODEL`, `AI_PROVIDERS_GEMINI_DATA_HANDLING_ACKNOWLEDGED=true`, `AI_MAX_CALLS_PER_ANALYSIS=5`, and **`AI_TIMEOUT_SECONDS` below the Vercel function limit** (see 4.3). Leave `OLLAMA_*` unset and `AI_PROVIDERS_OLLAMA_ENABLED=false`.

**Resources.** Local Compose caps the backend at 2 GB. On Railway start with **2 GB RAM and 2 vCPU** **(verify plan limits and price)** and watch memory during an analysis of a larger Java repository (PMD plus Semgrep run in the same container). Raise only if you see restarts.

**Networking.**
- Generate a Railway public domain for `backend` only (for example `repointel-backend.up.railway.app`). Keep the project's private network for `backend` to `postgres`.
- Railway's healthcheck calls `/actuator/health` from its own host name; the gate (Phase 1) must allow that path.
- Optional hardening: no custom domain for the backend at all. Only the Vercel proxy needs to know the URL.

### 3.4 Workspace volume

`WORKSPACE_ROOT=/workspace` holds extracted repository snapshots during analysis (`retain-after-analysis: PT0S` deletes them right after a run; indexing rebuilds a missing workspace from one GitHub tarball download).

- Attach a Railway **volume** to `backend` mounted at `/workspace`, sized 5 GB to start (extracted size is capped at 500 MB per snapshot; `max-pending-jobs: 10` bounds concurrency).
- Without a volume the container's ephemeral disk is used. It works, but a restart in the middle of an analysis loses the workspace, and ephemeral disk limits apply **(verify)**.
- The image runs as the non-root user `app`. If Railway mounts the volume root-owned and the backend cannot write, either set `RAILWAY_RUN_UID=0` **(verify; weakens the non-root hardening, so prefer the next option)** or add an entrypoint step that `chown`s `/workspace` before dropping privileges. Test this in the first deploy (Phase 3, check 6).

### 3.5 Backend changes required in this repo (small)

These are the only code changes the deployment needs. Put them in a branch `deploy/railway-vercel` and test them like any backend change (`./mvnw clean verify`).

1. **`AccessGateFilter`** (`com.repointelligence.common.security`):
   - Active only when `ACCESS_PROXY_SECRET` is set (local Compose and tests stay open, unchanged).
   - Requires header `X-Repointel-Proxy-Secret` equal to the secret for every `/api/**` request, compared in **constant time** (`MessageDigest.isEqual`). Otherwise `401` with the standard RFC 9457 problem body and **no hint** about the expected header.
   - Always allows `/actuator/health` (Railway healthcheck). `show-details: never` already hides internals.
   - Never logs the header value (redaction test).
2. **Disable the Swagger UI and OpenAPI docs in `prod`** (`springdoc.swagger-ui.enabled=false`, `springdoc.api-docs.enabled=false` in `application-prod.yml`): they list every endpoint on a public host.
3. **Trusted forwarded headers:** the backend sits behind Railway's proxy and the Vercel function. Set `server.forward-headers-strategy=framework` in `prod` so generated URLs and the analyze rate limiter use the forwarded client address, and decide which header the rate limit trusts. The existing token bucket (10 per minute on `POST /repositories/analyze`) is global; keep it (it is the cost guard).
4. **Tests:** gate enabled without header, wrong header, right header, health path open, secret never logged; gate disabled when the variable is empty.
5. Document the new variable in `.env.example` (empty by default), the README and `docs/architecture.md`.

---

## 4. Vercel (frontend and API proxy)

### 4.1 Project settings

| Setting | Value |
|---|---|
| Root Directory | `frontend` |
| Framework preset | Vite |
| Install command | `pnpm install --frozen-lockfile` |
| Build command | `pnpm build` (runs `tsc --noEmit && vite build`) |
| Output directory | `dist` |
| Node version | 22.x (matches `frontend/Dockerfile`) |
| Package manager | pnpm from `packageManager` in `package.json` (Corepack; **verify** that Vercel picks the pinned version) |
| Production branch | `main`; other branches get preview deployments |

Environment variables (Vercel, **not** exposed to the browser because they are read only by the function; do not prefix with `VITE_`):

| Variable | Value | Scope |
|---|---|---|
| `BACKEND_URL` | `https://<backend>.up.railway.app` | Production (and Preview if previews should reach the backend; see 4.4) |
| `PROXY_SECRET` | same as Railway `ACCESS_PROXY_SECRET` | Production, marked **Sensitive** |

### 4.2 `frontend/vercel.json` (new)

Reproduces what `nginx.conf` did: the security headers and the SPA fallback. The CSP must stay as strict as today.

```json
{
  "$schema": "https://openapi.vercel.sh/vercel.json",
  "headers": [
    {
      "source": "/(.*)",
      "headers": [
        { "key": "Content-Security-Policy", "value": "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'" },
        { "key": "X-Content-Type-Options", "value": "nosniff" },
        { "key": "X-Frame-Options", "value": "DENY" },
        { "key": "Referrer-Policy", "value": "no-referrer" }
      ]
    },
    {
      "source": "/assets/(.*)",
      "headers": [{ "key": "Cache-Control", "value": "public, max-age=31536000, immutable" }]
    }
  ],
  "rewrites": [
    { "source": "/((?!api/|assets/).*)", "destination": "/index.html" }
  ]
}
```

Notes:
- The fallback excludes `/api/` and `/assets/` so API calls reach the function and missing assets return 404 instead of HTML.
- The font files added by MVP 7 are emitted under `/assets/` by Vite, so `font-src` falls under `default-src 'self'`. No CSP change is needed (check again after MVP 7 lands).
- Add `Strict-Transport-Security` only if you use a custom domain you control **(verify)**; Vercel already serves HTTPS.

### 4.3 The `/api` proxy

**Option 1 (recommended): a Vercel Function that adds the shared secret.** New file `frontend/api/[...path].ts`:

- Accepts every method on `/api/**`.
- Builds the target URL `BACKEND_URL + request.url path + query`.
- Forwards the method, body (JSON; the backend limit is 2 MB, below Vercel's request body limit **(verify)**), `Content-Type`, `Accept` and a request id; **drops** browser cookies and hop-by-hop headers; **adds** `X-Repointel-Proxy-Secret: ${PROXY_SECRET}`.
- Returns the backend's status, `Content-Type`, `Retry-After` and body unchanged (the UI depends on RFC 9457 error bodies and `retryAfterSeconds`).
- Sets a hard upstream timeout shorter than the function's `maxDuration`, and answers `504` with a problem body on timeout.
- Never logs the secret, the token, request bodies or response bodies.

**Function time limit (verify).** The long calls are AI insights and Q&A (up to `AI_TIMEOUT_SECONDS`, default 60). Analyses are asynchronous (`202` then polling), so they do not hit the limit. Choose one:
- Plan allows a 60 s (or longer) function duration: set `maxDuration` accordingly and keep `AI_TIMEOUT_SECONDS=45`.
- Plan limit is shorter: set `AI_TIMEOUT_SECONDS` below it (for example 20 to 25); the UI already shows "The AI provider did not answer in time" for a timeout.

**Option 2 (simpler, weaker): plain rewrite** in `vercel.json`: `{ "source": "/api/:path*", "destination": "https://<backend>.up.railway.app/api/:path*" }` placed before the SPA fallback. No function and no function time limit, but **it cannot add the secret header**, so the Railway origin stays open to anyone. Use only with AI off and an accepted risk (section 1).

### 4.4 Deployment Protection and previews

- Production: enable Vercel Deployment Protection so only allowed people open the site (layer A, section 1). **(Verify the options on your plan.)**
- Preview deployments (one per pull request) must not hit production data by accident. Either leave `BACKEND_URL` / `PROXY_SECRET` unset for Preview (the UI then shows an error state, which is acceptable), or point Preview at a separate Railway **environment** with its own database. Do the second only if you really review UI changes against live data.

---

## 5. Phased rollout

### Phase 0: Prerequisites (about 0.5 day)
- [ ] GitHub fine-grained token created (public repos read-only, no permissions). Never commit it.
- [ ] Railway and Vercel accounts, both linked to the GitHub repository.
- [ ] Decide the AI mode for the first release: **off** (recommended).
- [ ] Decide protection: Vercel Deployment Protection available on your plan? If not, decide on the fallback (a small shared-password check in the proxy function) before going public.
- [ ] `pnpm build` and `./mvnw clean verify` green on `main`; the Docker Compose stack works locally.

### Phase 1: Code changes in the repo (about 1 day), branch `deploy/railway-vercel`
- [ ] Backend: gate filter, prod Swagger off, forwarded-headers setting, tests (3.5).
- [ ] `backend/railway.toml` (3.3).
- [ ] `frontend/vercel.json` (4.2) and `frontend/api/[...path].ts` (4.3) with unit tests for the proxy (header added, secret not returned to the browser, timeout becomes 504, hop-by-hop headers dropped).
- [ ] `.env.example`: `ACCESS_PROXY_SECRET=` (empty means open, for local use).
- [ ] README: a "Deploying to Railway and Vercel" section, plus the explicit note that the app is **not** safe to expose without the gate.
- [ ] CI stays Docker-free; add nothing that needs a Railway or Vercel token to the main jobs.

### Phase 2: Railway (about 0.5 day)
1. New project; add `postgres` (3.2). Note the service name.
2. Add `backend` from the GitHub repo, Root Directory `/backend`; set all variables of 3.3 (generate `ACCESS_PROXY_SECRET` now).
3. Attach the `/workspace` volume (3.4).
4. Deploy. Watch the logs: Flyway applies all migrations; the app reports healthy; `/actuator/health` returns `UP` from the Railway healthcheck.
5. Generate the public domain. Confirm:
   - `GET https://<backend>/actuator/health` returns 200 (no secret needed);
   - `GET https://<backend>/api/v1/repositories` returns **401** without the secret and **200** with `-H "X-Repointel-Proxy-Secret: ..."`;
   - `/swagger-ui.html` and `/v3/api-docs` return 404.

### Phase 3: Vercel (about 0.5 day)
1. Import the repository, Root Directory `frontend`, settings of 4.1.
2. Set `BACKEND_URL` and `PROXY_SECRET`; deploy.
3. Enable Deployment Protection (4.4).
4. Smoke test through the Vercel URL, signed in as an allowed user:
   1. Dashboard loads, no CSP errors in the browser console, fonts and icons local.
   2. Analyze `https://github.com/spring-projects/spring-petclinic`: progress screen, completion, report, findings, dependencies, tests, architecture diagrams render.
   3. Search index one snapshot; run a symbol search.
   4. Impact analysis on a picked symbol.
   5. Deep link refresh (`/repositories/<id>?tab=...`) works (SPA fallback).
   6. Workspace writable: the analysis in step 2 proves the volume permissions; check Railway logs for `AccessDenied`/permission errors.
   7. AI Settings shows "AI disabled" and every AI entry point shows the calm disabled pattern.
5. Negative checks: request the Railway API directly without the header (401); open the Vercel site while signed out (blocked); a long GitHub rate-limit case shows the retry time.

### Phase 4: Hardening and operations (about 0.5 day)
- [ ] Railway: usage alert and a hard spend limit **(verify availability)**; restart policy set; one replica.
- [ ] Backups: scheduled `pg_dump` (a Railway cron service or an external job) **or** Railway volume backups **(verify)**; do one test restore into a scratch database.
- [ ] Secrets rotation procedure written down (section 7).
- [ ] Log review: structured JSON with redaction; confirm no token, secret header, or repository secret appears in Railway logs (search for `ghp_`, `github_pat_`, the proxy secret's first characters).
- [ ] Optional: a custom domain for the Vercel site; none for the backend.

### Phase 5: Optional later
- Hosted AI: enable Gemini or OpenRouter after reading the provider terms, with the acknowledgement variable, `AI_MAX_CALLS_PER_ANALYSIS=5`, and the timeout rule of 4.3. Prefer the cheap model per feature (Appendix C.4).
- pgvector (3.2 option B) if semantic search is used.
- Preview environment with its own database.
- Uptime check on `/actuator/health` via an external monitor.

---

## 6. CI/CD

| Concern | Setup |
|---|---|
| Backend deploy | Railway GitHub integration: auto-deploy `main`, **watch paths** `/backend/**` and `/db/**` only so frontend changes do not rebuild the backend; enable "wait for CI" if available **(verify)** |
| Frontend deploy | Vercel Git integration: production from `main`, previews from pull requests, ignored build step when only `backend/**` changed (Vercel "Ignored Build Step" or the Root Directory filter) |
| Order for a change that touches both | Deploy the backend first (additive fields stay compatible; MVP 7 step 7.3 only adds fields), then the frontend |
| Migrations | Flyway runs on backend start. A failed migration makes the new deployment fail its healthcheck, so **Railway keeps the previous deployment serving** (verify this behaviour on first use). Migrations must stay additive and backward compatible for one release |
| Tests | Unchanged: `./mvnw clean verify` and `pnpm lint && pnpm typecheck && pnpm test && pnpm build` in the existing CI, Docker-free |

---

## 7. Secrets and rotation

| Secret | Where it lives | Rotate |
|---|---|---|
| `GITHUB_TOKEN` | Railway variable only | Revoke on GitHub, create a new one, update Railway, redeploy. Verify with `railway run sh -c '[ -n "$GITHUB_TOKEN" ] && echo set'` (do not print it) |
| `ACCESS_PROXY_SECRET` / `PROXY_SECRET` | Railway variable and Vercel sensitive variable (same value) | Change both, redeploy backend then frontend; brief 401s between the two deploys are expected |
| `DB_PASSWORD` | Railway reference to the Postgres service | Rotate in the Postgres service, redeploy the backend |
| `GEMINI_API_KEY` / `OPENROUTER_API_KEY` | Railway variable (only if hosted AI is on) | Revoke at the provider, replace, redeploy |

Rules (unchanged from Appendix C.1): never commit secrets, never put them in `vercel.json`, `railway.toml`, docs or chat; the proxy and backend never log them.

---

## 8. Backup, rollback and incident handling

- **Rollback backend:** Railway "Redeploy" of the previous successful deployment. If a migration already ran, the previous code must still work with the new schema (additive migrations rule).
- **Rollback frontend:** Vercel "Promote" the previous production deployment (instant).
- **Database loss:** restore the latest dump. All results are **derived data**: if there is no backup, re-run the analyses (each is one GitHub download per commit). This is why backups are useful but not critical.
- **Leaked token or secret:** rotate per section 7, then check Railway logs and GitHub's token activity.
- **Abuse or cost spike:** set `ACCESS_PROXY_SECRET` to a new value (cuts off any direct callers), lower `repository.submission.rate-limit.capacity` / `max-pending-jobs`, and pause the service in Railway if needed.

---

## 9. Costs and sizing (indicative, verify current prices)

- **Railway:** usage based (compute, memory, volume, egress) after the plan's included credit. The backend is the main cost because of memory (2 GB target) while analyses run; it is mostly idle otherwise. PostgreSQL and a 5 GB volume are small. Set a spend limit.
- **Vercel:** the static site and the proxy function are small. Protection options and function duration depend on the plan.
- **Third parties:** GitHub API is free with a token. Hosted AI is pay-per-use or quota-limited (keep it off at first).

---

## 10. Risks and open points

| Risk | Impact | Mitigation |
|---|---|---|
| No authentication in the app itself | Open API on the public internet | Two-layer gate (section 1); do not skip layer B |
| Vercel function duration shorter than AI call time | Insight/Q&A calls fail with 504 | Keep `AI_TIMEOUT_SECONDS` below the limit or choose a plan with a longer duration (4.3) |
| Non-root `app` user cannot write to the volume | Every analysis fails at ingestion | Test in Phase 3; chown in an entrypoint step, or `RAILWAY_RUN_UID=0` as a last resort |
| Memory pressure (PMD + Semgrep + JVM) | Restarts during large analyses | 2 GB+ RAM, repository size cap, `max-pending-jobs`; watch metrics |
| Shared GitHub egress IP without a token | 60 requests/hour shared | Token is mandatory (3.3) |
| Scaling beyond one instance | Budget counters and job pool are in memory | Keep `numReplicas = 1`; revisit (Appendix C.4) before scaling |
| Hosted AI data handling | Repository excerpts leave your infrastructure | Off by default; acknowledgement variable; public repositories only |
| Railway/Vercel feature or limit changes | Plan steps drift | Every **(verify)** item is checked at execution time and the result recorded in the completion note |

---

## 11. Completion checklist

- [ ] Backend deployed on Railway, healthy, one replica, database and volume attached
- [ ] Frontend deployed on Vercel from `main`; `/api` reaches the backend through the proxy function
- [ ] Direct calls to the Railway API without the secret return 401; Swagger is not exposed
- [ ] Site protected by Vercel Deployment Protection (or the documented fallback)
- [ ] spring-petclinic analysed end to end through the Vercel URL (report, architecture, search, impact)
- [ ] No CSP violations in the browser console; no external fonts, scripts or images
- [ ] AI disabled by default and every AI entry point shows the disabled pattern
- [ ] No secret in logs, repository, build output or browser
- [ ] Backup configured and one restore tested
- [ ] Spend limit and alerts set
- [ ] README, `docs/architecture.md` and `.env.example` updated; the "no auth" rule updated with the gate
