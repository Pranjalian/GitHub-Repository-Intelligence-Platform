# Google Stitch Prompts: UI Redesign (all MVPs)

How to use:
1. Paste the **main prompt** (section 1) into Google Stitch first. It gives the full product context and asks for the complete screen set of MVPs 1–6.
2. Then refine one screen at a time with the **follow-up prompts** (section 2). Stitch produces better results from focused prompts than from one huge request.
3. If Stitch rejects the main prompt as too long, send it in three messages: sections A–D ("Context"), then section E screens 1–6 ("Core report"), then screens 7–11 plus F ("AI and advanced features").

Screenshots of the current app are optional. Leave them out on the first pass, so Stitch designs freely instead of copying today's plain layout. Use them later only to say "keep this information, improve this look".

**What each screen is based on:**

| Screens | MVP | Status | Source of the description |
|---|---|---|---|
| 1–6: shell, dashboard, add repository, progress, report tabs, architecture | 1–2 | Built | The current frontend code |
| 7: AI insights and AI settings | 3 | Built | The current frontend code |
| 8: Code search and Q&A | 4 | Planned | `docs/implementationPlan.md` steps 4.1–4.4 |
| 9: Impact analysis | 5 | Planned | `docs/implementationPlan.md` steps 5.1–5.3 |
| 10: Investigations | 6 | Planned | `docs/implementationPlan.md` steps 6.1–6.2 |

Designs for the planned screens are guidance for those MVPs. When an MVP is implemented, check its design against what was actually built.

---

## 1. Main prompt (paste this first)

