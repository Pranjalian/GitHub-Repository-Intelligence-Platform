# MVP 1 and MVP 2 Overview: Steps and Flow

A short guide to what was built in MVP 1 (repository analysis) and MVP 2 (architecture intelligence), and how a repository flows through the platform. For full detail see [implementationPlan.md](implementationPlan.md), [architecture.md](architecture.md) and the completion reports for [MVP 1](mvp1-completion-report.md) and [MVP 2](mvp2-completion-report.md).

**In one sentence:** you give the platform a public GitHub URL; it downloads that exact commit, reads the code without ever running it, and produces an evidence-backed report of technologies, structure, dependencies, code quality, security, tests and (from MVP 2) architecture with diagrams.

**No AI is used in MVP 1 or MVP 2.** MVP 3 (section 4a) adds optional AI explanations on top, and MVP 4 (section 4b) code search (no AI) and optional Q&A; everything below still works with AI disabled. Every result comes from parsers and fixed, data-driven rules, so the same commit always gives the same report, and every statement points to its evidence (file, line, annotation). AI arrives in MVP 3 as an optional add-on that only explains what MVP 1 and 2 discovered.

---

## 1. MVP 1 steps: Basic GitHub Repository Analyzer

| Step | Title | One-liner |
|---|---|---|
| 1.1 | Database schema | Tables for repositories, commit snapshots, analysis runs, analyzer executions, files, findings, dependencies, tests and coverage. |
| 1.2 | JPA entities and repositories | Java classes mapping those tables, plus Spring Data repositories to read and write them. |
| 1.3 | GitHub API client | Talks to GitHub to look up a repository, resolve a branch to an exact commit, and download its archive, with rate-limit handling and token redaction. |
| 1.4 | Repository submission API | `POST /api/v1/repositories/analyze`: validates the URL, resolves the commit up front, reuses an earlier result for the same commit, or forces a rescan. |
| 1.5 | Snapshot workspace and ingestion | Downloads the commit's tarball and extracts it safely into a per-snapshot workspace (size, file-count and path-traversal limits; no links or devices). |
| 1.6 | Analyzer SPI, registry and capability matrix | A plug-in contract for analyzers, a registry that picks the ones that apply, and a matrix that honestly says what was and was not analyzed per technology. |
| 1.7 | Analysis orchestrator and run status API | Runs the analyzers in the background with time limits, cancellation and isolation (one failing analyzer never erases the others), saves results, and reports progress. |
| 1.8 | Generic analyzers | Work for every repository: language breakdown, technology detection from manifests, structure (modules, entry points, sizes) and test-file discovery. |
| 1.9 | Consolidated report API | One commit-scoped report endpoint combining technologies, structure, capability matrix, section summaries and limitations. |
| 1.10 | Frontend vertical slice | First working UI: add a repository, watch progress, list repositories, see the overview. |
| 1.11 | Java pack: Spring detection and Maven dependencies | Detects Spring Boot, Java version and databases with JavaParser, and lists Maven dependencies (versions from remote parents/BOMs labelled as unresolved). |
| 1.12 | Dependency vulnerability source | Optional OSV.dev lookup for known vulnerabilities; off by default, so dependencies show "Not checked" rather than "safe". |
| 1.13 | Code quality analyzer | Runs PMD with a project ruleset on Java code and stores findings with file, line and evidence. |
| 1.14 | Security analyzers | A secret scanner for every repository (values always redacted) plus Semgrep with bundled Java rules and honest coverage reporting. |
| 1.15 | Test analyzer and JaCoCo ingestion | Lists Java test classes and methods, and reads coverage only from a committed JaCoCo report (never shows 0% when there is no report). |
| 1.16 | Full report dashboard | Tabs for Overview, Dependencies, Code Quality, Security and Tests & Coverage, with filters, snapshot selector and finding details. |
| 1.17 | Integration and security testing | End-to-end tests for Java and non-Java repositories, malicious archives, SSRF, secret leakage, analyzer failure and running with AI disabled. |
| 1.18 | Documentation and completion report | README, OpenAPI annotations, `.env.example`, package-boundary test and the MVP 1 completion report. |
| 1.19 | Docker Compose | One command (`docker compose up --build`) runs PostgreSQL, the backend (with Semgrep included) and the nginx-served frontend locally. |

