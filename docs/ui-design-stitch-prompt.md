# Google Stitch Prompts: UI Redesign

How to use:
1. Paste the **main prompt** (section 1) into Google Stitch first. It gives the full product context and asks for the core screens.
2. Then refine one screen at a time with the **follow-up prompts** (section 2). Stitch produces better results from focused prompts than from one huge request.
3. If Stitch rejects the main prompt as too long, paste sections A–D first ("Context") and then section E ("Screens") as the next message.

Screenshots of the current app are optional. Leave them out on the first pass, so Stitch designs freely instead of copying today's plain layout. Use them later only to say "keep this information, improve this look".

---

## 1. Main prompt (paste this first)

```text
Design a modern, professional web application UI for a developer tool called "GitHub Repository Intelligence Platform" (short name: RepoIntel). Desktop-first (1440px), responsive down to tablet. Provide both light and dark themes.

A. WHAT THE PRODUCT IS
Developers often need to understand codebases they did not write: joining a team, taking over a legacy app, investigating a production issue, planning a refactor, reviewing a pull request, or assessing technical debt and security. Today that means hours of browsing folders, running separate tools and asking colleagues.

RepoIntel takes a public GitHub repository URL, downloads one exact commit, and produces an evidence-backed report: technology stack, repository structure, dependencies, code quality, security findings, tests and coverage, and (for Java/Spring Boot) the application architecture with diagrams. All analysis is deterministic (parsers and scanners such as JavaParser, PMD and Semgrep); no AI is used today. The repository's code is never executed.

The product's core values, which the design must express:
1. Evidence first: every finding points to a file, line, rule and the commit it came from.
2. Honesty: it clearly says what was NOT analyzed and why. A missing metric is "Unavailable" or "Not analyzed", never shown as 0 or as "safe". Example: "Coverage unavailable" is different from "0% coverage".
3. Confidence: inferred statements are labelled DETECTED (proven by code), LIKELY (strong signal) or POSSIBLE (weak hint).
4. Commit-scoped: every report belongs to one commit SHA; users can switch between analyzed commits (snapshots).
5. Multi-language: Java + Spring Boot + Maven get deep analysis; other languages get generic analysis plus an honest "capability matrix" showing which categories are supported for each detected technology.

B. USERS
Backend and full-stack developers, developers onboarding to a codebase, tech leads and architects, maintainers doing production support, code reviewers. They are technical, comfortable with dense data, and want to scan quickly, filter, and jump to evidence. The tool is a local single-user app (no login screens).

C. VISUAL DIRECTION
- Feel: calm, precise, trustworthy engineering tool (think Linear, GitHub, Vercel, Sentry dashboards). Clean, information-dense but not cluttered; generous whitespace between sections; strong typographic hierarchy.
- Monospace font for code, file paths, commit SHAs, rule IDs, versions and routes.
- Clear colour language, consistent everywhere, and always paired with a text label or icon (never colour alone, for accessibility):
  - Severity: CRITICAL, HIGH, MEDIUM, LOW, INFO.
  - Run and analyzer status: Queued, Fetching code, Analyzing, Completed, Completed with warnings, Failed, Cancelled, Timed out, Skipped, Not analyzed, Not configured, Not supported, Unavailable.
  - Confidence: DETECTED, LIKELY, POSSIBLE.
  - Capability depth: Deep, Basic, Generic, Unsupported, Unavailable.
  - Dependency vulnerability status: Vulnerable, No known vulnerabilities, Not checked, Not checkable.
- "Not analyzed / Unavailable / Not supported" states must look deliberate and informative (neutral styling with a reason), not like errors.
- Every screen needs designed loading, empty and error states.
- WCAG AA contrast, visible keyboard focus, readable at 14px base.

D. TECHNICAL CONSTRAINTS FOR THE DESIGN
- Will be implemented in React 19 with Tailwind CSS 4: use Tailwind-friendly spacing, colours and components; define design tokens (colours, type scale, radii, shadows) for light and dark.
- No external web fonts or CDN assets (the app's security policy only allows its own files): use a system font stack, or a font that can be self-hosted.
- No images from external URLs; icons as inline SVG.
- Diagrams are rendered by Mermaid.js inside a card: design the card frame, toolbar (zoom, fit, full screen, copy source) and a collapsible "Mermaid source" section, not the diagram itself.
- Repository-derived text (file paths, class names, code excerpts) is untrusted plain text: show it in monospace blocks, never as rich formatting.

E. SCREENS TO DESIGN

1. App shell
   - Left sidebar: product logo/name, navigation "Dashboard" and "Add Repository". Collapsible.
   - Top header: page title/breadcrumb (Dashboard / owner/repo / tab), theme toggle.
   - A subtle notice that this is a local tool with no authentication.

2. Dashboard (repository list)
   - Table/cards of analyzed repositories: repository (owner/name), URL, latest commit SHA (short, copyable), last analyzed time, run status badge.
   - Primary action "Add Repository".
   - Empty state: "No repositories analyzed yet. Submit a public GitHub repository to see its technology stack, structure and findings."
   - Nice to have: small per-repository summary chips (primary language, finding counts by severity).

3. Add Repository
   - Single focused form: URL input (placeholder "https://github.com/owner/repo", also accepts ".../tree/<branch>"), checkbox "Force full rescan", primary button "Analyze" (busy state "Analyzing…").
   - Inline validation messages (unsupported host, malformed URL, not public).
   - Helpful side note: only public repositories; nothing is executed; a GitHub token raises the rate limit (60 → 5,000 requests per hour).
   - Example repositories as quick-fill chips (e.g. spring-projects/spring-petclinic).

4. Analysis progress (after submitting, and when a run is in progress)
   - Stepper of phases: Waiting in the queue → Fetching the repository code from GitHub → Detecting technologies → Running analyzers → Finished.
   - List of analyzers with live status and duration, e.g. "Language detector – Completed – 10 ms", "Java code analyzer (PMD) – Completed – 10.9 s", "Semgrep security analyzer – Running", "Dependency vulnerability lookup – Not configured: lookup disabled, dependencies are 'Not checked'".
   - Cancel button ("Cancelling…" state). Failed, cancelled and rate-limited states (with "retry after HH:MM").

5. Repository report (the main screen), with:
   - Header: owner/repo, URL, branch, commit SHA (copyable), analyzed time, run status, and a Snapshot selector to switch between analyzed commits (each option shows short SHA, date and status).
   - Tabs: Overview, Dependencies, Code Quality, Security, Tests & Coverage, Architecture (Architecture appears only when the architecture analyzer ran for this commit).
   - Each tab must clearly show when it is "Not analyzed" or "Not supported" for this repository, with the reason.

   5a. Overview tab
   - Summary cards: primary language, frameworks, modules, source files, dependencies, findings by severity, tests, coverage (or "Coverage unavailable").
   - Technology stack grouped by kind: Languages (with share %, e.g. Java 92%), Frameworks, Build tools, Package managers, Databases, Test frameworks, Deployment. Each item shows version if known, a confidence badge, and expandable evidence (file path + signal, e.g. "pom.xml – matched rule spring-boot-starter-parent").
   - Structure: top-level directories, top file extensions (small bar chart "Files by type"), modules table (module, path, build file, ecosystem), entry points and configuration files.
   - Capability matrix: rows = detected technology × category (Technology, Structure, Dependencies, Code quality, Security, Tests, Architecture); columns = analyzer, depth (Deep/Basic/Generic/Unsupported/Unavailable), execution status, reason. This is the key "honesty" element: design it as a readable heat-map style grid, not a raw table.
   - Limitations panel: list of plain-language limitations per analyzer (e.g. "Only direct dependencies are inventoried", "Tests are not executed; coverage only from committed reports").

   5b. Dependencies tab
   - Filters: ecosystem (All, Maven, npm), module, scope, vulnerability status, search.
   - Table: dependency (group:artifact or package), module, declared version, resolved version, version source (Declared, From a property, Managed by a local parent, "Managed by a remote parent or BOM (not resolved)"), scope, vulnerability status, management-entry marker.
   - Unresolved versions must be visibly labelled, not hidden.

   5c. Code Quality tab
   - Findings list with filters (severity, module, file, rule) and sort (severity, file); grouping by severity or by rule.
   - Each row: severity badge, title, rule ID, file path:line, analyzer.
   - Finding detail panel (side drawer): title, severity, description, location (file, line range, module), rule and analyzer, analysis method (Deterministic scanner / Parser / Heuristic / Graph inference), evidence excerpt in a code block with line numbers, "Suggested next step", and a link to the exact line on GitHub at that commit.

   5d. Security tab
   - Two parts: Security findings (secrets with redacted values like "aws_secret = ****", SQL injection, unsafe deserialization, weak crypto, sensitive logging…) using the same list + detail drawer, and Security coverage: which scanners ran (secret scanner, Semgrep version), languages and rule files scanned, files scanned vs skipped, and dependency vulnerability status.
   - Make clear that findings are indications requiring review, not proof of exploitability.

   5e. Tests & Coverage tab
   - Summary: test classes, test methods, methods by framework (JUnit 5, etc.).
   - Coverage: lines, branches, methods per scope, from a committed JaCoCo report, with the report's path and a "may be stale" note; when absent, a clear "Coverage unavailable: no committed JaCoCo report" state (never 0%).
   - Test inventory table (test class, framework, methods) and "Production classes without an obvious test (by naming convention)" with the note "These classes may still be tested under another name."

   5f. Architecture tab (Java / Spring Boot)
   - Summary chips: controllers, services, repositories, entities, DTOs, configuration, endpoints, relationships, unresolved/ambiguous counts.
   - Diagrams section: cards for "Layers", "API flow" (endpoint → controller → service → repository → entity), "Entity relationships" (ER diagram) and "Modules", each with toolbar and collapsible Mermaid source; notes when a diagram was truncated ("API flow: capped at 45 nodes").
   - Layered types: grouped lists (Controllers, Services, Repositories, Entities, DTOs, Configuration, Infrastructure), each type with a confidence badge and "How" (e.g. "@RestController", "Spring Data interface", "package name"), and its injected dependencies.
   - Endpoints table: HTTP method badge (GET/POST/PUT/DELETE/PATCH), route (monospace, e.g. /owners/{ownerId}/pets/new), handler (Class.method), request type, response type; "path not resolved" marker.
   - JPA entities: entity, table, relationships (OneToMany, ManyToOne, OneToOne, ManyToMany with field names), and which repository manages it.
   - Dependency injection chains: controller → service → repository → entity as readable horizontal chains.
   - Dependency cycles: warning cards listing the classes in each cycle with evidence.
   - Design pattern candidates: Strategy, Factory, Adapter, Repository with confidence (POSSIBLE/LIKELY/DETECTED) and evidence.
   - Unsupported state: "Architecture analysis is not supported for this technology" listing the detected technologies (e.g. Python, CSS) with what IS available instead.

Realistic sample data (spring-projects/spring-petclinic, commit 500158f): Java + Spring Boot + Maven; 6 controllers (OwnerController, PetController, VetController, VisitController, CrashController, WelcomeController), 3 Spring Data repositories (OwnerRepository, PetTypeRepository, VetRepository), 9 entities (BaseEntity, NamedEntity, Person, Owner, Pet, PetType, Visit, Vet, Specialty), 17 endpoints (e.g. GET /owners/{ownerId}, POST /owners/new, GET /owners/{ownerId}/pets/new), 46 dependencies, 22 findings, 24 tests, coverage unavailable, Semgrep 1.186.0, vulnerability lookup not configured.

F. OUTPUT
Produce high-fidelity screens for: App shell + Dashboard, Add Repository, Analysis progress, Report Overview, Dependencies, Code Quality (with finding detail drawer open), Security, Tests & Coverage, Architecture, and the "Not supported" state for a Python repository. Also produce a small component sheet: badges (severity, status, confidence, capability, HTTP method), tables, filter bar, finding drawer, diagram card, empty/loading/error states, snapshot selector, and the colour and typography tokens for light and dark mode.

Do not design screens for future features (AI explanations, Q&A chat, impact analysis), but keep the tab bar and sidebar extensible so such sections can be added later.
```