```text
Design a modern, professional web application UI for a developer tool called "GitHub Repository Intelligence Platform" (short name: RepoIntel). Desktop-first (1440px), responsive down to tablet. Provide both light and dark themes. Design the complete product: the navigation must fit every screen listed below from the start.

A. WHAT THE PRODUCT IS
Developers often need to understand codebases they did not write: joining a team, taking over a legacy app, investigating a production issue, planning a refactor, reviewing a pull request, or assessing technical debt and security. Today that means hours of browsing folders, running separate tools and asking colleagues.

RepoIntel takes a public GitHub repository URL, downloads one exact commit, and produces an evidence-backed report: technology stack, repository structure, dependencies, code quality, security findings, tests and coverage, and (for Java/Spring Boot) the application architecture with diagrams. This core analysis is deterministic (parsers and scanners such as JavaParser, PMD and Semgrep). The repository's code is never executed.

On top of that, optional features build up in stages:
- AI insights: "Explain" a class, method or finding; summarize a module; summarize technical debt; suggest refactoring. Uses a local model (Ollama) or a hosted one (Gemini, OpenRouter). Off by default.
- Code search (always available, no AI) and Q&A chat: ask questions about the code and get answers that cite files and lines.
- Impact analysis: pick a class or method and see what a change to it may affect, with an explainable risk score.
- Investigations: multi-step automated reviews (e.g. "Top risks", "Full review") that combine everything above into one consolidated report with a full audit trail.

The product's core values, which the design must express:
1. Evidence first: every finding and every AI statement points to a file, line, rule and the commit it came from.
2. Honesty: it clearly says what was NOT analyzed and why. A missing metric is "Unavailable" or "Not analyzed", never shown as 0 or as "safe". Example: "Coverage unavailable" is different from "0% coverage".
3. Confidence: inferred statements are labelled DETECTED (proven by code), LIKELY (strong signal) or POSSIBLE (weak hint).
4. Commit-scoped: every report, search, answer and investigation belongs to one commit SHA; users can switch between analyzed commits (snapshots).
5. Multi-language: Java + Spring Boot + Maven get deep analysis; other languages get generic analysis plus an honest "capability matrix" showing which categories are supported for each detected technology.
6. AI is optional and clearly marked: the product is fully useful with AI switched off. Anything written by AI carries an "AI-generated" badge, the provider and model, and links to the evidence it was given. Deterministic results and AI text must never look the same.

B. USERS
Backend and full-stack developers, developers onboarding to a codebase, tech leads and architects, maintainers doing production support, code reviewers. They are technical, comfortable with dense data, and want to scan quickly, filter, and jump to evidence. The tool is a local single-user app (no login screens).

C. VISUAL DIRECTION
- Feel: calm, precise, trustworthy engineering tool (think Linear, GitHub, Vercel, Sentry dashboards). Clean, information-dense but not cluttered; generous whitespace between sections; strong typographic hierarchy.
- Monospace font for code, file paths, commit SHAs, rule IDs, versions, routes and symbol names.
- Clear colour language, consistent everywhere, and always paired with a text label or icon (never colour alone, for accessibility):
  - Severity: CRITICAL, HIGH, MEDIUM, LOW, INFO.
  - Run and analyzer status: Queued, Fetching code, Analyzing, Completed, Completed with warnings, Failed, Cancelled, Timed out, Skipped, Not analyzed, Not configured, Not supported, Unavailable.
  - Confidence: DETECTED, LIKELY, POSSIBLE.
  - Capability depth: Deep, Basic, Generic, Unsupported, Unavailable.
  - Dependency vulnerability status: Vulnerable, No known vulnerabilities, Not checked, Not checkable.
  - AI: a distinct, restrained "AI-generated" treatment (e.g. a subtle tinted surface plus a small sparkle icon and label) used for every piece of AI text, and nowhere else. Also: Local provider vs Hosted provider, and AI unavailable.
- "Not analyzed / Unavailable / Not supported / AI unavailable" states must look deliberate and informative (neutral styling with a reason and, where useful, a link to set it up), not like errors.
- Every screen needs designed loading, empty and error states.
- WCAG AA contrast, visible keyboard focus, readable at 14px base.

D. TECHNICAL CONSTRAINTS FOR THE DESIGN
- Will be implemented in React 19 with Tailwind CSS 4: use Tailwind-friendly spacing, colours and components; define design tokens (colours, type scale, radii, shadows) for light and dark.
- No external web fonts or CDN assets (the app's security policy only allows its own files): use a system font stack, or a font that can be self-hosted.
- No images from external URLs; icons as inline SVG.
- Diagrams are rendered by Mermaid.js inside a card: design the card frame, toolbar (zoom, fit, full screen, copy source) and a collapsible "Mermaid source" section, not the diagram itself.
- Repository-derived text (file paths, class names, code excerpts) is untrusted plain text: show it in monospace blocks, never as rich formatting.
- AI text is shown as simple Markdown only (paragraphs, lists, bold, inline code, code blocks): no images, embeds or raw HTML.

E. SCREENS TO DESIGN

1. App shell
   - Left sidebar, collapsible: product logo/name; global navigation "Dashboard", "Add Repository", "AI Settings".
   - When a repository is open, the sidebar (or a secondary nav) also shows that repository's sections: "Report" (with its tabs), "Search", "Ask" (Q&A), "Impact analysis", "Investigations". Sections that need AI show a small "AI" marker; sections that are unavailable stay visible with a reason.
   - Top header: page title/breadcrumb (Dashboard / owner/repo / section / tab), the current commit (short SHA) with the snapshot selector, an AI status indicator (AI off / Local: Ollama / Hosted: Gemini), theme toggle.
   - A subtle notice that this is a local tool with no authentication.

2. Dashboard (repository list)
   - Table/cards of analyzed repositories: repository (owner/name), URL, latest commit SHA (short, copyable), last analyzed time, run status badge.
   - Primary action "Add Repository".
   - Empty state: "No repositories analyzed yet. Submit a public GitHub repository to see its technology stack, structure and findings."
   - Nice to have: small per-repository summary chips (primary language, finding counts by severity, "Indexed for search" marker).

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
   - Module summary (AI, optional): pick a module, button "Summarize module", result shown as an AI insight card (see screen 7).
   - Capability matrix: rows = detected technology × category (Technology, Structure, Dependencies, Code quality, Security, Tests, Architecture); columns = analyzer, depth (Deep/Basic/Generic/Unsupported/Unavailable), execution status, reason. This is the key "honesty" element: design it as a readable heat-map style grid, not a raw table.
   - Limitations panel: list of plain-language limitations per analyzer (e.g. "Only direct dependencies are inventoried", "Tests are not executed; coverage only from committed reports").

   5b. Dependencies tab
   - Filters: ecosystem (All, Maven, npm), module, scope, vulnerability status, search.
   - Table: dependency (group:artifact or package), module, declared version, resolved version, version source (Declared, From a property, Managed by a local parent, "Managed by a remote parent or BOM (not resolved)"), scope, vulnerability status, management-entry marker.
   - Unresolved versions must be visibly labelled, not hidden.

   5c. Code Quality tab
   - "Technical debt summary" card at the top (AI, optional): note "An optional AI summary of the deduplicated findings of every analyzer (no source code is sent)", button "Summarize technical debt", result as an AI insight card with sections Priorities and Suggestions.
   - Findings list with filters (severity, module, file, rule) and sort (severity, file); grouping by severity or by rule.
   - Each row: severity badge, title, rule ID, file path:line, analyzer.
   - Finding detail panel (side drawer): title, severity, description, location (file, line range, module), rule and analyzer, analysis method (Deterministic scanner / Parser / Heuristic / Graph inference), evidence excerpt in a code block with line numbers, "Suggested next step", a link to the exact line on GitHub at that commit, and an "Explain" button that shows an AI explanation of this finding inside the drawer.

   5d. Security tab
   - Two parts: Security findings (secrets with redacted values like "aws_secret = ****", SQL injection, unsafe deserialization, weak crypto, sensitive logging…) using the same list + detail drawer (including "Explain"), and Security coverage: which scanners ran (secret scanner, Semgrep version), languages and rule files scanned, files scanned vs skipped, and dependency vulnerability status.
   - Make clear that findings are indications requiring review, not proof of exploitability.

   5e. Tests & Coverage tab
   - Summary: test classes, test methods, methods by framework (JUnit 5, etc.).
   - Coverage: lines, branches, methods per scope, from a committed JaCoCo report, with the report's path and a "may be stale" note; when absent, a clear "Coverage unavailable: no committed JaCoCo report" state (never 0%).
   - Test inventory table (test class, framework, methods) and "Production classes without an obvious test (by naming convention)" with the note "These classes may still be tested under another name."

6. Architecture tab / page (Java / Spring Boot)
   - Summary chips: controllers, services, repositories, entities, DTOs, configuration, endpoints, relationships, unresolved/ambiguous counts.
   - Diagrams section: cards for "Layers", "API flow" (endpoint → controller → service → repository → entity), "Entity relationships" (ER diagram) and "Modules", each with toolbar and collapsible Mermaid source; notes when a diagram was truncated ("API flow: capped at 45 nodes").
   - Layered types: grouped lists (Controllers, Services, Repositories, Entities, DTOs, Configuration, Infrastructure), each type with a confidence badge and "How" (e.g. "@RestController", "Spring Data interface", "package name"), and its injected dependencies. Each type has AI actions "Explain class" and "Suggest refactoring", plus an expandable "Methods" list (constructors and methods) where each method has "Explain method".
   - Endpoints table: HTTP method badge (GET/POST/PUT/DELETE/PATCH), route (monospace, e.g. /owners/{ownerId}/pets/new), handler (Class.method), request type, response type; "path not resolved" marker.
   - JPA entities: entity, table, relationships (OneToMany, ManyToOne, OneToOne, ManyToMany with field names), and which repository manages it.
   - Dependency injection chains: controller → service → repository → entity as readable horizontal chains.
   - Dependency cycles: warning cards listing the classes in each cycle with evidence.
   - Design pattern candidates: Strategy, Factory, Adapter, Repository with confidence (POSSIBLE/LIKELY/DETECTED) and evidence.
   - Unsupported state: "Architecture analysis is not supported for this technology" listing the detected technologies (e.g. Python, CSS) with what IS available instead.

7. AI insights (built) and AI Settings page (built)
   7a. AI insight card (one reusable component, used in the finding drawer, architecture types and methods, module summary and technical debt summary)
   - Trigger button (e.g. "Explain", "Explain class", "Explain method", "Suggest refactoring", "Summarize module", "Summarize technical debt"); states "Checking whether AI is available…", "Asking…".
   - Result: "AI-generated" badge with tooltip "Written by an AI model from the evidence listed below. It can be wrong; check the evidence."; meta line with provider, model, prompt version and small tags "cached", "fallback provider", "truncated".
   - Body sections depending on the request: Explanation, Key points, Responsibilities, Key types, Priorities, Suggestions, Missing evidence (what the model said it could not see).
   - "Source evidence" list: the files and line ranges that were sent, each with "View source evidence" linking to GitHub at the commit.
   - Insight notes: a small muted list of warnings and limitations returned with the answer.
   - Failure states, each with a plain reason: "The AI provider did not answer in time", "The AI provider is rate-limited", "The AI provider failed", "The model returned an invalid answer, so it is not shown", "Skipped" (call limit for this analysis reached).
   - AI-unavailable state: button disabled with "AI insights are unavailable." plus the reason and a link to AI Settings. When available, a one-line note "AI explanations are available; every answer is marked AI-generated."
   7b. AI Settings page (/settings/ai)
   - Status banner: "AI enabled" / "AI disabled", and "Insights available" / "Insights unavailable" with the reason.
   - Providers table: provider (Ollama, Gemini, OpenRouter), state (enabled/disabled), health, data handling (Local: nothing leaves the machine / Hosted: excerpts are sent, secrets scrubbed, needs acknowledgement), model, capabilities, "(default)" marker.
   - Preferred provider selector among permitted providers.
   - Limits: Calls per analysis, Max input tokens, Max output tokens, Timeout (s), Max retries.
   - Usage table: When, Feature, Provider / model, Status, Input tokens, Output tokens (marked reported vs estimated), Latency, Cost (only when configured), cache hit. Empty state "No AI calls yet."
   - "How to enable AI insights" setup guide card with monospace steps: install Ollama, run "ollama pull llama3.2", set AI_ENABLED=true, AI_PROVIDERS_OLLAMA_ENABLED=true and OLLAMA_CHAT_MODEL in .env; for hosted providers also AI_PROVIDERS_<ID>_DATA_HANDLING_ACKNOWLEDGED=true. Link "See the README section 'AI insights (optional)'".

8. Code search and Q&A (planned)
   8a. Index status (shown on Search and Ask)
   - Per snapshot: "Not indexed" / "Indexing…" with progress / "Indexed" (chunks count, embedding model, indexed time), button "Index this snapshot". Note that indexing is optional and separate from analysis.
   8b. Search page (always available, no AI needed)
   - Search box with a mode toggle "Symbol" / "Text"; filters: language, module, file type.
   - Results: symbol results (kind badge class/method/field, fully qualified name, file:line) and text results (file path, line range, highlighted snippet in monospace), each linking to GitHub at the commit. Empty and "no results" states.
   8c. Ask page (Q&A chat, AI)
   - Chat thread scoped to repository + commit (shown at the top). Question input with example questions ("Where is the owner validation done?", "How is a visit saved?").
   - Each answer: AI-generated badge, Markdown answer with inline source references, a "Sources" list (file path, line range, snippet, GitHub link). Follow-up questions in the same conversation; a new conversation button.
   - "Not enough evidence" answer style: says so honestly, lists what was searched, may ask a clarifying question.
   - Unavailable states that point users to Search: "AI unavailable" and "No embedding provider configured", each showing the deterministic search results for the question terms instead.

9. Impact analysis (planned)
   - Input: symbol picker (search-backed, choose a class or method, e.g. OwnerController.processCreationForm), depth selector, optional free-text change description (AI only; hidden with a note when AI is unavailable; the AI-proposed symbol mapping must be confirmed by the user).
   - Results:
     - Affected components tree, grouped: direct callers (1 hop), indirect dependents, reaching endpoints/APIs, related entities and repositories, dependent modules. Each node shows a confidence badge and evidence (file:line, edge type).
     - "Heuristic risk score" (e.g. 62 / 100, labelled clearly as not a probability) with a breakdown of every factor and its value: dependency depth, number of dependents, public API exposure, security relevance (findings on the path), test presence/coverage.
     - Affected tests (DETECTED via call graph, LIKELY via naming convention) and "Suggested regression-test areas".
     - Limitations: unresolved edges on the path, reflection, dynamic dispatch (all implementations listed as POSSIBLE), generated code, runtime wiring (profiles, conditional beans, AOP).
     - Dependency-chain diagram card (Mermaid, size-capped).
     - Optional AI summary as an AI insight card.

10. Investigations (planned)
   - Start: choose an investigation type ("Top risks", "Full review", …) with a short description of each, the budget it will use (AI calls, tokens, time), and a Start button. List of previous investigations for this commit with status.
   - Progress: steps by specialist role (Repository, Architecture, Code Quality, Security, Performance, Impact, Test, Documentation), each with status, duration, tools used and tokens; Cancel; budget-exceeded state.
   - Consolidated report: executive summary, prioritized items with the transparent criteria used, one section per specialist, uncertainty notes and recommended next steps. Deterministic facts and AI-written text visibly distinguished; every item has expandable evidence.
   - Audit trail: a step-by-step timeline (inputs, tool called, outputs, evidence references, tokens, duration). Note that all tools are read-only.

11. Cross-cutting states
   - AI off everywhere: every AI entry point shows the same calm disabled pattern with a link to AI Settings; nothing else on the page changes.
   - Not supported (e.g. a Python repository): report tabs, Architecture and Impact analysis show what is not supported and what is available instead.

Realistic sample data (spring-projects/spring-petclinic, commit 500158f): Java + Spring Boot + Maven; 6 controllers (OwnerController, PetController, VetController, VisitController, CrashController, WelcomeController), 3 Spring Data repositories (OwnerRepository, PetTypeRepository, VetRepository), 9 entities (BaseEntity, NamedEntity, Person, Owner, Pet, PetType, Visit, Vet, Specialty), 17 endpoints (e.g. GET /owners/{ownerId}, POST /owners/new, GET /owners/{ownerId}/pets/new), 46 dependencies, 22 findings, 24 tests, coverage unavailable, Semgrep 1.186.0, vulnerability lookup not configured. AI: local provider Ollama with model llama3.2.

F. OUTPUT
Produce high-fidelity screens for:
- App shell + Dashboard, Add Repository, Analysis progress.
- Report: Overview, Dependencies, Code Quality (with the finding drawer open and an AI explanation shown), Security, Tests & Coverage, Architecture (with one class's AI explanation open).
- AI Settings (AI enabled with Ollama) and the AI-unavailable variant of an insight card.
- Search, Ask (a conversation with sources, plus the "not enough evidence" answer), Impact analysis (results), Investigation (progress and consolidated report with audit trail).
- The "Not supported" state for a Python repository.
Also produce a component sheet: badges (severity, status, confidence, capability, HTTP method, AI-generated, local/hosted), tables, filter bar, finding drawer, diagram card, AI insight card (all states), chat message with sources, risk-factor breakdown, step timeline, empty/loading/error states, snapshot selector, and the colour and typography tokens for light and dark mode.
```