## 2. MVP 2 steps: Architecture Intelligence

| Step | Title | One-liner |
|---|---|---|
| 2.1 | Architecture database schema | New tables for code symbols, dependency edges, REST endpoints, layer assignments, module dependencies, JPA relationships and design-pattern candidates. |
| 2.2 | Symbol and edge extraction | Parses Java with JavaParser into classes, methods and fields, and records who injects, calls, extends or uses whom; library types are marked "unresolved", never guessed. |
| 2.3 | Spring architecture discovery | Works out controllers, services, repositories and entities, REST endpoints with request/response types, JPA relationships, DI chains, dependency cycles and pattern candidates. |
| 2.4 | Diagram generation | Builds the architecture report and turns it into Mermaid diagrams (layers, modules, API flow, entity relationships), with escaped labels and size caps; served at `GET .../snapshots/{snapshotId}/architecture`. |
| 2.5 | Architecture frontend | An Architecture tab with diagrams (Mermaid in strict mode), layer lists, endpoint table, entities, DI chains, cycle warnings and patterns; "Not supported" for non-Java repositories. |
| 2.6 | Integration testing | End-to-end checks on Spring Boot and multi-module fixtures and a non-Java fixture, golden diagram files, docs and the MVP 2 completion report. |

---

## 3. End-to-end flow

```mermaid
flowchart TD
    U["User in browser<br/>http://localhost:5173"] --> N["frontend container: nginx<br/>serves the React app, forwards /api"]
    N --> S["backend: submission API<br/>POST /api/v1/repositories/analyze"]
    S -->|"look up repo, resolve commit SHA"| GH["GitHub API"]
    S -->|"save repository, snapshot, run = QUEUED"| DB[("PostgreSQL")]
    S --> O["Orchestrator (background job)"]
    O -->|"download tarball for that SHA"| CL["codeload.github.com"]
    O --> W["Safe extraction into /workspace"]
    W --> A["Analyzers (in parallel, each with a deadline)"]
    A --> R["Orchestrator saves results<br/>(redacted, fingerprinted)"]
    R --> DB
    R --> X["Workspace deleted"]
    U -.->|"polls run status, then loads report tabs"| N
```

Step by step:

1. **Submit.** You paste a public GitHub URL and press Analyze. The backend checks the URL strictly (it never fetches a user-supplied URL directly), asks GitHub for the repository and resolves the branch to an exact commit SHA.
2. **Reuse or queue.** If that commit was already analyzed with the same analyzers, the earlier result is reused; otherwise (or with *Force full rescan*) a run is saved as `QUEUED`.
3. **Ingest.** A background job downloads the archive of that exact commit and extracts it into a temporary workspace, enforcing size and file limits and rejecting unsafe archive entries.
4. **Analyze.** Applicable analyzers run in parallel, each with a time budget. They only read files; repository code is never compiled or executed. Analyzers return results and never write to the database themselves.
5. **Save.** The orchestrator redacts secrets, fingerprints findings and stores everything scoped to that one snapshot (commit). A failed analyzer is recorded as failed; the others' results are kept.
6. **Clean up.** The extracted workspace is deleted.
7. **Report.** The UI polls the run status, then loads the report: Overview, Dependencies, Code Quality, Security, Tests & Coverage and, for Java/Spring projects, Architecture. The capability matrix states what was not analyzed and why.

### Which analyzers run

| Analyzer | Category | Runs for | MVP |
|---|---|---|---|
| Language detector, manifest technology detector | Technology | All repositories | 1 |
| Structure analyzer | Structure | All repositories | 1 |
| Secret scanner | Security | All repositories | 1 |
| Test-file discovery | Tests | All repositories | 1 |
| npm dependency analyzer | Dependencies | Repositories with `package.json` | 1 |
| Java/Spring technology detector | Technology | Java | 1 |
| Maven dependency analyzer (+ optional OSV lookup) | Dependencies | Maven | 1 |
| Java code analyzer (PMD) | Code quality | Java | 1 |
| Semgrep security analyzer | Security | Java (when Semgrep is installed; always in Docker) | 1 |
| Java test analyzer (+ JaCoCo) | Tests | Java | 1 |
| Spring architecture analyzer | Architecture | Java / Spring Boot | 2 |

