# GitHub Repository Intelligence Platform

Evidence-backed analysis of public GitHub repositories. Submit a public repository URL and get a commit-scoped report: technologies, structure, dependencies, code quality, secrets and security rules, tests and (real) coverage, plus an honest capability matrix that says what the platform could not analyze and why. For Java/Spring Boot it also discovers the **architecture**: layers, REST endpoints with request/response types, JPA entities and relationships, dependency injection chains, Maven module dependencies, dependency cycles and design-pattern candidates, with Mermaid diagrams. It is deterministic: **no AI and no AI key are needed**, and no code from the analyzed repository is ever executed. Optionally (MVP 3), an AI provider you configure (hosted Gemini, the project default, or OpenRouter after you acknowledge their data handling, or a local Ollama) can **explain** classes, methods and findings, suggest refactorings and summarise modules and technical debt; every answer is marked AI-generated and lists the evidence it was given. MVP 4 adds **code search** that needs no model (exact symbol lookup and full-text search in one commit) and optional **repository Q&A**: answers grounded in retrieved code with source links, or an explicit "not enough evidence". MVP 5 adds **change-impact analysis**: pick a class or method and see which callers, endpoints, entities, modules and tests may be affected, from the dependency graph of the parsed code (never from a model), with a transparent heuristic risk score and an explicit list of what the analysis cannot see. MVP 8 adds an optional **server-enforced login** (one operator account, an `HttpOnly` session cookie, CSRF protection and login throttling) so the app can be deployed publicly. MVP 7 is a **UI redesign**: the whole interface was rebuilt from the Google Stitch designs (dark and light themes, a navigation shell, a findings inspector, themed architecture diagrams, designed loading, empty, error and "not supported" states) without changing what any analyzer, search or impact rule does.

See [docs/](docs/) for the problem statement, architecture, implementation plan and the completion reports for [MVP 1](docs/mvp1-completion-report.md), [MVP 2](docs/mvp2-completion-report.md), [MVP 3](docs/mvp3-completion-report.md) [MVP 4](docs/mvp4-completion-report.md), [MVP 5](docs/mvp5-completion-report.md) and [MVP 7](docs/mvp7-completion-report.md) (the UI redesign) and [MVP 8](docs/mvp8-completion-report.md) (login).