---

## 2. Follow-up prompts (one screen at a time)

Use these after the main prompt to refine individual screens.

**Report Overview**
```text
Refine the Repository Report "Overview" tab. Make the top summary cards scannable at a glance, the technology stack grouped by kind with confidence badges and expandable evidence (file path + matched rule), and redesign the capability matrix as an easy-to-read grid (technology rows × category columns: Technology, Structure, Dependencies, Code quality, Security, Tests, Architecture), where each cell shows depth (Deep/Basic/Generic/Unsupported/Unavailable) and status, with the reason on hover. Unsupported must look informative, not like an error. Include the optional "Module summary" AI card (module picker + "Summarize module") without letting it dominate the deterministic content.
```

**Findings (Code Quality and Security)**
```text
Refine the findings experience used by the Code Quality and Security tabs: a filter bar (severity, module, file, rule, search), a dense list grouped by severity, and a right-side detail drawer showing severity, rule ID, analyzer, analysis method, location (file:line-range at commit 500158f), the evidence excerpt as a code block with line numbers and the matched line highlighted, a redacted secret example ("password = ****"), a "Suggested next step" box, an "Open on GitHub" link, and an "Explain" button that opens an AI explanation inside the drawer, visually separate from the deterministic finding. Add the "Technical debt summary" AI card above the Code Quality list. Include empty ("No findings") and "nothing matches these filters" states.
```