## 4. How the architecture is generated (MVP 2)

```mermaid
flowchart TD
    W["Extracted Java sources in /workspace"] --> P["1. Parse<br/>JavaParser builds syntax trees (no compiling)"]
    P --> G["2. Symbol graph<br/>classes, methods, fields, annotations<br/>edges: INJECTS, CALLS, EXTENDS, IMPLEMENTS, uses-type"]
    G --> D["3. Spring discovery<br/>rules from spring-architecture.yml"]
    D --> L["Layers"]
    D --> E["REST endpoints + DTOs"]
    D --> J["JPA relationships + repository-to-entity"]
    D --> C["Cycles (Tarjan)"]
    D --> PT["Pattern candidates"]
    L & E & J & C & PT --> S["4. Saved by the orchestrator<br/>(cycles also become findings)"]
    S --> RS["5. Report service<br/>DI chains + Mermaid diagram text"]
    RS --> B["6. Browser<br/>Mermaid.js (strict mode) draws the diagrams"]
```

1. **Parse.** Every production `.java` file is parsed into a syntax tree. Nothing is compiled or run.
2. **Build the symbol graph.** Classes, interfaces, records, methods and fields are recorded with their annotations, and relationships between them become edges: injection (constructor/field, `@Autowired`, Lombok `@RequiredArgsConstructor`), method calls where the receiver's type is known, inheritance and type usage. There is no classpath, so a type from a library is kept as an **unresolved** edge with its imported name; ambiguous names are marked **ambiguous**.
3. **Discover the Spring architecture** using rules from [spring-architecture.yml](../backend/src/main/resources/architecture/spring-architecture.yml) (data, not code):
   - **Layers:** `@RestController`/`@Controller` → controller, `@Service` → service, `@Repository` or a Spring Data interface → repository, `@Entity` → entity, `@Configuration` → configuration; endpoint request/response types → DTO; package names (`service`, `repository`, ...) only as a fallback.
   - **Endpoints:** class-level and method-level mappings are combined (`@RequestMapping("/owners")` + `@GetMapping("/{id}")` → `GET /owners/{id}`), with request types from `@RequestBody` and response types unwrapped from `ResponseEntity<T>`.
   - **JPA:** `@OneToMany`, `@ManyToOne`, `@OneToOne`, `@ManyToMany`, and which entity each repository manages (`JpaRepository<Owner, Integer>` → `Owner`).
   - **Cycles:** Tarjan's algorithm finds loops in injection and type-usage edges (normal two-way JPA relationships are not reported).
   - **Patterns:** judged by structure, never by names. Strategy (an interface with 2+ implementations injected as a list or map), Factory (a method creating different implementations in branches), Adapter (a class implementing an interface by delegating to a wrapped object).
4. **Save.** The orchestrator stores everything for that snapshot; dependency cycles are also stored as findings.
5. **Report.** `GET /api/v1/repositories/{repositoryId}/snapshots/{snapshotId}/architecture` assembles layers, endpoints, entities, controller → service → repository → entity chains, cycles, patterns and limitations, and generates Mermaid diagram text: names are escaped (repository text is untrusted) and large diagrams are capped with a note.
6. **Draw.** The Architecture tab gives the Mermaid text to Mermaid.js in strict mode, which draws the SVG diagrams in the browser.

**Confidence labels:**

| Label | Meaning | Example |
|---|---|---|
| DETECTED | Proven by the code | `@RestController`; an interface extending `JpaRepository` |
| LIKELY | Strong signal, not proof | Class in a `service` package; type used as an endpoint request body |
| POSSIBLE | Weak hint | Plain `@Component`; a design-pattern candidate |

**Not analyzed (shown as limitations):** library code (no classpath), reflection and runtime proxies, `@Bean` factory methods and XML wiring, Gradle modules, functional (router) endpoints, request mappings on interfaces, and calls on chained expressions.

## 4a. MVP 3 steps: AI-Powered Code Insights (optional)

