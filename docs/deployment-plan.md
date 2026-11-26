# Deployment Plan: Railway (backend + database) and Vercel (frontend)

**Document type:** Deployment blueprint (plan only; nothing in this file has been executed)
**Targets:** Backend, PostgreSQL and (optionally) the analysis workspace volume on **Railway**. Static frontend on **Vercel**.
**Based on:** `compose.yaml`, `backend/Dockerfile`, `db/Dockerfile`, `frontend/nginx.conf`, `.env.example`, `backend/src/main/resources/application*.yml`, [implementationPlan.md](implementationPlan.md) (ground rule: "No auth ... must not be exposed to the public internet until authentication is added", now met by MVP 8).

> Platform limits, prices and UI labels change. Every item marked **(verify)** must be checked against the current Railway or Vercel documentation when you reach that step.

---

## 1. Authentication: a real login, enforced by the backend (MVP 8)

The app was built as a single-user local tool; MVP 8 adds the login this deployment needs. Without it, putting the backend on a public Railway URL means **anyone who finds the URL can**:

- submit analyses of any public repository (CPU, memory and disk on your Railway bill),
- spend your **GitHub token's** 5,000 requests per hour,
- spend your **Gemini** quota (you use a Gemini API key, so AI is on in this deployment),
- read every report, finding and index you stored.

**Decision (replaces the earlier two-layer gate):** implement **MVP 8** ([implementationPlan.md](implementationPlan.md), a single-user login with a server-side session cookie, CSRF protection and login throttling). With it:

| Earlier idea | Status now |
|---|---|
| Shared-secret header added by a Vercel proxy function (backend origin gate) | **Dropped.** The backend rejects every `/api/**` call without a valid session, so the secret is redundant |
| Vercel Deployment Protection (site gate) | **Optional.** The static HTML and JavaScript contain no data. Keep it only as an extra lock while testing |
| Plain Vercel rewrite for `/api` | **Used** (4.3). No proxy function, no function time limit for normal calls |

**What must be true for this to be safe:** the login is enforced **on the Railway API**, not only in the React app. A login page that only hides screens protects nothing, because the Railway URL is public. MVP 8 Step 8.2 and its tests (401 without a session, from the Railway URL directly) are the proof; the deployment checks in Phase 2 and 3 repeat it from outside.

**Do not deploy before MVP 8 is done.** With `AUTH_ENABLED=false` the app is open; the backend must never be reachable on a public URL in that mode.

---

## 2. Target architecture

```
Browser ──HTTPS──> Vercel (static SPA from frontend/dist, login page at /login)
                      │  same-origin request  /api/**   (plain Vercel rewrite, cookies pass through)
                      ▼
                   Railway: backend (Spring Boot, Semgrep, PMD; session login enforced here)
                      │  private network
                      ├──> Railway: postgres (+ pgvector optional)
                      ├──> api.github.com / codeload.github.com          (repository download)
                      └──> generativelanguage.googleapis.com             (Gemini, AI features)
```

Why same-origin (`/api` on the Vercel domain) instead of calling Railway from the browser:

- The frontend already calls relative `/api/...` URLs (`frontend/src/api/client.ts`), so the only frontend changes are the MVP 8 login and session handling.
- The CSP (`connect-src 'self'`) stays exactly as it is.
- The session cookie is first-party (the browser only sees the Vercel domain), so no CORS configuration and no third-party-cookie problems.

**AI in the cloud: Gemini.** This project uses a **Gemini API key** (locally too; Ollama is not used). `AI_DEFAULT_PROVIDER` now defaults to `gemini`, but AI is still **off** until `AI_ENABLED=true`, the Gemini provider is enabled and `AI_PROVIDERS_GEMINI_DATA_HANDLING_ACKNOWLEDGED=true` is set (3.3). Excerpts of **public** repositories, with secrets scrubbed, are sent to Google; the backend only allows the host `generativelanguage.googleapis.com`. A local Ollama cannot be reached from Railway and is not part of this deployment.

---

## 3. Railway (backend and database)

### 3.1 Services

One Railway project, **three** services (names matter because variables reference them):

| Service | Source | Notes |
|---|---|---|
| `postgres` | Option A: Railway's PostgreSQL template. Option B: this repo's `db/` folder (Dockerfile = postgres:16 + pgvector 0.8.7) | See 3.2 |
| `backend` | GitHub repo, **Root Directory `/backend`**, Dockerfile build | See 3.3 |
| (volume) | Attached to `backend`, mount path `/workspace` | See 3.4 |