**Architecture**
```text
Refine the Architecture tab for a Spring Boot repository. Top: summary chips (6 controllers, 3 repositories, 9 entities, 17 endpoints, unresolved edges count). Then a 2-column grid of diagram cards (Layers, API flow, Entity relationships, Modules) with zoom/fit/full-screen/copy-source toolbar and a collapsible Mermaid source. Then tabs or sections for Layered types (with confidence badge and "How" evidence such as "@RestController", AI actions "Explain class" and "Suggest refactoring", and an expandable Methods list with "Explain method"), Endpoints table with coloured HTTP method badges and monospace routes, JPA entities with relationship chips (OneToMany Owner.pets → Pet), DI chains as horizontal arrow chains, dependency-cycle warning cards, and design-pattern candidates with confidence. Also show the "not supported for Python" state.
```

**AI insight card**
```text
Design the reusable AI insight card in all its states: idle (trigger button), checking availability, asking (skeleton), success, cached result, fallback provider used, truncated input, each failure ("did not answer in time", "rate-limited", "provider failed", "invalid answer, so it is not shown", "skipped: call limit reached") and AI unavailable (disabled button, reason, link to AI Settings). Success shows an "AI-generated" badge with the tooltip "Written by an AI model from the evidence listed below. It can be wrong; check the evidence.", provider/model/prompt version, Markdown sections (Explanation, Key points, Suggestions, Missing evidence) and a "Source evidence" list with file:line ranges and "View source evidence" links. It must be clearly distinguishable from deterministic results in both themes, without being loud.
```