| Step | Title | One-liner |
|---|---|---|
| 3.1 | Provider abstraction | `AIModelService` with request, response, capability and health types, and a router that picks the preferred, feature-routed or default provider and only permitted fallbacks (a hosted provider needs an acknowledged data handling). |
| 3.2 | Provider implementations | Ollama, Gemini and OpenRouter adapters behind one bounded HTTP helper (allowlist, no redirects, timeout, at most one retry on connection errors and 5xx, never on 429); all off by default. |
| 3.3 | Guardrails, usage and cache | `AIGateway`: enabled check, routing, secret scrub, token budget, cache, per-run call budget, schema validation, usage record for every call; `ai_usage_record` and `ai_cache` tables; providers and usage endpoints. |
| 3.4 | Templates and insight features | Six versioned prompt templates, bounded evidence from the MVP 1/2 analysis plus source excerpts, and `POST .../insights` for classes, methods, findings, refactorings, modules and technical debt. |
| 3.5 | AI insights frontend | Explain buttons on findings, classes and methods, debt and module summaries, Markdown without raw HTML, evidence links, and an AI settings page with providers, limits, preferred provider and usage. |

```mermaid
flowchart TD
    UI["Explain button"] -->|"POST .../insights {type, targetId}"| IS["InsightService"]
    IS -->|"AI off or no permitted provider"| UN["200 UNAVAILABLE + reason<br/>(nothing collected or sent)"]
    IS --> EB["Evidence from the stored analysis<br/>symbols, edges, layers, findings, modules"]
    IS --> SRC["Source excerpt<br/>(workspace rebuilt from the commit tarball if needed)"]
    EB & SRC --> GW["AIGateway"]
    GW --> SC["Secret scrub<br/>findings, scanner rules, PEM blocks, redactor"]
    SC --> BU["Token budget"] --> CA{"Cache hit?"}
    CA -->|yes| OK["Answer, marked AI-generated, with the evidence sent"]
    CA -->|no| BG{"Call budget left?"}
    BG -->|no| SK["SKIPPED"]
    BG -->|yes| PR["Provider (Ollama / Gemini / OpenRouter)"]
    PR --> VA{"Valid against the schema?"}
    VA -->|no| IN["INVALID_OUTPUT (not shown)"]
    VA -->|yes| OK
    PR -.-> US[("ai_usage_record")]
    OK -.-> AC[("ai_cache")]
```

AI only explains what the deterministic analysis found: it never changes a finding, a count or a status. Every answer says which provider and model wrote it, which prompt version was used and exactly which evidence was sent.

## 4b. MVP 4 steps: Repository search and Q&A (search needs no AI)

| Step | Title | One-liner |
|---|---|---|
| 4.1 | Chunking, keyword index and embeddings | An optional per-snapshot index job splits the commit's text files (secrets withheld first) into Java class and method chunks, YAML/properties sections, Markdown sections or labelled line windows, stores them with a PostgreSQL full-text index, and adds embeddings keyed by content hash, model and model version when an embedding model is configured. |
| 4.2 | Deterministic search and hybrid retrieval | `GET .../search`: exact symbol lookup in the MVP 2 symbol graph and full-text search with language/module/file-type filters, no model involved; hybrid retrieval fuses keyword, symbol and semantic (pgvector or in-process cosine) results with reciprocal rank fusion; a retrieval evaluation set measures recall@5 and MRR. |
| 4.3 | Q&A service and API | `POST .../questions`: retrieval, a bounded prompt through the AI gateway (all MVP 3 guardrails, own per-snapshot budget), and an answer whose citations are checked against the chunks that were sent; "not enough evidence" is said explicitly; without a model, deterministic search results are returned instead. |
| 4.4 | Search and Q&A frontend | A Search page (always available) and a Q&A page with sources, follow-ups, the AI-generated badge and clear "AI unavailable" / "no embedding provider" states, both with the index status and an "Index this snapshot" button. |

```mermaid
flowchart TD
    IX["Index this snapshot<br/>POST .../index"] --> W["Workspace (rebuilt from the tarball if needed)"]
    W --> SC["Secret scrub (same as AI prompts)"]
    SC --> CH["Chunker<br/>Java class/method, YAML, properties, Markdown, line windows"]
    CH --> DB[("code_chunk<br/>+ tsvector keyword index")]
    CH -.->|"only new content hashes"| EM["Embedding model (optional)<br/>Ollama or Gemini"]
    EM -.-> EV[("chunk_embedding<br/>hash + model + version")]
    S["Search page<br/>GET .../search"] --> SY["Symbol lookup (code_symbol)"]
    S --> KW["Full-text search (code_chunk)"]
    Q["Q&A page<br/>POST .../questions"] --> R["Hybrid retrieval<br/>keyword + symbol + semantic, fused"]
    R --> GW["AI gateway<br/>scrub, budget, cache, schema"]
    GW --> A["Answer with validated sources,<br/>or 'not enough evidence'"]
    Q -.->|"no model"| KW
```