> **Open by default, login when you need it.** With `AUTH_ENABLED=false` (the default) there is no login and the stack is for local use only: ports are bound to `127.0.0.1`. **Never expose an instance with `AUTH_ENABLED=false` to a network.** With `AUTH_ENABLED=true` (MVP 8) the backend rejects every `/api/**` call that has no signed-in session; see [Authentication](#authentication-mvp-8) and the Railway/Vercel plan in [docs/deployment-plan.md](docs/deployment-plan.md).

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

What runs: `postgres` (PostgreSQL 16, data in the `pgdata` volume), `backend` (Spring Boot on JRE 25, with a pinned Semgrep CLI so the Semgrep analyzer is available) and `frontend` (the built UI on nginx, which proxies `/api` to the backend). Containers run as non-root users, and every port is bound to `127.0.0.1` only, because the default configuration has no login (see Authentication to turn it on). Analyzed repository code is still never executed.

Host ports can be changed with `POSTGRES_PORT`, `BACKEND_PORT` and `FRONTEND_PORT` in `.env`.

## How to analyze a repository

1. Start the app (with Docker above, or the backend and frontend separately below), open http://localhost:5173 and choose **Add Repository**.
2. Paste a public GitHub URL (for example `https://github.com/spring-projects/spring-petclinic`, optionally `/tree/<branch>`) and press **Analyze**. Tick *Force full rescan* to re-analyze a commit that was already analyzed.
3. Watch the progress page, then read the report: Overview (technologies, structure, capability matrix, limitations), Dependencies, Code Quality, Security, Tests & Coverage and, for Java/Spring Boot, Architecture. Use the snapshot selector to switch between analyzed commits.
4. The architecture also has its own page: `/repositories/{id}/arch?snapshot={snapshotId}` (link from the Architecture tab). Every statement shows its confidence (*Detected*, *Likely*, *Possible*) and how it was found; relationships to code outside the repository are labelled *unresolved*, never guessed.

5. Optional AI insights: with AI configured (below), use **AI** next to a class on the architecture page (explain the class, suggest refactorings, explain a method), **Explain this finding** in a finding's detail panel, **Summarize technical debt** on the Code Quality tab and **Summarize module** on the Overview tab. **AI Settings** (`/settings/ai`) shows providers, health, data handling, limits and usage.

API documentation: http://localhost:8080/swagger-ui.html. Flow: `POST /api/v1/repositories/analyze` returns a run id; poll `GET /api/v1/analysis-runs/{runId}`; read `GET /api/v1/repositories/{repositoryId}/reports/{snapshotId}` and the findings, dependencies, tests, coverage and architecture endpoints under `/snapshots/{snapshotId}/`. MVP 3 adds `GET .../snapshots/{snapshotId}/symbols/{symbolId}` (a type with its members), `POST .../snapshots/{snapshotId}/insights` (`{type, targetId, provider?}`; types `EXPLAIN_CLASS`, `EXPLAIN_METHOD`, `EXPLAIN_FINDING`, `SUGGEST_REFACTORING`, `SUMMARIZE_MODULE`, `TECH_DEBT_SUMMARY`), `GET /api/v1/ai/providers` and `GET /api/v1/ai/usage`. With AI disabled the insight endpoint answers `200` with `status: UNAVAILABLE` and a reason. MVP 4 adds `POST`/`GET .../snapshots/{snapshotId}/index` (index job and state), `GET .../snapshots/{snapshotId}/search?q=&type=symbol|text&language=&module=&fileType=&limit=` and `POST .../snapshots/{snapshotId}/questions` (`{question, conversationId?, provider?}`; statuses `OK`, `INSUFFICIENT_EVIDENCE`, `NOT_INDEXED`, `UNAVAILABLE` with deterministic `searchResults`, `SKIPPED`, `RATE_LIMITED`, `TIMEOUT`, `INVALID_OUTPUT`, `ERROR`). MVP 7 adds `GET /api/v1/repositories/stats` (dashboard tiles: repositories analyzed, indexed for search, open findings or `null`, active runs), a `summary` on every repository in `GET /api/v1/repositories` and `GET .../{repositoryId}` (primary language, frameworks, dependency count, findings by severity with `null` for not analyzed, index state, run status and duration; `null` until a run has completed), `run.durationMs`, `run.analyzerSetHash` and `dependencyCount` on the report, `reason` on each analyzer execution of a run, and the `analyzer` and `q` filters on `GET .../snapshots/{snapshotId}/findings`. MVP 5 adds `GET .../snapshots/{snapshotId}/impact-analysis/symbols?q=` (the deterministic symbol picker), `POST .../snapshots/{snapshotId}/impact-analysis` (`{targetSymbolId, depth?, changeDescription?, includeAiSummary?, provider?}`: affected components with confidence and evidence, endpoints, related entities and repositories, modules, tests, regression areas, risk factors, limitations, a Mermaid chain; `aiSummary` only when asked and clearly marked) and `POST .../snapshots/{snapshotId}/impact-analysis/suggestions` (`{changeDescription}`, AI only: proposes symbols that exist in the snapshot, `UNAVAILABLE` without AI).

## The interface (MVP 7)

The UI follows the Stitch designs in `stitch_repointel_ui_platform/` (source of truth: the PNGs and `DESIGN.md`; the exported `code.html` files are never used, because they load fonts and scripts from CDNs that the CSP blocks). Tokens, components and states are rebuilt in React and Tailwind 4.

| Screen | Route | Notes |
|---|---|---|
| Repositories dashboard | `/` | KPI tiles, rows with language, SHA, run status, findings by severity and index state; filters; Retry for failed runs |
| Add Repository | `/repositories/new` | example chips, force-rescan, inline validation; `?example=owner/name` prefills |
| Analysis progress | shown on the report route while a run is active | stepper, analyzer table with reasons, elapsed time, cancel, failed / cancelled / rate-limited variants |
| Report (Overview, Dependencies, Code Quality, Security, Tests & Coverage, Architecture) | `/repositories/:id?tab=...&snapshot=...` | the tabs are the sidebar's *Diagnostics* group; old URLs still work |
| Architecture | `/repositories/:id/arch` | pan / zoom / full-screen diagram cards that follow the theme |
| Code Search, Ask, Impact Analysis | `/repositories/:id/search`, `/qa`, `/impact` | filters and the query live in the URL |
| AI Settings | `/settings/ai` | read-only limits, health re-check, the real environment variable names |
| Investigations | `/repositories/:id/investigations` | **placeholder**: needs MVP 6, which is not built (see Known limitations) |

- **Themes.** `light`, `dark` (default) or `system`, from the toggle in the header; the choice is kept in this browser and applied before the first paint. Text, badge and chip colours are checked against WCAG AA by `frontend/src/styles/contrast.test.ts`; where `DESIGN.md` failed that check (muted text, light-theme semantic colours) the values were adjusted and the changes are listed in the MVP 7 completion report.
- **Fonts and icons are self-hosted.** Geist and JetBrains Mono are bundled from `@fontsource-variable/*` (SIL Open Font License 1.1, which allows bundling and redistribution) and served from the app's own origin; icons are inline SVG. The nginx Content-Security-Policy is unchanged and the build is scanned for external hosts (`frontend/src/test/bundle.test.ts`). Material Symbols is not used.
- **Honesty rules still apply to every screen.** "Not analyzed", "Not supported", "Not checked" and "Unavailable" are neutral, deliberate states with a reason, never zeros and never red. AI text appears only on the violet AI surface with the AI-generated badge, the provider and model, and its evidence. There is no invented telemetry: the designs' "daemon", "SQLite", per-file parse counts, health scores and similar elements were dropped (the list is in the completion report).
- **Screenshots** (taken from the built app with `pnpm e2e` and `E2E_SCREENSHOTS`): [dashboard](docs/screenshots/dark-dashboard.png), [findings with the inspector drawer](docs/screenshots/dark-tab-code-quality.png), [impact analysis](docs/screenshots/dark-impact.png), [AI settings](docs/screenshots/dark-ai-settings.png) and the [overview in the light theme](docs/screenshots/light-overview.png).
- **Dev component sheet.** `pnpm dev` also serves `/dev/components`, every primitive in every state in both themes (not in the production build).
- **Browser check (optional).** `pnpm e2e` (after `npx playwright install chromium`) opens the main screens in both themes against a running stack (`E2E_BASE_URL`, default http://localhost:5173) and fails on console errors, CSP violations, requests to other origins and unrendered diagrams. It is not part of `pnpm test` or the main CI jobs; `.github/workflows/browser-check.yml` runs it against Docker Compose on demand (manual trigger, not yet run in Actions).

## GitHub token

Set `GITHUB_TOKEN` to a **fine-grained token with read-only access to public repositories** (no scopes needed for public data). Without it GitHub allows only 60 API requests per hour per IP; with it, 5,000. The token is sent only to GitHub (`api.github.com`, `codeload.github.com`), never logged, and redacted from logs and reports.

## Optional tools and features

- **Semgrep** (security rules for Java). Already included in the Docker backend image. Otherwise not required: if the `semgrep` CLI is not found the analyzer is reported as *Unavailable* with the reason, and everything else still runs. Install natively (`pip install semgrep`; on Windows run it through WSL if native support is insufficient) or point to it with `analysis.analyzers.semgrep-security-analyzer.options.command`. Rules are vendored in `backend/src/main/resources/semgrep-rules/`; nothing is downloaded at runtime and metrics are off.
- **OSV.dev vulnerability lookup** for dependencies. Off by default (`analysis.vulnerabilities.osv.enabled=false`) because it needs outbound network access. When off, every dependency shows *Not checked*, which never means "no known vulnerabilities". When on, only package names and versions are sent, and only for dependencies with a concrete resolved version.

## AI insights (optional)

AI is **off by default** and nothing in the platform needs it. With `AI_ENABLED=false` no provider client is created, nothing is sent anywhere, and every AI button says why it is unavailable.

> **This project uses Gemini, not Ollama, including for local development.** `AI_DEFAULT_PROVIDER` defaults to `gemini`, and the author's own `.env` uses a Gemini API key. Ollama is still supported as an alternative (below) but is not the default and is not what the sample data, screenshots or deployment plan assume. AI stays **off** until you set `AI_ENABLED=true`, enable the Gemini provider and acknowledge its data handling (see "Hosted: Gemini" below).

**What is sent.** Only bounded evidence for one request: the target's declaration (from the parser), its source excerpt (at most `ai.source.max-lines` lines), directly related types (declarations only), findings in range, or deduplicated findings for the debt summary. Never the whole repository. Before anything is hashed, cached or sent, lines with a possible secret are withheld (stored secret findings, the secret scanner's rules on every line, whole PEM private-key blocks) and `SecretRedactor` masks the rest. Repository text is placed in delimited data blocks after a fixed system prompt that tells the model it is untrusted data. Answers must match the template's JSON schema; anything else is shown as *invalid output*, never displayed.

**Local alternative (private code that must not leave your machine): Ollama.** Not the project default. Install Ollama, `ollama pull <model>`, then in `.env`:

```sh
AI_ENABLED=true
AI_PROVIDERS_OLLAMA_ENABLED=true
OLLAMA_CHAT_MODEL=<model>        # e.g. the model you pulled; nothing is assumed
# outside Docker only: OLLAMA_BASE_URL=http://localhost:11434 (Docker uses http://host.docker.internal:11434)
```

**Hosted: Gemini (the default provider here) or OpenRouter.** First read the provider's **current** terms, quotas, pricing and data-use policy: free tiers and model lists change, and nothing here assumes a model is free. Excerpts (secrets scrubbed) are sent to the provider, so a hosted provider is only used after you acknowledge that:

```sh
AI_ENABLED=true
AI_DEFAULT_PROVIDER=gemini                     # the default; or openrouter / ollama
AI_PROVIDERS_GEMINI_ENABLED=true
GEMINI_API_KEY=...                             # .env only; never commit it
GEMINI_CHAT_MODEL=<model id>                   # list the ids your key can use: GET /v1beta/models
GEMINI_THINKING_LEVEL=low                      # default; empty = not sent (for models that reject it)
AI_PROVIDERS_GEMINI_DATA_HANDLING_ACKNOWLEDGED=true
# OpenRouter: AI_PROVIDERS_OPENROUTER_ENABLED, OPENROUTER_API_KEY, OPENROUTER_CHAT_MODEL,
#             AI_PROVIDERS_OPENROUTER_DATA_HANDLING_ACKNOWLEDGED
```

Then `docker compose up -d` (recreates the backend). Check `/settings/ai`: the provider should be *active* and *up*.

**Gemini thinking.** Gemini models think before answering, and thinking tokens count against the same output limit as the answer (and are billed as output). The adapter therefore sends `thinkingConfig.thinkingLevel` (`GEMINI_THINKING_LEVEL`, default `low`) and adds a 2048-token thinking allowance on top of each answer's limit. Without this, a summary could spend its whole limit thinking and be cut off (`INVALID_OUTPUT`, now reported as "stopped at the output limit"). Very new models can also be overloaded on free keys (timeouts, HTTP 503): if a model keeps timing out, pick an older stable one.

**Rules the router enforces.** The order is the user's preferred provider (stored per browser on the AI settings page), else `ai.features.<feature>.provider`, else `ai.default-provider`, then `ai.fallback-providers`. A hosted provider is never used without its acknowledgement and only for public repositories (`ai.allow-hosted-for-private-repositories=false`), so a local provider is never silently replaced by a hosted one. Rate-limited providers are skipped until their reset time (no retry storm); timeouts and 4xx are never retried, connection errors and 5xx at most `ai.limits.max-retries` times.

**Limits and accounting.** `ai.limits.max-input-tokens` (12000, estimated at `ai.token-estimate.chars-per-token`), `max-output-tokens` (1500, `AI_MAX_OUTPUT_TOKENS`; an upper bound for the visible answer, each prompt template has its own lower limit and the smaller applies), `max-calls-per-analysis` (10 provider calls per analysis run, `AI_MAX_CALLS_PER_ANALYSIS`; cached answers are free and still served), `timeout-seconds` (60, `AI_TIMEOUT_SECONDS`; keep it well below the frontend nginx `proxy_read_timeout` of 120 s), `max-retries` (1). Only validated answers are cached; timeouts and invalid answers are not, so a retry after a failure calls the provider again. Answers are cached for `ai.cache.ttl` (7 days) per provider, model, prompt version, commit and prompt. Every call and cache hit is recorded (`/api/v1/ai/usage`): provider-reported tokens are kept apart from estimates, and cost is shown only when you set `input-price-per-million-tokens` and `output-price-per-million-tokens` for the provider.

**Source for insights.** The extracted source is normally deleted after an analysis. An insight that needs code rebuilds the workspace once from the commit's tarball (one GitHub request), and the regular sweep removes it again (within `repository.workspace.sweep-interval`). Set `ai.source.allow-download=false` to use parser facts only.

## Search and Q&A (MVP 4)

Open a repository and choose **Search code** or **Ask a question** (routes `/repositories/:id/search` and `/repositories/:id/qa`).

- **Symbol search** works right after the analysis and needs nothing else: an exact identifier (`OwnerController`, `Owner.getPets`, `org.x.Owner#getPets`), looked up in the MVP 2 symbol graph (Java). No prefix or fuzzy matching.
- **Index this snapshot** (button on both pages, or `POST /api/v1/repositories/{id}/snapshots/{snapshotId}/index`) builds the snapshot's search index: text files are secret-scrubbed exactly like AI prompts, then split into Java class and method chunks (with Javadoc; test sources too), YAML and properties sections, Markdown sections, or labelled 60-line windows for other languages. Lockfiles, minified files and files over 256 KB are skipped and reported. It needs no AI and runs on its own thread, separate from analyses. If the extracted source was already deleted, it is rebuilt once from the commit's tarball (one GitHub request) and removed again afterwards.
- **Text search** (after indexing): every word must match; identifiers also match by their camelCase parts (`findByLastName` matches *last name*). Filters: language, module, file type.
- **Semantic search** needs an embedding model, configured separately from the chat model. With AI enabled and Ollama: `ollama pull nomic-embed-text`, then `OLLAMA_EMBEDDING_MODEL=nomic-embed-text` (any Ollama embedding model works; nothing is assumed). Gemini: `AI_EMBEDDING_PROVIDER=gemini`, `GEMINI_EMBEDDING_MODEL=<model id>` and the Gemini acknowledgement. Embeddings are stored per content hash, model and model version (the Ollama digest, Gemini's model version), so a new commit only embeds changed chunks, and changing the model creates new vectors without touching the old ones. There is no fallback between embedding providers: vectors of different models are not comparable. The Docker database includes **pgvector** (`db/Dockerfile`); elsewhere vectors are ranked in the application (`RAG_VECTOR_STORE=auto|pgvector|in-process`).
- **Q&A**: the question is answered from the top chunks of a hybrid retrieval (keyword, symbol and semantic results, fused with reciprocal rank fusion). The prompt goes through the same AI gateway as insights (secret scrub, token budget, cache, schema validation, usage record) with its own budget of `RAG_MAX_QUESTIONS_PER_SNAPSHOT` (50) provider calls per snapshot. Every citation in the answer is checked against the chunks that were actually sent; unknown ones are removed and reported, and an answer that cites no retrieved chunk is not shown as grounded. When nothing relevant is found, or the model says the evidence is insufficient, the answer says so and lists what was searched. Follow-ups see only a summary of the last three turns. Without a model, the answer is *AI unavailable* plus deterministic search results for the question's words.

## Impact analysis (MVP 5)

Open a repository and choose **Impact analysis** (route `/repositories/:id/impact`). Find a class or method by name (the picker is a plain database search, no AI), choose a depth and press **Analyze impact**.

- The result comes from the stored AST graph (MVP 2 symbols and edges) and from what the tests name (`test_reference`, filled by the Java test analyzer): direct and indirect callers to the chosen depth, REST endpoints whose handler reaches the target, repositories and entities connected to it, Maven modules that contain or depend on it, tests, and suggested regression areas. Every entry says how it was reached and carries a confidence: **Detected** (every hop is a resolved call, injection or inheritance edge), **Likely** (type use only, naming convention) or **Possible** (dynamic dispatch, ambiguous overloads, a test that only names the type).
- The **heuristic risk score** adds six visible factors (depth, number of dependents, public API exposure, security findings on the path, test presence, measured coverage) out of 100. It ranks changes; it is not a probability.
- The **limitations** list states what is not seen: reflection, runtime wiring, generated code, framework-invoked callers, calls with an unknown receiver type, unresolved or ambiguous relationships (with counts for the snapshot).
- Optional AI (never needed): a change description can be turned into suggested symbols (you confirm one), and **Analyze and summarize with AI** adds a clearly marked summary of the result. The graph result is identical with AI off.
- Snapshots analysed before MVP 5 have no test references: re-run the analysis with *Force full rescan* to link tests to the code they call (the page says so).

## Authentication (MVP 8)

Off by default. `AUTH_ENABLED=false` keeps the application open (local use only; Docker Compose binds every port to `127.0.0.1`). **Never expose an instance with `AUTH_ENABLED=false`.** For a shared or public deployment set `AUTH_ENABLED=true`: the **backend** then answers `401` to every `/api/**` call without a valid session (the login page is only the interface for that rule). Open paths: `/actuator/health`, `GET /api/v1/auth/session`, `POST /api/v1/auth/login`, `POST /api/v1/auth/logout`.

| Variable | Meaning |
|---|---|
| `AUTH_ENABLED` | `false` (default) or `true`. With `true` the application **refuses to start** unless the two values below are valid |
| `AUTH_USERNAME` | The one account name |
| `AUTH_PASSWORD_HASH` | BCrypt hash (`$2a$`, `$2b$` or `$2y$`, cost 12 recommended) of the password. Never the password itself, and never commit a real one |
| `AUTH_SESSION_IDLE_TIMEOUT` / `AUTH_SESSION_ABSOLUTE_TIMEOUT` | Optional, defaults `PT2H` and `PT12H` |

Create the hash (pick one; the password is not echoed and not stored):

```sh
# Apache htpasswd (Linux/macOS, or Git Bash if it ships htpasswd)
htpasswd -nbBC 12 "" 'a-long-password' | cut -d: -f2 | tr -d '\n'

# No extra tools: Java 25 and the project's own dependencies (from backend/)
./mvnw -q dependency:build-classpath -Dmdep.outputFile=target/cp.txt -DincludeScope=runtime
java -cp "$(cat target/cp.txt)" tools/HashPassword.java          # Git Bash / Linux / macOS
java -cp (Get-Content target/cp.txt) tools/HashPassword.java     # PowerShell
```

Where to put it:

- **Docker Compose / `.env`:** every `$` in the hash must be written `$$`, for example `AUTH_PASSWORD_HASH=$$2a$$12$$abc...`. Compose would otherwise try to substitute `$2a`, `$12` and so on.
- **Railway's variable editor, a shell `export` (single quotes) or an IDE run configuration:** paste the hash exactly as printed.

Run the full stack locally with the login on:

```sh
# .env
AUTH_ENABLED=true
AUTH_USERNAME=operator
AUTH_PASSWORD_HASH=$$2a$$12$$...   # note the doubled dollars
docker compose up --build -d       # then open http://localhost:5173 and sign in

# without Docker (backend/, bash): note the single quotes around the hash
AUTH_ENABLED=true AUTH_USERNAME=operator AUTH_PASSWORD_HASH='$2a$12$...' ./mvnw spring-boot:test-run
# and in frontend/: npx -y pnpm@12.21.0 dev  (the Vite dev server proxies /api to :8080)
```

How it behaves:

- **Session:** server-side, in memory; cookie `REPOINTEL_SESSION` (`HttpOnly`, `SameSite=Lax`, `Secure` in the `prod` profile). Ends after 2 hours without a request, or 12 hours after login regardless. A new session id is issued at every login; logout invalidates it on the server. A backend restart ends all sessions.
- **CSRF:** the `XSRF-TOKEN` cookie must be copied into an `X-XSRF-TOKEN` header on every `POST`, `PUT`, `PATCH` and `DELETE` (the web app does this). Without it the call is `403`, even when signed in.
- **Throttling:** 5 failed logins per client address and per username within 15 minutes lock that key for 15 minutes (`429` with `Retry-After`; even the right password is refused meanwhile). A success clears the counters. Every credential failure returns the same message, "Invalid username or password".
- **Production profile (`SPRING_PROFILES_ACTIVE=prod`):** Swagger UI and `/v3/api-docs` are off, only `/actuator/health` is exposed, forwarded headers are honoured behind a proxy, and the session cookie is always `Secure`.
- **Rotating the password:** generate a new hash, change `AUTH_PASSWORD_HASH`, redeploy. All sessions end.
- **Deployment:** [docs/deployment-plan.md](docs/deployment-plan.md) (Railway backend, Vercel frontend with a plain `/api` rewrite).

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
| Login (MVP 8) | `auth.enabled` (`AUTH_ENABLED`), `auth.username` (`AUTH_USERNAME`), `auth.password-hash` (`AUTH_PASSWORD_HASH`), `auth.session.{idle-timeout, absolute-timeout}`, `auth.throttle.{max-failures, window, max-tracked-keys}`; session cookie settings `server.servlet.session.cookie.*` |
| AI (optional) | `ai.enabled` (`AI_ENABLED`), `default-provider`, `fallback-providers`, `features.<feature>.{provider,model}`, `providers.<id>.{enabled, base-url, api-key, chat-model, data-handling-acknowledged, allowed-hosts, max-context-tokens, structured-output, input-price-per-million-tokens, output-price-per-million-tokens, currency}`, `limits.*`, `cache.{enabled,ttl}`, `token-estimate.chars-per-token`, `source.{allow-download,max-lines}`, `health-cache-ttl`, `rate-limit-cooldown`, `allow-hosted-for-private-repositories`, `setup-guide-url`; prompt templates in `backend/src/main/resources/ai/prompt/` |
| Embeddings (optional) | `ai.providers.<id>.embedding-model` (`OLLAMA_EMBEDDING_MODEL`, `GEMINI_EMBEDDING_MODEL`), `ai.embedding.{provider (AI_EMBEDDING_PROVIDER), batch-size, max-input-chars, max-batches-per-index}` |
| Impact analysis | `impact.{default-depth (IMPACT_DEFAULT_DEPTH), max-depth (IMPACT_MAX_DEPTH), max-components, max-graph-rows, max-picker-results, suggestion-candidates, diagram-nodes}` |
| Search index and Q&A | `rag.indexing.{allow-download, max-file-kb, max-files, max-chunks, max-chunk-lines, window-lines, window-overlap, excluded-files, queue-capacity}`, `rag.retrieval.{top-k, candidates, rrf-k, vector-store (RAG_VECTOR_STORE), max-in-process-vectors, max-search-results}`, `rag.qa.{max-question-chars, history-turns, history-answer-chars, max-questions-per-snapshot (RAG_MAX_QUESTIONS_PER_SNAPSHOT)}` |

## Known limitations

- Impact analysis (MVP 5): Java and Spring only, static and without a classpath. Calls on library types, chained expressions, lambdas and static imports have no resolved target; reflection, runtime wiring, generated code, callers outside the repository and framework-invoked code are not seen. Dynamic dispatch is approximated by method name and parameter count in the project's own supertypes and is always labelled Possible. Tests are linked by the names in their source; tests that reach code only through HTTP (MockMvc) are not linked. The graph traversals are relational PostgreSQL queries; measured latencies are in the MVP 5 completion report.
- UI (MVP 7): the **Investigations** screens (start and history, progress, report, audit trail) are not built because they need the MVP 6 agent workflow; the route shows an explicit placeholder. The design's log stream, SARIF export, Compare, follow-up chips, thumbs up/down, Save Policy and similar elements were intentionally dropped because the backend has nothing behind them (see the MVP 7 completion report for the full list). Search and analyzer filters on findings are applied by the server; the symbol picker is a search box with a result list rather than a combobox with arrow-key navigation.
- Only **direct** dependencies are inventoried; transitive dependencies are not resolved.
- Versions supplied by **remote parents or BOMs** (for example `spring-boot-starter-parent`) are not resolved and are labelled as such.
- Tests are **never executed**. Coverage appears only when a JaCoCo report is committed in the repository and may be stale; otherwise it says "unavailable", never 0%.
- "Outdated dependency" detection is not implemented.
- Deep analysis exists for Java/Maven; other stacks get generic analysis (structure, secrets, test files, npm dependencies) and an honest "unsupported" matrix, including for architecture.
- Architecture discovery reads source only, with **no classpath**: types and inherited methods from libraries are not resolved (kept by name, labelled *unresolved*); calls are resolved only when the receiver's declared type is visible; reflection, proxies, conditional beans, `@Bean` methods, XML configuration, interface-declared or functional endpoints and Gradle modules are not analysed; test sources are excluded by default. Layers from package names alone and all pattern candidates except Spring Data repositories are *Likely* or *Possible*, never *Detected*.
- AI insights (optional) are explanations, not analysis results: they can be wrong, are marked AI-generated and list their evidence. They see at most a bounded excerpt plus parser facts (no classpath, no runtime behaviour); class source excerpts start at the declaration, so Javadoc above a class is not included. The per-analysis call budget is counted per analysis run, and an in-progress budget reservation is held in memory (one backend instance). Insights for non-Java repositories are limited to findings, modules and technical debt (no symbols).
- Search and Q&A (MVP 4): indexing is a separate step per snapshot (not part of the analysis). Text search does not stem words (*policies* does not match *policy*). Symbol search covers the Java symbol graph only; other languages fall back to type and method names of indexed chunks where there are any (none for line windows). Semantic search was verified with a deterministic stand-in embedder in tests, not with a real embedding model (see the MVP 4 completion report). The Q&A budget in progress is held in memory (one backend instance), like the insight budget.
- **Login (MVP 8)** is a single operator account: no registration, no password reset by email (change `AUTH_PASSWORD_HASH` and restart), no roles, no second factor. Sessions are held in memory, so a backend restart signs everyone out, and the login throttle is per process (one replica). With `AUTH_ENABLED=false` there is no authentication at all: never expose such an instance to a network.

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

# backend with the login switched on for every MockMvc request (MVP 8; a signed-in test user and CSRF token are
# added to each request, so the whole existing suite runs behind the real filter chain)
./mvnw clean verify -Dspring.profiles.active=authon
```

Backend database tests use embedded PostgreSQL (no H2, no Docker). The embedded binaries have no pgvector, so those tests use the in-process vector store; `PgVectorStoreTests` checks the pgvector store against a real database when `PGVECTOR_TEST_DB_URL` points to an empty test database with the extension (for example `CREATE DATABASE repointel_vector_test` in the Docker Compose database, then `PGVECTOR_TEST_DB_URL=jdbc:postgresql://localhost:5432/repointel_vector_test ./mvnw test -Dtest=PgVectorStoreTests`; it cleans that database).

The retrieval evaluation (`RetrievalEvaluationTests`, questions in `backend/src/test/resources/rag-eval/questions.yml`) writes recall@5 and MRR per retrieval mode to `backend/target/rag-eval/report.md` and fails when a mode drops below its threshold.

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
| Spring Security (login, MVP 8) | 7.1.2, managed by the Spring Boot BOM (`spring-boot-starter-security`) |
| PMD (in-process) | 7.29.0 |
| JavaParser | 3.28.2 |
| maven-model (POM reading only) | 3.9.16 |
| springdoc-openapi | 3.2.0 |
| PostgreSQL (embedded, tests) | 16.16 |
| PostgreSQL (Docker Compose) | `postgres:16-alpine` + pgvector 0.8.7 (`db/Dockerfile`, built from the checksum-pinned source release) |
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
| react-markdown (AI text, no raw HTML) | 10.1.0 (pinned) |
| networknt json-schema-validator (AI output) | 3.0.8 |
| Vitest / jsdom / MSW | 5.1 / 29.1 / 3.0 |
| Geist Variable / JetBrains Mono Variable (`@fontsource-variable/*`, self-hosted, SIL OFL 1.1) | 5.3.0 / 5.3.0 |
| vitest-axe / axe-core (accessibility tests) | 0.1.0 / 4.14.1 |
| @playwright/test (optional browser check, `pnpm e2e`) | 1.65.1 |

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