**AI Settings**
```text
Refine the AI Settings page (/settings/ai): status banner (AI enabled/disabled, insights available/unavailable with reason), providers table (Ollama, Gemini, OpenRouter: state, health, data handling Local vs Hosted with a clear explanation of what leaves the machine, model, capabilities, default marker), preferred provider selector, limits (calls per analysis, max input/output tokens, timeout, retries), usage table (when, feature, provider/model, status, input/output tokens marked reported vs estimated, latency, cost, cache hit) with the "No AI calls yet." empty state, and a "How to enable AI insights" setup card with monospace commands and env variables. Also show the AI-disabled variant of the whole page.
```

**Search and Ask (Q&A)**
```text
Design the Search and Ask pages for one repository at commit 500158f. Shared index status bar: Not indexed / Indexing (progress) / Indexed (chunks, embedding model, time) with "Index this snapshot". Search: Symbol/Text toggle, filters (language, module, file type), results with kind badges, monospace names, file:line and highlighted snippets linking to GitHub. Ask: chat thread with example questions, answers with the AI-generated badge, inline source references and a Sources list (file, lines, snippet, GitHub link), follow-ups, new conversation, an honest "not enough evidence" answer that lists what was searched, and the "AI unavailable" / "No embedding provider configured" states that show search results instead and point to Search.
```