Search always works without AI: symbol search right after the analysis, text search once the snapshot is indexed. Every query is bound to one snapshot of one repository in the storage layer, so no answer can draw on another commit or repository. A Q&A answer is shown as grounded only when it cites at least one retrieved chunk that was actually sent; every citation links to GitHub at the exact commit.

---

## 5. Tools

| Area | Tool | Used for | Since |
|---|---|---|---|
| Backend | Java 25, Spring Boot 4.1 | REST API, background jobs | MVP 1 |
| Backend | PostgreSQL 16, Flyway, Spring Data JPA | Storage, schema migrations, data access | MVP 1 |
| Backend | springdoc-openapi | Swagger UI at `/swagger-ui.html` | MVP 1 |
| Backend | Apache Commons Compress | Safe archive extraction | MVP 1 |
| Analysis | JavaParser | Java parsing: Spring detection and tests (MVP 1), symbol graph and architecture (MVP 2) | MVP 1, core of MVP 2 |
| Analysis | PMD 7 | Java code quality | MVP 1 |
| Analysis | Semgrep 1.186 | Security rules (bundled, external process, included in the Docker image) | MVP 1 |
| Analysis | maven-model | Reading `pom.xml`: dependencies (MVP 1) and module diagram (MVP 2) | MVP 1 |
| Analysis | YAML rule files | Technology signatures, secret patterns, Spring architecture rules | MVP 1, MVP 2 |
| Analysis | OSV.dev (optional, off) | Known-vulnerability lookup | MVP 1 |
| External | GitHub REST API, codeload | Commit resolution, archive download | MVP 1 |
| Frontend | React 19, TypeScript, Vite, Tailwind CSS | Dashboard UI | MVP 1 |
| Frontend | TanStack Query, React Router | Data fetching and polling, page routes | MVP 1 |
| Frontend | Mermaid 11.17.2 | Architecture diagrams (strict mode, loaded on demand) | MVP 2 |
| Runtime | Docker, Docker Compose, nginx | One-command local run; nginx serves the UI, proxies `/api`, sets security headers | MVP 1 (Step 1.19) |
| Testing | JUnit 5, embedded PostgreSQL (zonky), WireMock | Backend tests on real PostgreSQL without Docker; fake GitHub | MVP 1 |
| Testing | Vitest, Testing Library, MSW | Frontend tests with a mocked API | MVP 1 |
| Quality | Spotless, ESLint, TypeScript | Formatting, lint and type checks | MVP 1 |
| AI (optional) | Ollama, Gemini, OpenRouter | Explanations behind `AIModelService`; all off by default | MVP 3 |
| AI (optional) | networknt json-schema-validator 3.0.8 | Validates every structured AI answer | MVP 3 |
| Search | PostgreSQL full-text search (`tsvector`, `simple` configuration) | Keyword index of code chunks | MVP 4 |
| Search (optional) | pgvector 0.8.7 (in the Docker database image) | Semantic search; without it vectors are ranked in the application | MVP 4 |
| AI (optional) | Ollama `/api/embed`, Gemini `batchEmbedContents` | Embeddings, separate from the chat model; off by default | MVP 4 |
| Frontend | react-markdown 10.1.0 | AI text as Markdown, without raw HTML, images or links | MVP 3 |
| CI | GitHub Actions | Backend, frontend and Docker image jobs on every push to `main` | MVP 1 |

## 6. Quick start

```sh
cp .env.example .env          # optional: set GITHUB_TOKEN (see implementationPlan.md, Appendix C.1)
docker compose up --build     # UI: http://localhost:5173  API: http://localhost:8080
```

Then choose **Add Repository**, paste a public GitHub URL (for example `https://github.com/spring-projects/spring-petclinic`) and open the report; Java/Spring repositories also get the **Architecture** tab. See the [README](../README.md) for running without Docker, configuration and troubleshooting.