The `frontend` Dockerfile and `nginx.conf` are **not** deployed (Vercel serves the static build and rewrites `/api` to Railway).

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
| `AUTH_ENABLED` | `true` | Turns the MVP 8 login on. The app refuses to start if it is true and the hash below is missing |
| `AUTH_USERNAME` | your choice (not `admin`) | The single account |
| `AUTH_PASSWORD_HASH` | BCrypt hash, cost 12 (`htpasswd -nbBC 12 "" 'password' \| cut -d: -f2`) | Paste as is in Railway (the `$$` escaping is only for Compose files). Never store the plain password |
| `AI_ENABLED` | `true` | You use Gemini. Set to `false` for a first smoke test without AI if you prefer |
| `AI_DEFAULT_PROVIDER` | `gemini` | Same as the new default |
| `AI_PROVIDERS_GEMINI_ENABLED` | `true` | |
| `GEMINI_API_KEY` | your key | Railway variable only, never committed. Consider a key restricted to the Generative Language API and a budget alert in Google Cloud |
| `GEMINI_CHAT_MODEL` | the model id you use locally (for example your Flash Lite model) | Nothing is assumed by the code |
| `AI_PROVIDERS_GEMINI_DATA_HANDLING_ACKNOWLEDGED` | `true` | Required; public-repository excerpts leave your infrastructure |
| `AI_MAX_CALLS_PER_ANALYSIS` | `5` | Matches your local setting (Appendix C.4) |
| `ANALYSIS_VULNERABILITIES_OSV_ENABLED` | `false` | Keep off unless you want outbound OSV lookups |
| `REPOSITORY_INGESTION_MAX_REPOSITORY_SIZE_MB` | `200` (lower to `100` to save cost) | Pre-check on repository size |
| `ANALYSIS_JOBS_MAX_ANALYSIS_DURATION` | `PT15M` | Per-run deadline |
| `JAVA_TOOL_OPTIONS` | `-XX:MaxRAMPercentage=75` (already the image default) | Leaves about 25% of memory for the Semgrep child process |
| `RAILWAY_RUN_UID` | `0` | Only if the volume is not writable by the image's non-root `app` user (see 3.4) **(verify)** |

Read Google's current terms, quotas and data-use policy for your key type before enabling Gemini (free-tier data may be used to improve products; verify). Leave `OLLAMA_*` unset and `AI_PROVIDERS_OLLAMA_ENABLED=false`. Set **`AI_TIMEOUT_SECONDS` below the Vercel rewrite timeout** (see 4.3). If you use Gemini embeddings for semantic search: `AI_EMBEDDING_PROVIDER=gemini` and `GEMINI_EMBEDDING_MODEL` (the embedding default is still `ollama` in the code).

**Resources.** Local Compose caps the backend at 2 GB. On Railway start with **2 GB RAM and 2 vCPU** **(verify plan limits and price)** and watch memory during an analysis of a larger Java repository (PMD plus Semgrep run in the same container). Raise only if you see restarts.

**Networking.**
- Generate a Railway public domain for `backend` only (for example `repointel-backend.up.railway.app`). Keep the project's private network for `backend` to `postgres`.
- Railway's healthcheck calls `/actuator/health` from its own host name; MVP 8 leaves that path open (and only that actuator path).
- Optional hardening: no custom domain for the backend at all. Only the Vercel rewrite in `vercel.json` needs to know the URL.

### 3.4 Workspace volume

`WORKSPACE_ROOT=/workspace` holds extracted repository snapshots during analysis (`retain-after-analysis: PT0S` deletes them right after a run; indexing rebuilds a missing workspace from one GitHub tarball download).

- Attach a Railway **volume** to `backend` mounted at `/workspace`, sized 5 GB to start (extracted size is capped at 500 MB per snapshot; `max-pending-jobs: 10` bounds concurrency).
- Without a volume the container's ephemeral disk is used. It works, but a restart in the middle of an analysis loses the workspace, and ephemeral disk limits apply **(verify)**.
- The image runs as the non-root user `app`. If Railway mounts the volume root-owned and the backend cannot write, either set `RAILWAY_RUN_UID=0` **(verify; weakens the non-root hardening, so prefer the next option)** or add an entrypoint step that `chown`s `/workspace` before dropping privileges. Test this in the first deploy (Phase 3, check 6).

### 3.5 Backend changes required in this repo (small)