---

## 2. Follow-up prompts (one screen at a time)

Use these after the main prompt to refine individual screens.

**Report Overview**
```text
Refine the Repository Report "Overview" tab. Make the top summary cards scannable at a glance, the technology stack grouped by kind with confidence badges and expandable evidence (file path + matched rule), and redesign the capability matrix as an easy-to-read grid (technology rows × category columns: Technology, Structure, Dependencies, Code quality, Security, Tests, Architecture), where each cell shows depth (Deep/Basic/Generic/Unsupported/Unavailable) and status, with the reason on hover. Unsupported must look informative, not like an error.
```

**Findings (Code Quality and Security)**
```text
Refine the findings experience used by the Code Quality and Security tabs: a filter bar (severity, module, file, rule, search), a dense list grouped by severity, and a right-side detail drawer showing severity, rule ID, analyzer, analysis method, location (file:line-range at commit 500158f), the evidence excerpt as a code block with line numbers and the matched line highlighted, a redacted secret example ("password = ****"), a "Suggested next step" box and an "Open on GitHub" link. Include empty ("No findings") and "nothing matches these filters" states.
```

**Architecture**
```text
Refine the Architecture tab for a Spring Boot repository. Top: summary chips (6 controllers, 3 repositories, 9 entities, 17 endpoints, unresolved edges count). Then a 2-column grid of diagram cards (Layers, API flow, Entity relationships, Modules) with zoom/fit/full-screen/copy-source toolbar and a collapsible Mermaid source. Then tabs or sections for Layered types (with confidence badge and "How" evidence such as "@RestController"), Endpoints table with coloured HTTP method badges and monospace routes, JPA entities with relationship chips (OneToMany Owner.pets → Pet), DI chains as horizontal arrow chains, dependency-cycle warning cards, and design-pattern candidates with confidence. Also show the "not supported for Python" state.
```