**Impact analysis**
```text
Design the Impact Analysis page. Input: search-backed symbol picker (e.g. OwnerController.processCreationForm), depth selector, optional change description (AI only; hidden with a note when AI is off). Results: an affected-components tree (direct callers, indirect dependents, endpoints, entities and repositories, modules) with confidence badges and evidence; a "Heuristic risk score" (not a probability, say so) with each factor and its value (dependency depth, dependents, public API exposure, security findings on the path, test presence/coverage); affected tests (DETECTED vs LIKELY) and suggested regression-test areas; a limitations box (unresolved edges, reflection, dynamic dispatch, generated code, runtime wiring); a dependency-chain diagram card; and an optional AI summary card.
```

**Investigations**
```text
Design the Investigations feature. Start screen: investigation types ("Top risks", "Full review") with descriptions and the budget each uses (AI calls, tokens, time), plus history for this commit. Progress: a step list by specialist role (Repository, Architecture, Code Quality, Security, Performance, Impact, Test, Documentation) with status, duration, tools used and tokens, Cancel, and a budget-exceeded state. Report: executive summary, prioritized items with the criteria used, one section per specialist, uncertainty and next steps, deterministic facts vs AI text visibly distinguished, expandable evidence everywhere. Audit trail: timeline of every step (inputs, tool, outputs, evidence references, tokens, duration), noting all tools are read-only.
```