The authentication code is **MVP 8** (Steps 8.2 and 8.3 of [implementationPlan.md](implementationPlan.md)): login endpoints, session and CSRF handling, throttling, fail-closed startup, Swagger and OpenAPI disabled in `prod`, `server.forward-headers-strategy=framework` in `prod` (so the client address behind Railway's proxy reaches the login throttle), and `server.servlet.session.cookie.secure=true` in `prod` (measured in MVP 8: forwarded headers alone do not make Tomcat's session cookie `Secure`), log redaction of cookies and passwords. Do those first, on their own branch, and test them like any backend change (`./mvnw clean verify`, both `auth.enabled` values).

Deployment-only additions on top of MVP 8: `backend/railway.toml` (3.3) and the Railway variables above. The existing token bucket (10 per minute on `POST /repositories/analyze`) stays as the cost guard behind the login.

---

## 4. Vercel (frontend and `/api` rewrite)

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

No Vercel environment variables are needed: `vercel.json` cannot read them, so the Railway URL is written into the rewrite (4.3). It is not a secret (the login protects the API, not the URL).

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
    { "source": "/api/:path*", "destination": "https://<backend>.up.railway.app/api/:path*" },
    { "source": "/((?!api/|assets/).*)", "destination": "/index.html" }
  ]
}
```

Notes:
- The fallback excludes `/api/` and `/assets/` so API calls reach the `/api` rewrite and missing assets return 404 instead of HTML.
- The font files added by MVP 7 are emitted under `/assets/` by Vite, so `font-src` falls under `default-src 'self'`. No CSP change is needed (MVP 7 is already merged).
- Add `Strict-Transport-Security` only if you use a custom domain you control **(verify)**; Vercel already serves HTTPS.

### 4.3 The `/api` rewrite (no proxy function)

With the backend enforcing the login (section 1), `/api/**` is a **plain rewrite** to Railway (the first rewrite in 4.2). There is no `api/[...path].ts` function and no shared secret.

- Put the real Railway URL in `vercel.json` (it is not a secret). Keep the `/api/` rule **before** the SPA fallback.
- The browser, the rewrite and the backend must agree on cookies: the session cookie is set by the backend, host-only, `HttpOnly`, `Secure`, `SameSite=Lax`. Verify on the first deploy that `Set-Cookie` and `Retry-After` pass through the rewrite unchanged and that the request body (up to the backend's 2 MB limit) and the `X-XSRF-TOKEN` header arrive **(verify Vercel's rewrite behaviour and body limit)**.
- Long calls: AI insights and Q&A can take up to `AI_TIMEOUT_SECONDS` (60 by default). **Verify the maximum time Vercel allows for a proxied (rewritten) request on your plan** and set `AI_TIMEOUT_SECONDS` below it (for example 25 if it is short). The UI already shows "The AI provider did not answer in time" on a timeout. Analyses are asynchronous (`202` then polling), so they are not affected.
- If a rewrite turns out to strip or rewrite cookies or time out too early, the fallback is a small Vercel Function that forwards the request and `Set-Cookie` unchanged (no secret involved). Do that only if the verification above fails.

### 4.4 Deployment Protection and previews

- **Deployment Protection is optional** now that the API has its own login. Enabling it adds a second lock on the static site (and costs plan features) **(verify availability)**; it is not needed for safety.
- Preview deployments (one per pull request) rewrite `/api` to the same Railway backend, so a preview needs the same login and sees production data. Either accept that (single user, same credentials) or use a separate Railway **environment** with its own database and point the preview rewrite at it. Do the second only if you really review UI changes against live data.

---

## 5. Phased rollout

### Phase 0: Prerequisites (about 0.5 day)
- [ ] GitHub fine-grained token created (public repos read-only, no permissions). Never commit it.
- [ ] Railway and Vercel accounts, both linked to the GitHub repository.
- [ ] Gemini API key at hand (the one you use locally) and the chat model id; read Google's current terms for your key type.
- [ ] **MVP 8 (login) implemented and merged**, `./mvnw clean verify` and `pnpm lint && pnpm typecheck && pnpm test && pnpm build` green, Docker Compose stack still works locally with `AUTH_ENABLED=false` and with `true`.
- [ ] A BCrypt hash generated for the account password (stored in a password manager, not in the repository).

### Phase 1: Deployment files in the repo (about 0.5 day), branch `deploy/railway-vercel`
- [ ] `backend/railway.toml` (3.3).
- [ ] `frontend/vercel.json` (4.2) with the `/api` rewrite to the Railway URL (the URL is known after Phase 2, so this file is committed at the end of Phase 2).
- [ ] README: a "Deploying to Railway and Vercel" section and the rule that an instance with `AUTH_ENABLED=false` must never be public.
- [ ] CI stays Docker-free; add nothing that needs a Railway or Vercel token to the main jobs.

### Phase 2: Railway (about 0.5 day)
1. New project; add `postgres` (3.2). Note the service name.
2. Add `backend` from the GitHub repo, Root Directory `/backend`; set all variables of 3.3, including `AUTH_ENABLED=true`, `AUTH_USERNAME`, `AUTH_PASSWORD_HASH` and the Gemini variables.
3. Attach the `/workspace` volume (3.4).
4. Deploy. Watch the logs: Flyway applies all migrations; the app reports healthy; `/actuator/health` returns `UP` from the Railway healthcheck. (If the app exits at startup complaining about the password hash, the variable is missing or malformed: that is the fail-closed check working.)
5. Generate the public domain. Confirm from your own machine:
   - `GET https://<backend>/actuator/health` returns 200;
   - `GET https://<backend>/api/v1/repositories` returns **401** with no session;
   - `POST https://<backend>/api/v1/auth/login` with a wrong password returns 401 (and 429 with `Retry-After` after five tries);
   - `/swagger-ui.html` and `/v3/api-docs` return 404.
6. Put the domain into `frontend/vercel.json` and commit.

### Phase 3: Vercel (about 0.5 day)
1. Import the repository, Root Directory `frontend`, settings of 4.1; deploy.
2. Open the Vercel URL signed out: you land on `/login`. Sign in; reload keeps the session; sign out returns to `/login`.
3. Check the cookie in the browser tools: `HttpOnly`, `Secure`, host-only, `SameSite=Lax` (4.3).
4. Smoke test through the Vercel URL, signed in:
   1. Dashboard loads, no CSP errors in the browser console, fonts and icons local.
   2. Analyze `https://github.com/spring-projects/spring-petclinic`: progress screen, completion, report, findings, dependencies, tests, architecture diagrams render.
   3. Search index one snapshot; run a symbol search.
   4. Impact analysis on a picked symbol.
   5. Deep link refresh (`/repositories/<id>?tab=...`) works (SPA fallback), and when signed out it goes to the login and back to the same page.
   6. Workspace writable: the analysis in step 2 proves the volume permissions; check Railway logs for `AccessDenied`/permission errors.
   7. **AI with Gemini:** AI Settings shows Gemini active and up; run one insight; confirm the call stays within the Vercel rewrite time limit (4.3) and the usage page counts it.
5. Negative checks: the Railway API called directly without a session (401); a state-changing call without the CSRF header (403); a long GitHub rate-limit case shows the retry time.

### Phase 4: Hardening and operations (about 0.5 day)
- [ ] Railway: usage alert and a hard spend limit **(verify availability)**; restart policy set; one replica.
- [ ] Google Cloud / AI Studio: a budget or quota alert for the Gemini key.
- [ ] Backups: scheduled `pg_dump` (a Railway cron service or an external job) **or** Railway volume backups **(verify)**; do one test restore into a scratch database.
- [ ] Secrets rotation procedure written down (section 7), including the password hash.
- [ ] Log review: structured JSON with redaction; confirm no token, password, cookie, API key or repository secret appears in Railway logs (search for `ghp_`, `github_pat_`, `AIza`, `REPOINTEL_SESSION`).
- [ ] Optional: a custom domain for the Vercel site; none for the backend.

### Phase 5: Optional later
- Per-feature Gemini models (a cheaper model for summaries, Appendix C.4) and Gemini embeddings for semantic search.
- Sessions that survive redeploys (Appendix C.8).
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
| `AUTH_PASSWORD_HASH` (and `AUTH_USERNAME`) | Railway variable only; the plain password lives in your password manager | Generate a new hash, change the variable, redeploy. All sessions end (they are in memory), which is the point |
| `DB_PASSWORD` | Railway reference to the Postgres service | Rotate in the Postgres service, redeploy the backend |
| `GEMINI_API_KEY` | Railway variable only | Revoke at Google, create a new key, replace, redeploy |

Rules (unchanged from Appendix C.1): never commit secrets, never put them in `vercel.json`, `railway.toml`, docs or chat; the backend never logs them (redaction covers cookies, passwords and `Authorization`).

---

## 8. Backup, rollback and incident handling

- **Rollback backend:** Railway "Redeploy" of the previous successful deployment. If a migration already ran, the previous code must still work with the new schema (additive migrations rule).
- **Rollback frontend:** Vercel "Promote" the previous production deployment (instant).
- **Database loss:** restore the latest dump. All results are **derived data**: if there is no backup, re-run the analyses (each is one GitHub download per commit). This is why backups are useful but not critical.
- **Leaked token or secret:** rotate per section 7, then check Railway logs and GitHub's token activity.
- **Abuse or cost spike:** change `AUTH_PASSWORD_HASH` and redeploy (ends every session and locks out whoever had access), lower `repository.submission.rate-limit.capacity` / `max-pending-jobs`, and pause the service in Railway if needed.

---

## 9. Costs and sizing (indicative, verify current prices)

- **Railway:** usage based (compute, memory, volume, egress) after the plan's included credit. The backend is the main cost because of memory (2 GB target) while analyses run; it is mostly idle otherwise. PostgreSQL and a 5 GB volume are small. Set a spend limit.
- **Vercel:** the static site is small and there is no function. The allowed duration of a rewritten request depends on the plan (4.3).
- **Third parties:** GitHub API is free with a token. Gemini is pay-per-use or quota-limited depending on your key; `AI_MAX_CALLS_PER_ANALYSIS=5`, the per-snapshot Q&A budget and the login keep spend bounded.

---

## 10. Risks and open points

| Risk | Impact | Mitigation |
|---|---|---|
| Login not enforced by the backend (or `AUTH_ENABLED` left false) | Open API on the public internet | MVP 8 enforces it server-side; the app refuses to start with a bad hash; Phase 2 checks 401 from outside; never deploy before MVP 8 |
| Password guessing against the login | Account takeover | BCrypt cost 12, throttle with `Retry-After`, a long random password |
| Session lost on every backend redeploy | You must sign in again | Accepted; Spring Session JDBC is the upgrade (Appendix C.8) |
| Vercel rewrite changes cookies, body or timing | Login or AI calls fail | Verified in Phase 3; fallback is a small forwarding function (4.3) |
| Vercel limit on a rewritten request shorter than AI call time | Insight/Q&A calls fail with 504 | Keep `AI_TIMEOUT_SECONDS` below the limit (4.3) |
| Non-root `app` user cannot write to the volume | Every analysis fails at ingestion | Test in Phase 3; chown in an entrypoint step, or `RAILWAY_RUN_UID=0` as a last resort |
| Memory pressure (PMD + Semgrep + JVM) | Restarts during large analyses | 2 GB+ RAM, repository size cap, `max-pending-jobs`; watch metrics |
| Shared GitHub egress IP without a token | 60 requests/hour shared | Token is mandatory (3.3) |
| Scaling beyond one instance | Budget counters and job pool are in memory | Keep `numReplicas = 1`; revisit (Appendix C.4) before scaling |
| Gemini data handling | Public-repository excerpts (secrets scrubbed) leave your infrastructure and your key's terms apply | Acknowledgement variable; public repositories only; `AI_MAX_CALLS_PER_ANALYSIS=5`; check the key type's terms |
| Railway/Vercel feature or limit changes | Plan steps drift | Every **(verify)** item is checked at execution time and the result recorded in the completion note |

---

## 11. Completion checklist

- [ ] MVP 8 (login) done and its exit criteria ticked
- [ ] Backend deployed on Railway, healthy, one replica, database and volume attached, `AUTH_ENABLED=true`
- [ ] Frontend deployed on Vercel from `main`; `/api` reaches the backend through the plain rewrite
- [ ] Direct calls to the Railway API without a session return 401; Swagger and OpenAPI docs are not exposed
- [ ] Login, reload, sign out and session-expired redirect work through the Vercel URL; the cookie is `HttpOnly` and `Secure`
- [ ] spring-petclinic analysed end to end through the Vercel URL (report, architecture, search, impact)
- [ ] Gemini insight works through the Vercel URL within the time limit; the acknowledgement variable is set
- [ ] No CSP violations in the browser console; no external fonts, scripts or images
- [ ] No secret, password, cookie or API key in logs, repository, build output or browser storage
- [ ] Backup configured and one restore tested
- [ ] Spend limit and alerts set (Railway and the Gemini key)
- [ ] README, `docs/architecture.md` and `.env.example` updated; the "no auth" rule replaced by the `AUTH_ENABLED` rule