**Analysis progress**
```text
Refine the Analysis Progress screen: a horizontal stepper (Queued → Fetching code → Detecting technologies → Running analyzers → Finished), a live list of analyzers with status icons and durations (Completed 10 ms, Running with spinner, Not configured with reason, Failed with short error), elapsed time, and a Cancel button. Add variants for Failed, Cancelled, and GitHub rate-limited ("Retry after 18:24").
```

**Dashboard and Add Repository**
```text
Refine the Dashboard and Add Repository screens. Dashboard: repository cards or rows with owner/name, primary language chip, short commit SHA with copy button, last analyzed (relative time), run status badge, and finding counts by severity; a prominent "Add Repository" button; a friendly empty state. Add Repository: one focused card with the URL field, inline validation, "Force full rescan" checkbox, Analyze button, example repository chips, and a short info panel (public repositories only, code is never executed, GitHub token raises the rate limit).
```

**Design system**
```text
Create the design system sheet: colour tokens for light and dark (background, surface, border, text, muted text, primary, and semantic colours for severity CRITICAL/HIGH/MEDIUM/LOW/INFO, run statuses, confidence DETECTED/LIKELY/POSSIBLE, capability Deep/Basic/Generic/Unsupported/Unavailable, HTTP methods GET/POST/PUT/PATCH/DELETE), typography scale with a system sans-serif and a monospace font, spacing, radii, shadows, and components: badges, buttons, inputs, tabs, tables, filter bar, drawer, diagram card, snapshot selector, empty/loading/error states. All colour usages must also carry text or an icon and meet WCAG AA.
```

---

## 3. Notes for bringing the design back into the app

- The frontend is React 19 + Tailwind CSS 4 (`frontend/src`): pages in `pages/`, sections in `components/report`, `components/findings`, `components/dependencies`, `components/tests`, `components/architecture`, shared badges in `components/ui`.
- Keep these rules when implementing a Stitch design:
  - No external fonts, scripts or images. The nginx security policy blocks them; self-host anything needed.
  - Never render repository text as HTML (no `dangerouslySetInnerHTML`; ESLint fails the build).
  - Keep Mermaid in strict mode.
- The screens map to the existing routes: `/` (Dashboard), `/repositories/new` (Add Repository), `/repositories/:repositoryId?tab=...` (report tabs).