**Analysis progress**
```text
Refine the Analysis Progress screen: a horizontal stepper (Queued → Fetching code → Detecting technologies → Running analyzers → Finished), a live list of analyzers with status icons and durations (Completed 10 ms, Running with spinner, Not configured with reason, Failed with short error), elapsed time, and a Cancel button. Add variants for Failed, Cancelled, and GitHub rate-limited ("Retry after 18:24").
```

**Dashboard and Add Repository**
```text
Refine the Dashboard and Add Repository screens. Dashboard: repository cards or rows with owner/name, primary language chip, short commit SHA with copy button, last analyzed (relative time), run status badge, finding counts by severity and an "Indexed for search" marker; a prominent "Add Repository" button; a friendly empty state. Add Repository: one focused card with the URL field, inline validation, "Force full rescan" checkbox, Analyze button, example repository chips, and a short info panel (public repositories only, code is never executed, GitHub token raises the rate limit).
```

**Design system**
```text
Create the design system sheet: colour tokens for light and dark (background, surface, border, text, muted text, primary, and semantic colours for severity CRITICAL/HIGH/MEDIUM/LOW/INFO, run statuses, confidence DETECTED/LIKELY/POSSIBLE, capability Deep/Basic/Generic/Unsupported/Unavailable, HTTP methods GET/POST/PUT/PATCH/DELETE, AI-generated surface, Local/Hosted provider), typography scale with a system sans-serif and a monospace font, spacing, radii, shadows, and components: badges, buttons, inputs, tabs, tables, filter bar, drawer, diagram card, AI insight card, chat message with sources, risk-factor bars, step timeline, snapshot selector, empty/loading/error/unavailable states. All colour usages must also carry text or an icon and meet WCAG AA.
```

---

## 3. Notes for bringing the design back into the app

> **Design implemented in MVP 7.** The screens below were built from the Stitch exports; see [mvp7-completion-report.md](mvp7-completion-report.md) for every deliberate deviation. Investigations (start and history, progress, report, audit trail) were not built because MVP 6 does not exist yet; their routes are placeholders.

- The frontend is React 19 + Tailwind CSS 4 (`frontend/src`). Tokens are CSS variables in `styles/globals.css` (light in `:root`, dark in `.dark`), exposed to Tailwind as `bg-canvas`, `bg-surface`, `bg-raised`, `text-fg`, `text-fg-2`, `text-muted`, `border-line`, `text-ai` and the `.tone-*` chip classes. Primitives live in `components/ui` (icons, badges, Button, Controls, Data, CodeBlock, Flow, Charts, Layout), the shell in `components/layout`, the AI card in `components/ai`, pages in `pages/` (lazy-loaded per route). `/dev/components` (development only) shows every primitive in both themes.
- Keep these rules when changing a screen:
  - No external fonts, scripts, icons or images. The nginx security policy blocks them; fonts come from `@fontsource-variable/*`, icons are inline SVG (`components/ui/icons.tsx`).
  - Never render repository text as HTML (no `dangerouslySetInnerHTML`; ESLint fails the build). AI text goes through `MarkdownText`. Keep Mermaid in strict mode.
  - Never show a number for something that was not measured ("Not analyzed", "Unavailable", "Not checked"); never show a percentage confidence or a test pass/fail status.
- Routes: `/` (Dashboard), `/repositories/new`, `/repositories/:repositoryId?tab=overview|dependencies|code-quality|security|tests|architecture&snapshot=`, `/repositories/:repositoryId/arch`, `/search`, `/qa`, `/impact`, `/repositories/:repositoryId/investigations` (placeholder), `/investigations/:id` and `/investigations/:id/audit` (placeholders), `/settings/ai`.
