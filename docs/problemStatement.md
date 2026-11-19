# GitHub Repository Intelligence Platform

## Product Problem Statement, Goals, MVP Roadmap, Architecture Guidance and Implementation Requirements

**Document type:** Product and technical implementation brief\
**Target implementation environment:** Antigravity / AI coding
assistant\
**Initial target stack:** Java + Spring Boot + Maven repositories\
**Frontend:** React\
**Backend:** Java + Spring Boot\
**Primary design constraint:** The platform must remain useful when no
paid AI model or paid AI API is configured.

------------------------------------------------------------------------

# 1. Executive Summary

Build a **GitHub Repository Intelligence Platform** that allows a
developer to provide a GitHub repository URL and receive a structured,
evidence-backed understanding of that codebase.

The platform should progressively evolve from a conventional static
repository analyzer into an AI-assisted engineering intelligence tool.
It should help developers understand unfamiliar repositories, discover
architecture and dependencies, identify quality and security concerns,
ask questions about implementation details, and estimate the impact of
proposed code changes.

The product must not be designed as a thin chat interface that sends an
entire repository to an LLM. Most measurable analysis should be
performed using deterministic tools, parsers and repository metadata. AI
should be an optional reasoning and explanation layer used only where it
adds value.

The product must support a **free-first and provider-independent AI
strategy**:

-   Core repository ingestion and static analysis must work without an
    AI API key.
-   AI features must support local models through Ollama and
    configurable hosted providers that offer free tiers or free models,
    subject to their current terms and quotas.
-   No paid provider may be mandatory for normal operation.
-   The system must be able to switch providers without changing
    business logic.
-   Token usage, request limits, failures and estimated costs must be
    visible and controlled.
-   If no AI provider is available, the platform must gracefully fall
    back to deterministic results and clearly mark AI-only capabilities
    as unavailable.

The platform is intended to become **multi-language and multi-framework**, not a Java-only analyzer. Its core ingestion, repository model, orchestration, reporting and capability tracking must be technology-independent. The first fully supported language/framework combination will be **Java + Spring Boot + Maven**. MVP 1 must also perform useful generic analysis for repositories in other languages (for example language/file detection, repository structure and supported generic scans), while clearly marking language-specific checks as unsupported or unavailable. Additional language/framework analyzers will be added incrementally in later phases.

------------------------------------------------------------------------

# 2. Problem Statement

Developers regularly need to understand codebases they did not create or
have not worked on recently. This happens when:

-   Joining an existing project or team.
-   Taking ownership of a legacy application.
-   Investigating a production issue.
-   Planning a new feature or refactoring.
-   Reviewing a pull request.
-   Migrating a legacy application to a new architecture or technology.
-   Assessing technical debt, security posture or maintainability.
-   Understanding which modules, APIs, classes and tests may be affected
    by a change.

Today, this understanding is often acquired manually by browsing
folders, searching for symbols, reading configuration files, running
multiple tools and asking experienced team members. This process is
time-consuming, inconsistent and difficult to repeat.

Generic AI chat tools can explain code, but they may not have a complete
or current understanding of the repository. Sending large amounts of
source code to a model is expensive, can exceed context limits, may
expose confidential code and can produce unsupported conclusions. A
chat-only solution also cannot reliably calculate dependency
relationships, test coverage or static-analysis findings.

The proposed platform addresses these problems by combining:

1.  **Repository-aware ingestion** -- safely obtain repository metadata
    and source files.
2.  **Deterministic analysis** -- use parsers and established tools to
    calculate facts and identify measurable findings.
3.  **Architecture discovery** -- infer modules, layers, APIs, data
    models and dependencies from source and configuration.
4.  **AI-assisted explanation** -- use an optional model to explain
    findings and answer questions using retrieved repository evidence.
5.  **Code impact analysis** -- build dependency relationships to
    identify likely affected components when code changes.
6.  **Progressive intelligence** -- introduce multi-step agent workflows
    only after the underlying analysis and evidence model are reliable.

The result should be a developer tool that answers both:

-   **"What is in this repository, and what can we prove about it?"**
-   **"Help me understand this repository and reason about a change
    using actual source-code evidence."**

------------------------------------------------------------------------

# 3. Product Vision and Goals

## 3.1 Vision

Provide a developer with a reliable, explainable intelligence layer over
a GitHub repository: from repository URL to architecture overview, code
health report, grounded Q&A and change-impact insights.

## 3.2 Primary goals

-   Reduce the time required to understand an unfamiliar repository.
-   Generate a repeatable overview of repository structure and
    technology.
-   Identify code-quality, dependency, security and testing concerns.
-   Explain architecture and implementation flows with references to
    actual files and symbols.
-   Allow developers to ask natural-language questions about a
    repository.
-   Identify potentially affected classes, methods, APIs and tests for a
    proposed change.
-   Keep AI usage optional, replaceable, measurable and economical.
-   Make analysis results traceable to a repository commit and source
    evidence.
-   Support multiple languages and frameworks through an extensible analyzer architecture, while introducing deep language-specific analysis incrementally.
-   Provide a foundation for later agentic workflows.

## 3.3 Non-goals for the first release

The first release is not intended to:

-   Guarantee deep, feature-complete analysis for every programming language and build system in the first release.
-   Execute arbitrary repository code on the application server.
-   Automatically modify source code, create branches, push commits or
    merge pull requests.
-   Guarantee that every architecture or design pattern is detected.
-   Replace SonarQube, Semgrep, dependency scanners or a developer's
    judgment.
-   Require a paid LLM subscription or paid API.
-   Treat an LLM-generated explanation as a verified fact.
-   Provide a production-grade multi-tenant enterprise deployment in the
    first MVP.

------------------------------------------------------------------------

# 4. Target Users and Main Use Cases

## 4.1 Target users

-   Full-stack and backend developers.
-   Developers onboarding to an existing codebase.
-   Technical leads and architects.
-   Engineers responsible for maintenance and production support.
-   Reviewers assessing code changes.
-   Teams evaluating technical debt and repository health.

## 4.2 Core use cases

### Use case A: Understand a repository

A developer submits a public GitHub URL. The platform identifies its
language, frameworks, build system, modules, directory structure,
dependencies, tests and key configuration files. It generates a
repository overview.

### Use case B: Understand architecture

A developer wants to know whether an application follows layered
architecture, how its modules are organized, where REST endpoints are
defined, how services call repositories and how entities relate to each
other. The platform discovers these relationships and presents an
architecture report and diagram.

### Use case C: Identify code health concerns

A developer wants to see code smells, complexity, duplication,
dependency risks, security findings, test coverage and missing tests.
The platform runs appropriate static-analysis and build tools and
presents findings with evidence.

### Use case D: Ask questions about code

A developer asks, "Where is authentication implemented?" or "Explain the
payment failure flow." The platform retrieves relevant files and
symbols, provides a concise explanation and cites the evidence used.

### Use case E: Estimate change impact

A developer asks, "If I change `calculatePayment()` in `PaymentService`,
what could be affected?" The platform uses a dependency graph and test
relationships to identify likely callers, dependent components and
tests. AI may explain the result, but the graph and source evidence must
remain available.

### Use case F: Investigate repository health

A developer asks for the most important technical risks in the
repository. In a later phase, specialized analysis workflows can combine
quality, security, architecture, dependency and testing results into a
prioritized investigation.

------------------------------------------------------------------------

# 5. Product Principles and Mandatory Design Decisions

These principles apply to every MVP.

## 5.1 Deterministic-first, AI-assisted

Use conventional tools and parsers for facts that can be calculated
reliably:

-   File counts and extensions.
-   Language and framework detection.
-   Maven dependency inventory.
-   Source structure and module discovery.
-   Static-analysis findings.
-   Dependency vulnerability results when a scanner/database is
    available.
-   Test counts and coverage when tests can be safely built and executed
    in an isolated environment.
-   AST-based class, method, import and call relationships.

Use an LLM selectively for tasks that need language understanding or
synthesis:

-   Explaining an architecture already discovered by parsers.
-   Summarizing a class or method.
-   Explaining why a finding matters.
-   Answering a natural-language question using retrieved code.
-   Synthesizing a change-impact report from graph results.
-   Suggesting possible refactorings.

Never ask an LLM to invent repository facts that can be obtained from
source analysis.

## 5.2 AI is optional, not a platform dependency

The application must start, ingest repositories and run MVP 1 analysis
without any AI provider configured.

Every AI-dependent feature must have:

-   A provider capability check.
-   A clear unavailable/degraded state.
-   A timeout and bounded retry policy.
-   A safe fallback.
-   Token/request accounting where provider usage information is
    available.
-   A user-visible indication that an answer is AI-generated.

## 5.3 Provider independence

Do not call a provider's SDK directly from controllers, business
services or domain logic.

Define a provider-neutral interface, for example:

``` java
public interface AIModelService {
    AIResponse generate(AIRequest request);
    AIProviderCapabilities getCapabilities();
}
```

Implement provider adapters independently, for example:

-   `OllamaAIModelService` -- local model endpoint.
-   `GeminiAIModelService` -- hosted API, subject to the currently
    available free quota and terms.
-   `OpenRouterAIModelService` -- optional access to models marked free,
    subject to current availability and limits.
-   Additional providers only when needed.

The exact models, pricing, rate limits, context windows and free-tier
terms change. Do not hard-code assumptions that a provider or model will
remain free. Read configuration at runtime and keep provider/model
selection configurable.

A provider that is unavailable, rate-limited or removed must not break
repository analysis.

## 5.4 Evidence before explanation

Every important finding and AI answer should include as much of the
following as is available:

-   Repository and commit SHA.
-   File path.
-   Line range.
-   Class, method, API or dependency name.
-   Finding type and severity.
-   Evidence excerpt or a link to the relevant source location.
-   Analysis method: deterministic scanner, parser, graph inference or
    AI interpretation.
-   Confidence or certainty classification where inference is involved.

Do not present an inferred relationship as a confirmed fact. Use labels
such as **Detected**, **Likely**, **Possible** and **Not verified**
where appropriate.

## 5.5 Incremental and reusable analysis

-   Identify a repository snapshot by commit SHA.
-   Cache deterministic results by repository, commit and analyzer
    version.
-   Reuse unchanged file hashes and summaries.
-   Analyze changed files and affected relationships when a later commit
    is submitted.
-   Avoid sending unchanged source to an AI provider repeatedly.
-   Allow the user to request a full rescan.

## 5.6 Safe handling of untrusted repositories

Repository source, README files, comments, build scripts and
configuration files are untrusted input.

-   Do not execute repository code on the main application server.
-   Do not execute build scripts by default.
-   If build/test execution is enabled, use an isolated, disposable
    worker or container with resource and network restrictions.
-   Apply repository size, file count, file size, processing time and
    memory limits.
-   Block or safely handle symlinks and path traversal.
-   Do not follow arbitrary links outside the checked-out repository.
-   Do not expose provider API keys or platform secrets to repository
    content.
-   Treat instructions found in source files as data, not as
    instructions to the AI system. This is essential protection against
    prompt injection.
-   Do not send private or confidential code to a hosted model unless
    the user/admin has explicitly enabled that provider and accepted its
    data-handling terms.

------------------------------------------------------------------------

# 5.7 Multi-language and analyzer extensibility (mandatory)

The long-term product scope is **any technology stack**, but support depth will vary by analyzer pack and release. The product must never be architecturally restricted to Java.

- The repository ingestion, file inventory, technology detection, orchestration, result model, persistence, reporting and frontend must be technology-independent.
- MVP 1 deeply supports Java + Spring Boot + Maven first, while providing generic repository analysis for other stacks.
- Add language/framework-specific analyzers incrementally; do not claim full support for a stack until its capabilities are implemented and tested.
- A single repository may be polyglot or a monorepo. Detect multiple stacks and run every compatible analyzer independently.
- A failure or unsupported feature in one analyzer must not prevent other analyzers from completing.
- Every report must show detected technologies separately from analyzer capabilities and actual execution status.
- Use common normalized finding/evidence contracts so the UI does not need language-specific screens for every analyzer.
- Keep language-specific parsers, rules and build-tool integrations inside their analyzer packs.

Suggested expansion order after the initial Java pack: JavaScript/TypeScript (Node.js, React, Angular), Python, Go and .NET/C#. This is a prioritization suggestion, not a promise of immediate or equal feature parity.

------------------------------------------------------------------------

# 6. Scope and Phased MVP Roadmap

The product should be delivered in independently useful phases. Each
phase should build on the previous phase and retain a working
application.

## MVP 0 -- Project foundation and engineering baseline

**Purpose:** Establish a maintainable application structure before
implementing analysis features.

### Features

-   React frontend application.
-   Spring Boot backend application.
-   PostgreSQL database.
-   Database migrations.
-   Health-check endpoint.
-   Application configuration profiles.
-   Centralized error handling and validation.
-   Structured logging with sensitive-data redaction.
-   Docker Compose for local development where practical.
-   API documentation, preferably OpenAPI.
-   Basic automated backend and frontend tests.
-   CI workflow for build, test and static checks.
-   A clean dashboard shell and navigation.

### Engineering requirements

-   Separate controller, application/service, domain and infrastructure
    concerns.
-   Use DTOs for API boundaries.
-   Do not expose persistence entities directly from APIs.
-   Keep external integrations behind interfaces.
-   Use configuration/environment variables for secrets and endpoints.
-   Provide `.env.example` or equivalent documentation without real
    secrets.
-   Include a clear README with local setup and troubleshooting.

### Exit criteria

-   Frontend and backend run locally.
-   Database migrations run successfully.
-   Health checks work.
-   CI builds and tests the project.
-   No provider API key is required to start the application.

------------------------------------------------------------------------

## MVP 1 -- Basic GitHub Repository Analyzer (must work without AI)

**Purpose:** Accept a public GitHub URL and generate a useful, deterministic repository analysis report for any repository, with deep language-specific support initially focused on Java + Spring Boot + Maven.

**This is the first functional product milestone and should receive the highest implementation priority.**

**Important product decision:** MVP 1 is not a Java-only product. The core must be technology-independent from the beginning. Java + Spring Boot + Maven is the first fully supported analyzer pack; other repositories must still receive generic analysis and an honest capability report.

### 1. Repository submission and ingestion

-   Accept a valid public GitHub repository URL.
-   Validate the URL and normalize supported GitHub URL formats.
-   Reject unsupported hosts and malformed URLs clearly.
-   Retrieve repository metadata and the default branch.
-   Resolve and record the exact commit SHA being analyzed.
-   Retrieve the repository tree and source files.
-   Prefer GitHub APIs for metadata/tree access where appropriate.
-   Support a bounded clone/download approach only when required.
-   Exclude generated and irrelevant directories such as `.git`,
    `node_modules`, `target`, `build`, `dist`, `.gradle` and other
    configurable paths.
-   Respect GitHub API rate limits and handle throttling.
-   Show ingestion and analysis status to the user.

### 2. Technology stack detection

Detect and report, where evidence exists:

-   Programming languages and approximate file distribution.
-   Frameworks and libraries.
-   Build tools and package managers.
-   Language/runtime versions where detectable.
-   Framework versions where detectable.
-   Project/module boundaries and workspace/monorepo indicators.
-   Database technologies based on dependencies and configuration.
-   Testing frameworks and test-related configuration.
-   Container/deployment indicators such as Dockerfiles and Kubernetes manifests.

Examples of detection evidence include `pom.xml`, `build.gradle`, `package.json`, lockfiles, `pyproject.toml`, `requirements.txt`, `.csproj`, `go.mod`, configuration files and source-file extensions. Do not infer a framework from a single weak clue; record confidence and evidence.

**Detection must be extensible:** use technology signatures/rules that can be added without changing the repository-ingestion core. Distinguish a detected language from a fully supported analyzer: detection alone does not mean that deep architecture, dependency or impact analysis is available.

### 3. Repository structure analysis

-   Directory tree.
-   Module count.
-   Source, test and resource file counts.
-   Main entry points.
-   Configuration files.
-   Important build files.
-   Basic module boundaries.
-   File types and size distribution.

### 4. Dependency analysis

Use a common dependency-analysis contract with technology-specific implementations. In MVP 1, fully implement Maven dependency parsing. Where safe and feasible, detect common manifests for other ecosystems (for example `package.json`, `requirements.txt` or `pyproject.toml`, `go.mod` and `.csproj`) and report basic dependency inventory only when a reliable parser is available. Do not present a manifest that was merely detected as if its dependencies were fully analyzed.

For Maven repositories:

-   Parse parent and child `pom.xml` files.
-   List direct dependencies and versions.
-   Identify dependency scopes.
-   Identify dependency-management entries.
-   Identify duplicate declarations and obvious version inconsistencies.
-   Identify outdated dependencies only when a reliable version source
    is configured.
-   Integrate a dependency vulnerability scanner in a controlled way,
    such as OWASP Dependency-Check or another suitable scanner.
-   Clearly distinguish "outdated" from "known vulnerable"; they are not
    the same.

Do not execute Maven build plugins from an untrusted repository during
ordinary ingestion.

### 5. Code quality analysis

Use a common code-quality analyzer contract. Integrate or prepare integration for tools such as:

-   SonarQube / SonarScanner for maintainability and code-quality
    findings.
-   Checkstyle or PMD for configurable Java rules.
-   JavaParser for structural metrics and later architecture analysis.

Capture findings such as:

-   Code smells.
-   High-complexity methods.
-   Large classes and methods.
-   Duplication, if supported by the configured analyzer.
-   Basic naming and maintainability rule violations.
-   Potentially unused code only when supported by reliable analysis.

Do not fabricate metrics when a tool was not run. Show "Not analyzed" or
"Unavailable" rather than zero.

### 6. Security analysis

Integrate Semgrep or an equivalent static-analysis tool for supported rules, plus dependency vulnerability scanning. Scanner coverage is rule- and language-dependent; show which rules/languages were actually scanned rather than implying universal coverage.

Potential findings:

-   Hardcoded secrets and credentials.
-   SQL injection patterns.
-   Unsafe deserialization patterns.
-   Insecure cryptographic usage.
-   Risky logging of sensitive data.
-   Weak or unsafe configuration.
-   Dependency vulnerabilities.

Requirements:

-   Findings must include file path, line number and rule identifier
    where available.
-   Avoid printing secret values in reports or logs. Redact matched
    secrets.
-   A scanner finding is an indication requiring review, not
    automatically proof of exploitability.
-   Never upload discovered secrets to an LLM.

### 7. Test analysis

Use a common test-analysis contract with language-specific implementations.

-   Detect test source folders and test frameworks.
-   Count test classes and test methods where possible.
-   Identify production modules with no obvious corresponding tests.
-   Detect JaCoCo configuration and parse coverage reports when
    available.
-   If execution is supported, run tests only in an isolated environment
    with resource limits.
-   Do not claim actual coverage if tests were not executed or a valid
    report was not present.

### 8. Consolidated report and dashboard

Provide a repository overview dashboard with sections for:

-   Overview.
-   Technology stack.
-   Repository structure.
-   Dependencies.
-   Code quality.
-   Security.
-   Tests and coverage.
-   Analysis limitations.

Each finding should include severity, description, source evidence and
analysis status.

Allow users to view a report for a specific commit and distinguish it
from reports for other commits.

### MVP 1 UI expectations

-   Repository URL input.
-   Analyze button.
-   Validation and ingestion progress.
-   Repository summary.
-   Analysis status per category.
-   Findings grouped by severity and category.
-   Filters and sorting.
-   Finding detail panel with source path/line references.
-   Clear empty, failed, skipped and unavailable states.

### MVP 1 acceptance criteria

For a public Java + Spring Boot + Maven repository (the first fully supported stack):

1.  The user can submit its GitHub URL.
2.  The platform records the repository identity and commit SHA.
3.  It identifies the technology stack and basic structure.
4.  It inventories Maven dependencies.
5.  It runs configured code-quality and security analysis, or clearly
    reports why a tool could not run.
6.  It identifies tests and reports coverage only when supported by a
    real coverage report.
7.  It generates a consolidated report with evidence.
8.  The entire workflow works with all AI providers disabled and no AI API key configured.

For a public repository using another stack (for example React/TypeScript, Python or Go):

9.  The platform still ingests the repository and reports languages, file distribution, directory structure and detected technology/build manifests.
10. It runs only generic or explicitly supported analyzers and reports unsupported/unavailable capabilities honestly.
11. The UI shows a capability matrix so users can distinguish detected technologies from technologies with deep analyzer support.

------------------------------------------------------------------------

## MVP 2 -- Architecture Intelligence

**Purpose:** Move beyond file inventory and identify how the application is organized and how its components relate, using pluggable language/framework-specific architecture analyzers. Start with Java/Spring Boot, then add further analyzer packs such as TypeScript/JavaScript with React or Angular and Python as separate, testable increments. Architecture discovery is only as deep as the relevant parser/framework analyzer; unsupported stacks must retain generic structure analysis and show the limitation.

### Features

-   Identify likely application layers:
    -   Controller / REST layer.
    -   Service / business layer.
    -   Repository / DAO layer.
    -   Domain / entity / model layer.
    -   Configuration and infrastructure.
-   Discover Maven modules and likely module responsibilities.
-   Discover REST endpoints from Spring annotations.
-   Identify request/response DTOs.
-   Identify JPA entities and relationships.
-   Identify repository interfaces and entity mappings.
-   Detect dependency injection relationships.
-   Build class-level dependency relationships from imports, fields,
    constructors and method signatures.
-   Detect possible circular dependencies.
-   Identify common patterns where evidence supports them, such as
    factory, strategy, adapter and repository patterns.
-   Generate architecture diagrams using a diagram format such as
    Mermaid.
-   Provide an architecture report explaining the detected structure and
    limitations.

### Implementation guidance

-   Use JavaParser or another maintained Java AST parser.
-   Parse Spring annotations and configuration.
-   Parse Maven module relationships.
-   Use static rules first; use AI only to explain discovered structures
    or summarize module responsibilities.
-   Store raw parser facts separately from inferred descriptions.
-   Do not claim a design pattern solely because a class name contains
    "Factory" or "Strategy".

### AI usage

Optional. The platform should still generate structural architecture
information without an LLM. If AI is enabled, pass only relevant module
summaries and evidence rather than the entire repository.

### Exit criteria

A developer can view a useful module/layer/API overview and an
architecture diagram for a supported Java repository, with evidence and
confidence labels.

------------------------------------------------------------------------

## MVP 3 -- AI-Powered Code Insights

**Purpose:** Add model-assisted explanations and recommendations without
making AI mandatory for the platform.

### Features

-   Explain a selected class or method.
-   Explain a detected code-quality finding in plain language.
-   Explain why a security finding may matter.
-   Explain likely performance concerns.
-   Suggest refactoring approaches.
-   Summarize a module's responsibility.
-   Generate a technical-debt summary from deterministic findings.
-   Allow the user to choose an available AI provider/model.
-   Show when an answer is AI-generated and which evidence was provided.

### AI implementation requirements

-   Provider abstraction as defined in Section 5.
-   Prompt templates versioned in the application.
-   Structured output where possible, validated against a schema.
-   Bounded input/output token budgets.
-   Configurable timeouts and retry limits.
-   Rate-limit handling.
-   Response caching based on provider, model, prompt version,
    repository commit and input hash.
-   Token/usage tracking where provider responses expose it.
-   Never retry indefinitely.
-   Never send an entire repository by default.
-   Provide a "copy evidence" or "view source" path for every
    explanation.

### Example interaction

User selects `PaymentService.calculatePayment()` and requests an
explanation.

The platform should:

1.  Retrieve the method and relevant types.
2.  Retrieve only directly relevant dependencies and configuration.
3.  Send a bounded prompt to the configured model.
4.  Ask for a concise explanation grounded in supplied evidence.
5.  Validate the response.
6.  Display source references and state that the explanation is
    AI-generated.

### Exit criteria

A user can request an explanation of selected code or findings using an
available free/local provider, while the application remains fully
usable if no provider is configured.

------------------------------------------------------------------------

## MVP 4 -- RAG-Based Repository Q&A

**Purpose:** Allow natural-language questions about a repository and
answer them using relevant source-code evidence.

### Features

-   Index supported source files and selected documentation.
-   Chunk code by meaningful units such as class, method and
    configuration section rather than arbitrary character counts alone.
-   Store chunk metadata:
    -   Repository ID.
    -   Commit SHA.
    -   File path.
    -   Language.
    -   Module.
    -   Class and method names.
    -   Line range.
    -   Chunk type.
    -   Content hash.
-   Generate embeddings using a configurable embedding provider.
-   Store vectors in PostgreSQL using pgvector, or use a replaceable
    vector-store adapter.
-   Retrieve relevant chunks using semantic search.
-   Add keyword/symbol search for exact identifiers.
-   Use hybrid retrieval (semantic + keyword + metadata filters).
-   Build a bounded context for the selected model.
-   Answer with file path and line references.
-   Support follow-up questions with controlled conversation context.
-   Re-index only changed files when a new commit is analyzed.

### Example questions

-   Where is authentication implemented?
-   Explain the request flow for creating an access request.
-   Which classes communicate with the database?
-   Where is the payment failure handled?
-   Which APIs call this service?
-   How is configuration loaded?

### RAG quality requirements

-   Never answer as if a retrieved snippet proves something outside its
    scope.
-   If evidence is insufficient, say so and ask a clarifying question or
    identify what could not be found.
-   Include source references in every repository-specific answer.
-   Keep the repository commit consistent across retrieval.
-   Prevent cross-repository and cross-tenant retrieval leakage.
-   Treat retrieved source as untrusted data and defend against prompt
    injection.
-   Use a retrieval evaluation dataset with representative questions and
    expected source files.
-   Measure retrieval quality separately from answer quality.

### Free-tier and local-model considerations

-   Embedding provider must be configurable.
-   Prefer a local embedding model for confidential repositories and
    predictable per-request cost where hardware permits.
-   A hosted free embedding endpoint may be used only when its current
    quota and data terms are acceptable.
-   Do not assume that a model used for chat is also suitable for
    embeddings.
-   Cache embeddings by content hash, model and model version.
-   Avoid regenerating embeddings for unchanged chunks.
-   Make vector storage optional until this phase; MVP 1--3 must not
    require it.

### Exit criteria

A user can ask repository-specific questions and receive grounded
answers with source references. If no model is available, the UI should
explain that conversational answers are unavailable while deterministic
repository search remains usable.

------------------------------------------------------------------------

## MVP 5 -- Intelligent Code Impact Analysis

**Purpose:** Estimate what may be affected by a proposed code change.

### Features

-   Method-level and class-level dependency graph.
-   Callers and callees for supported Java code.
-   Field and constructor injection relationships.
-   Module dependency relationships.
-   REST endpoint-to-service relationships where detectable.
-   Entity/repository relationships.
-   Test-to-production-code relationships where detectable.
-   Potentially affected APIs, modules and tests.
-   Risk score based on transparent factors.
-   Suggested regression-test areas.
-   Evidence-backed explanation of the impact.

### Example interaction

User asks:

"If I change `calculatePayment()` in `PaymentService`, what could be
impacted?"

The platform should attempt to identify:

-   Direct callers.
-   Indirect dependent services.
-   Controllers or APIs that reach the method.
-   Related entities and repositories.
-   Tests that call or cover the method.
-   Other modules that depend on the affected class.
-   Areas requiring manual verification.

### Implementation guidance

-   Build the graph from AST and framework/configuration analysis, not
    from an LLM's guess.
-   Store graph nodes and edges with repository commit, source evidence
    and relationship type.
-   Start with PostgreSQL tables or another simple graph representation.
-   Introduce a dedicated graph database only if measured query
    complexity or scale justifies it.
-   Use AI to summarize graph results, not to replace graph
    construction.
-   Make risk scoring explainable. For example, consider dependency
    depth, number of dependents, public API exposure, security relevance
    and test coverage. Do not present a heuristic score as a probability
    of failure.
-   Mark reflection, dynamic dispatch, generated code and runtime wiring
    as limitations when they cannot be resolved statically.

### Exit criteria

For supported Java code, the user can select a method/class or describe
a change and receive a traceable list of likely affected components and
tests, along with explicit limitations.

------------------------------------------------------------------------

## MVP 6 -- Agentic Repository Intelligence

**Purpose:** Introduce controlled multi-step investigations that combine
the capabilities built in earlier phases.

### Possible specialist roles

-   Repository Agent -- gathers repository facts and scope.
-   Architecture Agent -- interprets architecture evidence.
-   Code Quality Agent -- prioritizes maintainability concerns.
-   Security Agent -- reviews scanner findings and relevant code
    evidence.
-   Performance Agent -- investigates likely performance concerns.
-   Impact Analysis Agent -- uses the dependency graph.
-   Test Agent -- identifies test gaps and regression-test candidates.
-   Documentation Agent -- produces a consolidated technical report.
-   Orchestrator -- plans and coordinates the investigation.

These are logical roles. They do not need to be separate deployed
services or separate LLM calls in the first implementation.

### Example investigation

User asks:

"Analyze this repository and identify the top 10 technical risks,
explain why they matter, and recommend the order in which the team
should address them."

The orchestrator may:

1.  Confirm repository and commit scope.
2.  Retrieve existing deterministic scan results.
3.  Request focused investigations from relevant specialist workflows.
4.  Correlate findings and remove duplicates.
5.  Prioritize risks using transparent criteria.
6.  Produce a consolidated report with evidence, uncertainty and
    recommended next steps.

### Guardrails

-   Start with read-only investigations.
-   Apply per-task budgets for model calls, tokens, time and tool
    execution.
-   Require human approval before changing source files, creating
    branches, opening pull requests or triggering external side effects.
-   Restrict each agent to explicitly allowed tools and repository
    scope.
-   Keep an audit trail of steps, inputs, outputs and evidence.
-   Prevent agents from treating repository text as trusted
    instructions.
-   Use deterministic workflows for known tasks; do not introduce agents
    where a normal service call is sufficient.
-   Add a framework such as LangGraph only when dynamic multi-step
    orchestration is genuinely needed. A normal Spring Boot
    workflow/orchestrator is sufficient initially.

### Possible future extensions

-   Generate JUnit tests for human review.
-   Propose a patch for an identified issue.
-   Review a pull request.
-   Analyze only changed files in a pull request.
-   Compare repository health across commits.
-   Produce onboarding documentation.
-   Create architecture decision-record drafts.
-   Open a pull request only after explicit user approval.

### Exit criteria

A user can request a multi-category repository investigation and receive
one consolidated, evidence-backed report with traceable specialist steps
and human-controlled actions.

------------------------------------------------------------------------

# 7. Recommended Technical Architecture

## 7.1 High-level components

``` text
React Web Application
        |
        v
Spring Boot REST API
        |
        +--------------------+
        |                    |
        v                    v
Repository Service      Analysis Orchestrator
        |                    |
        v                    +----------------------------+
GitHub Connector             |             |              |
        |                    v             v              v
        v                Static       Architecture     AI Provider
Repository Snapshot     Analyzers      Analyzer        Adapters
        |                    |             |              |
        +--------------------+-------------+--------------+
                             |
                             v
                       Analysis Results
                             |
              +--------------+---------------+
              |              |               |
              v              v               v
          PostgreSQL       Report         (Later) Vector
          Metadata         Service          Index
              |                              |
              +------------------------------+
                             |
                             v
                       React Dashboard
```

## 7.2 Technology-independent core and analyzer-pack architecture

The platform must use a **plugin-style analyzer architecture**. This is a core architectural requirement, not a later refactoring task. The repository ingestion and analysis orchestration layers must not contain Java-only assumptions.

**Technology-independent core responsibilities**

- Repository URL validation, GitHub access, snapshot/commit tracking and safe file ingestion.
- File classification, content hashing, size/resource limits and generic repository metrics.
- Technology detection and analyzer capability discovery.
- Analyzer registration, selection, execution, status tracking and result normalization.
- Common finding/evidence model, persistence, reporting and UI capability presentation.

**Recommended analyzer contracts** (names are illustrative; choose idiomatic interfaces):

- `TechnologyDetector`: detects languages, frameworks, build tools and versions from repository evidence.
- `RepositoryAnalyzer`: generic file/tree/manifest analysis that can run for any repository.
- `DependencyAnalyzer`: inventories dependencies for a supported package/build ecosystem.
- `CodeAnalyzer`: runs language-aware static/code-quality checks.
- `SecurityAnalyzer`: runs security rules and reports actual language/rule coverage.
- `TestAnalyzer`: discovers test frameworks and parses supported test/coverage reports.
- `ArchitectureAnalyzer`: discovers modules, symbols, layers, APIs and relationships for supported languages/frameworks.
- `ImpactAnalyzer`: calculates change impact using relationships produced by supported language analyzers.

Each analyzer should declare its supported languages, frameworks, file/build manifests, capability level, version, required tools, configuration and limitations. The orchestrator should select applicable analyzers based on detected technologies, not hard-coded repository names. A repository can contain multiple languages and should run all applicable analyzers.

**Analyzer capability levels**

- `GENERIC`: technology-independent repository facts (file tree, extensions, file counts, manifests detected).
- `BASIC`: reliable inventory or rule checks for a language/ecosystem.
- `DEEP`: AST/symbol/framework-aware analysis such as architecture and call/dependency relationships.
- `UNSUPPORTED` / `UNAVAILABLE`: no compatible analyzer or required tool is configured.

Do not claim that the product supports a technology deeply merely because it can detect its file extensions. Expose capability level, analyzer version, evidence and limitations in the report.

**Initial and planned analyzer packs**

- First deep-support pack: Java, Spring Boot, Maven, JavaParser, Maven dependency metadata, JUnit and JaCoCo where present.
- Planned packs, added incrementally after MVP 1: JavaScript/TypeScript with Node.js, React and Angular; Python; Go; .NET/C#.
- Generic repository analysis should remain available for other languages even before a dedicated pack exists.
- Prioritize additional packs based on actual usage and tool/parser maturity; do not promise equal feature parity across languages.

**Adding a language must be additive:** a new analyzer pack should register its capabilities and implement the common contracts. It must not require rewriting repository ingestion, persistence, orchestration, reporting or the frontend. Avoid one giant switch statement with language-specific logic spread across the codebase.

## 7.3 Suggested backend modules/packages

Use a modular monolith initially. Do not split into microservices
prematurely.

A possible package structure:

``` text
com.example.repointelligence
  config
  common
    exception
    logging
    security
    validation
  repository
    api
    application
    domain
    infrastructure
      github
      storage
  analysis
    api
    application
    domain
    orchestration
    capability
    registry
    spi                 # common analyzer interfaces/contracts
    generic             # technology-independent analyzers
    packs
      java              # Java/Spring/Maven analyzer implementations
      javascript        # later: JS/TS/Node/React/Angular
      python            # later
      go                # later
      dotnet            # later
  architecture
    api
    application
    parser              # shared parser abstractions/adapters
    graph
  ai
    api
    application
    domain
    provider
      ollama
      gemini
      openrouter
    prompt
    usage
    cache
  rag                 # introduced in MVP 4
    ingestion
    chunking
    embedding
    retrieval
    vectorstore
  impact              # introduced in MVP 5
  agent                # introduced in MVP 6
  report
  persistence
```

Package names are illustrative. Keep boundaries clear and avoid creating
empty abstractions without a real use.

## 7.4 Suggested frontend areas

-   Dashboard / repository list.
-   Add repository.
-   Repository overview.
-   Technology stack.
-   Structure and modules.
-   Architecture diagram.
-   Dependencies.
-   Code quality findings.
-   Security findings.
-   Test analysis.
-   AI insights.
-   Repository Q&A.
-   Impact analysis.
-   Analysis history and settings.

Only implement screens needed for the current MVP. Do not build empty
screens for future phases.

## 7.5 Suggested persistence model

Start with a relational schema. Names can be adjusted by the
implementation assistant.

-   `repository`: repository URL, owner, name, default branch,
    visibility, created/updated timestamps.
-   `repository_snapshot`: repository ID, commit SHA, branch, ingestion
    status, analysis status, timestamps.
-   `analysis_run`: snapshot ID, analyzer/version, category, status,
    start/end time, error summary.
-   `file_record`: snapshot ID, path, language, size, content hash, file
    type.
-   `technology_finding`: snapshot ID, technology, version, confidence, evidence path and detection method.
-   `analyzer_capability`: snapshot ID, analyzer ID/version, technology scope, capability level, status and limitation/reason.
-   `analyzer_execution`: snapshot ID, analyzer ID/version, status, duration, configuration summary and error details.
-   `dependency`: snapshot ID, module, group/artifact, version, scope,
    vulnerability status.
-   `analysis_finding`: snapshot ID, category, rule ID, severity, title,
    description, file path, line range, evidence metadata, status.
-   `module_record`: snapshot ID, module name, path, detected
    responsibility and evidence.
-   `api_endpoint`: snapshot ID, HTTP method, route, class, method and
    source location.
-   `code_symbol`: snapshot ID, symbol type, fully qualified name, file
    path, line range.
-   `dependency_edge`: snapshot ID, source symbol, target symbol,
    relationship type, evidence.
-   `test_record`: snapshot ID, test symbol, production symbol
    relationship and evidence.
-   `ai_usage_record`: provider, model, feature, repository snapshot,
    input/output usage where available, estimated cost, status,
    timestamp.
-   `ai_cache`: cache key, provider/model, prompt version, response,
    creation/expiry metadata.
-   `conversation` and `conversation_message`: introduced with RAG.
-   `code_chunk` and embedding/vector storage: introduced with RAG.
-   `agent_run` and `agent_step`: introduced with agent workflows.

Important: include snapshot/commit identity in repository-derived
records so data from different commits is not mixed.

------------------------------------------------------------------------

# 8. Free-Tier AI and Cost-Control Strategy

This is a core product requirement, not an optional optimization.

## 8.1 Provider strategy

Support a provider selection configuration such as:

``` yaml
ai:
  enabled: false
  default-provider: ollama
  providers:
    ollama:
      enabled: false
      base-url: http://localhost:11434
      chat-model: ${OLLAMA_CHAT_MODEL:}
      embedding-model: ${OLLAMA_EMBEDDING_MODEL:}
    gemini:
      enabled: false
      api-key: ${GEMINI_API_KEY:}
      chat-model: ${GEMINI_CHAT_MODEL:}
    openrouter:
      enabled: false
      api-key: ${OPENROUTER_API_KEY:}
      chat-model: ${OPENROUTER_CHAT_MODEL:}
  limits:
    max-input-tokens: 12000
    max-output-tokens: 1500
    max-calls-per-analysis: 10
    timeout-seconds: 45
    max-retries: 1
```

This is an example configuration shape, not a promise that the named
provider/model is currently free or that every model supports every
capability. Validate provider capabilities at runtime.

Do not commit real keys. Use environment variables or a secrets manager.

## 8.2 Provider selection and fallback

-   Allow the user/developer to configure the preferred provider.
-   Support local Ollama as an option for private code and to avoid
    per-token hosted API charges.
-   Support hosted providers with free quotas when available.
-   Provide a model capability registry: chat, structured output,
    embeddings, context size and streaming support.
-   Do not silently switch confidential code from a local provider to a
    hosted provider.
-   Any hosted fallback for private repositories must require explicit
    configuration/consent.
-   If a provider returns quota/rate-limit errors, mark the AI operation
    as unavailable or deferred. Do not create a retry storm.
-   A fallback provider may be used only when its data-handling policy
    is permitted for that repository.

## 8.3 Do not assume free means unlimited

Free-tier terms can change and often include request limits, daily
quotas, rate limits, model availability or data-use conditions.

The application should:

-   Make provider/model names configurable.
-   Display provider availability and configured limits.
-   Record failed calls and quota errors.
-   Allow the user to disable hosted AI entirely.
-   Avoid promising that a particular model will always be free.
-   Include a provider setup guide that asks users to verify current
    official terms before using confidential source code.

## 8.4 Token and request optimization

Implement these controls before adding large AI workflows:

1.  **Selective analysis:** send only relevant classes/methods and
    findings.
2.  **Chunking:** split source into class/method-sized units.
3.  **Summarization:** create compact module/class summaries that can be
    reused.
4.  **Caching:** cache responses using repository commit, content hash,
    provider/model and prompt version.
5.  **Incremental analysis:** do not reprocess unchanged files.
6.  **Deduplication:** merge repeated findings before asking a model to
    summarize them.
7.  **Bounded prompts:** set maximum input and output budgets.
8.  **Structured output:** request compact JSON when the feature needs
    structured data.
9.  **Batching:** group related small items when this does not exceed
    context limits or reduce answer quality.
10. **No unnecessary conversation history:** include only relevant prior
    context.
11. **No full-repository prompts:** never send the whole repository by
    default.
12. **Deterministic-first:** use scanner/parser output instead of asking
    an LLM to rediscover it.
13. **Model routing:** use a smaller/cheaper/local model for simple
    summaries and reserve stronger models for complex synthesis.
14. **Embedding reuse:** cache embeddings by chunk hash and embedding
    model version.

## 8.5 AI usage visibility

Provide an AI usage page or panel showing, where available:

-   Provider and model.
-   Feature that triggered the call.
-   Repository snapshot.
-   Request timestamp and status.
-   Input and output tokens.
-   Estimated cost, if reliable pricing data is configured.
-   Cache hit/miss.
-   Rate-limit/quota failures.

Do not invent token counts when a provider does not return them. Mark
estimates as estimates and distinguish them from provider-reported
usage.

## 8.6 Ollama considerations

-   Ollama can run local models without a per-token hosted API charge.
-   It still consumes local CPU, RAM, GPU and electricity.
-   Model selection must account for available hardware and context
    length.
-   Provide a health check and a clear setup guide.
-   Do not assume Ollama is installed or running.
-   If it is unavailable, non-AI functionality must continue to work.
-   Keep model names configurable rather than embedding a single model
    name throughout the code.

------------------------------------------------------------------------

# 9. Security, Privacy and Trust Requirements

These requirements apply from MVP 1 onward.

## 9.1 Repository access

-   MVP 1: public repositories only.
-   Later: private repository access through GitHub App or OAuth with
    minimum read-only permissions.
-   Never ask users to paste personal access tokens into ordinary
    repository fields.
-   Store credentials securely and never log them.
-   Provide a way to revoke repository access.
-   Respect GitHub rate limits and terms.

## 9.2 Source-code privacy

-   Make it clear whether source code is processed locally or sent to a
    hosted provider.
-   Default to no hosted AI processing until configured.
-   Never send secrets detected by scanners to a model.
-   Avoid retaining raw source code longer than necessary.
-   Provide configurable retention/deletion for repository snapshots and
    derived data.
-   Do not use submitted source code for model training.
-   Do not claim a provider's privacy guarantees; link to the provider's
    current terms in product documentation.

## 9.3 Untrusted code and tool execution

-   Do not run arbitrary build scripts on the API server.
-   If build/test execution is implemented, isolate it in a
    worker/container.
-   Apply CPU, memory, disk, process and time limits.
-   Disable outbound network by default for untrusted build execution.
-   Use read-only mounts where practical.
-   Clean up temporary workspaces after processing.
-   Do not mount application secrets into repository-processing
    containers.

## 9.4 Prompt injection

A README, comment, source file or retrieved chunk may contain malicious
instructions.

-   Treat all repository content as untrusted evidence.
-   Separate system instructions from repository content.
-   Explicitly tell the model that retrieved source is data and must not
    override system instructions.
-   Do not allow source content to invoke tools or change permissions.
-   Keep tool authorization in backend code, not in model-generated
    text.
-   Validate structured model output before use.

## 9.5 Application security

-   Validate URLs and prevent server-side request forgery.
-   Restrict outbound access to approved GitHub endpoints and configured
    provider endpoints.
-   Apply authentication and authorization before supporting multiple
    users or private repositories.
-   Use secure secret storage.
-   Sanitize rendered Markdown and code excerpts.
-   Avoid exposing stack traces or secrets in API errors.
-   Apply request-size and rate limits.

------------------------------------------------------------------------

# 10. Reliability, Performance and Scalability

-   Use asynchronous analysis jobs for repository ingestion and scans.
-   Return a job/run identifier and provide status polling or
    server-sent events later.
-   Track each analysis category independently so one tool failure does
    not fail the entire report.
-   Use bounded concurrency for repository processing.
-   Enforce maximum repository size, file count, file size and total
    analysis duration.
-   Make long-running scanner processes cancellable.
-   Use timeouts for GitHub and AI provider calls.
-   Make operations idempotent where possible.
-   Record analyzer versions so results can be compared meaningfully.
-   Avoid holding full repository contents in memory.
-   Store temporary files in a controlled workspace and clean them up.
-   Start with a modular monolith and a simple worker mechanism;
    introduce a message broker only when workload justifies it.

Suggested initial configurable limits (tune after testing):

-   Maximum repository size: configurable, initially conservative.
-   Maximum individual source file size: configurable.
-   Maximum file count: configurable.
-   Maximum analysis duration: configurable.
-   Maximum concurrent repository jobs: configurable.
-   Maximum AI calls per analysis: configurable.

Do not hard-code these limits in business logic.

------------------------------------------------------------------------

# 11. API Design -- Initial Endpoint Suggestions

The implementation assistant may refine these endpoints, but keep the
API resource-oriented and versioned.

  ------------------------------------------------------------------------------------------------------------
  Method                  Endpoint                                                     Purpose
  ----------------------- ------------------------------------------------------------ -----------------------
  POST                    `/api/v1/repositories/analyze`                               Submit a repository URL
                                                                                       and start
                                                                                       ingestion/analysis

  GET                     `/api/v1/repositories`                                       List analyzed
                                                                                       repositories

  GET                     `/api/v1/repositories/{repositoryId}`                        Repository details

  GET                     `/api/v1/repositories/{repositoryId}/snapshots`              List analyzed commits

  GET                     `/api/v1/analysis-runs/{runId}`                              Get analysis status

  GET                     `/api/v1/repositories/{repositoryId}/reports/{snapshotId}`   Get consolidated report

  GET                     `/api/v1/repositories/{repositoryId}/findings`               Filter findings

  GET                     `/api/v1/repositories/{repositoryId}/dependencies`           Dependency inventory

  GET                     `/api/v1/repositories/{repositoryId}/architecture`           Architecture data (MVP
                                                                                       2)

  POST                    `/api/v1/repositories/{repositoryId}/insights`               Request AI explanation
                                                                                       (MVP 3)

  POST                    `/api/v1/repositories/{repositoryId}/questions`              Ask repository question
                                                                                       (MVP 4)

  POST                    `/api/v1/repositories/{repositoryId}/impact-analysis`        Request impact analysis
                                                                                       (MVP 5)

  GET                     `/api/v1/ai/providers`                                       Show configured
                                                                                       provider
                                                                                       capabilities/status

  GET                     `/api/v1/ai/usage`                                           Show AI usage records
  ------------------------------------------------------------------------------------------------------------

For MVP 1, implement only endpoints needed by the actual UI. Do not
create placeholder endpoints for future phases.

------------------------------------------------------------------------

# 12. UX and Reporting Recommendations

The interface should help a developer understand the result quickly
rather than overwhelm them with raw scanner output.

## 12.1 Repository overview

Show:

-   Repository name and URL.
-   Analyzed branch and commit SHA.
-   Last analysis date.
-   Primary language and detected frameworks.
-   Module and source-file counts.
-   Dependency count.
-   Quality/security/test status.
-   Analysis categories that were skipped or unavailable.

## 12.2 Finding presentation

Every finding should show:

-   Severity.
-   Category.
-   Short title.
-   Plain-language description.
-   File and line reference.
-   Rule/scanner source.
-   Evidence.
-   Suggested next step, if available.
-   Whether the description is deterministic or AI-generated.

Provide filters for severity, category, module and file.

## 12.3 Honest status labels

Use distinct statuses:

-   Completed.
-   Completed with warnings.
-   Not configured.
-   Not supported.
-   Skipped.
-   Failed.
-   Not analyzed.

Never display a missing metric as zero. For example, "Coverage
unavailable" is different from "0% coverage".

## 12.4 Report export

A later enhancement may export HTML, Markdown or PDF. Prioritize a
useful on-screen report and machine-readable API response first.

------------------------------------------------------------------------

# 13. Testing and Quality Strategy

## 13.1 Backend tests

-   Unit tests for URL validation, repository identity and commit
    resolution.
-   Parser tests using small representative Java projects.
-   Tests for Maven dependency parsing.
-   Tests for finding normalization and severity mapping.
-   Provider adapter tests using mocked HTTP responses.
-   Tests for quota, timeout and unavailable-provider behavior.
-   Tests ensuring the app starts with AI disabled.
-   Tests for repository isolation and snapshot separation.

## 13.2 Frontend tests

-   Repository URL validation.
-   Loading/progress states.
-   Empty and error states.
-   Findings filtering and sorting.
-   Source-reference display.
-   AI-unavailable state.
-   Provider configuration and usage display when implemented.

## 13.3 Integration and security tests

-   Test a small public sample repository.
-   Test malformed URLs and unsupported hosts.
-   Test large files and oversized repositories.
-   Test rate-limit responses.
-   Test malicious path names and symlinks.
-   Test prompt-injection strings in README/source files.
-   Test that secrets are redacted from reports and logs.
-   Test that one failed analyzer does not erase successful analyzer
    results.

## 13.4 Evaluation datasets

Maintain a small set of known Java repositories or synthetic fixtures
with expected outcomes:

-   Known Spring Boot layers.
-   Known Maven dependencies.
-   Known REST endpoints.
-   Known JPA entities.
-   Known test classes.
-   Known security/code-quality examples.

Use these fixtures to detect regressions in parsers and analysis logic.

------------------------------------------------------------------------

# 14. Implementation Priorities and Recommended Delivery Order

The coding assistant must implement in small, verifiable increments.

Recommended order:

1.  Inspect the existing workspace and report its current state before
    changing files.
2.  Create the MVP 0 project foundation.
3.  Build the repository URL validation and metadata flow.
4.  Implement repository snapshot/commit tracking.
5.  Implement file-tree ingestion with safety limits.
6.  Implement technology-independent file classification and technology detection; introduce analyzer registration/capability contracts.
7.  Implement the Java/Spring Boot/Maven analyzer pack as the first deeply supported stack, plus generic analysis for other repositories.
8.  Integrate one deterministic code-quality scanner and report its actual language/rule coverage.
9.  Integrate one deterministic security scanner and report its actual language/rule coverage.
10. Implement test discovery and JaCoCo report ingestion for the Java pack, and generic test-manifest discovery for other stacks where feasible.
11. Build the consolidated report API and capability matrix.
12. Build the React repository overview and findings UI.
13. Add integration fixtures for Java/Spring Boot and at least one non-Java repository (for example React/TypeScript) to prove generic fallback behavior.
14. Verify the full MVP 1 flow with AI disabled.
15. Only after MVP 1 is stable, proceed to deeper architecture discovery and additional analyzer packs.
16. Add AI provider abstraction and one provider adapter when MVP 3 begins.
17. Add additional providers, RAG, impact analysis and agent workflows only in their respective phases.

Do not implement all six MVPs in one large code-generation step. Each
phase should end with a working build, tests, updated documentation and
a demonstrable user flow.

------------------------------------------------------------------------

# 15. Explicit Instructions for Antigravity / Coding Assistant

Treat this document as the product requirements and implementation
direction. Follow these instructions while implementing.

## 15.1 Before coding

-   Inspect the current workspace and existing code.
-   Identify whether the repository is empty or contains an existing
    project.
-   Summarize the proposed architecture and implementation plan.
-   Identify assumptions and risks.
-   Do not overwrite existing files without understanding their purpose.
-   If a decision is genuinely blocking, ask a concise question.
    Otherwise choose a sensible, documented default.

## 15.2 Scope control

-   Implement **MVP 0 and MVP 1 only** in the initial delivery unless
    the user explicitly asks to continue.
-   Keep the code structured so later MVPs can be added without major
    redesign.
-   Do not prematurely implement agents, vector search or graph databases.
-   Do not attempt deep support for every language in MVP 1; however, the technology-independent core, analyzer contracts, capability reporting and generic fallback are mandatory from the beginning. Do not hard-code the product architecture around Java.
-   Do not add unnecessary microservices.
-   Do not introduce paid services as mandatory dependencies.
-   Do not add a feature merely because it is easy for an LLM to
    generate.

## 15.3 Engineering quality

-   Use current stable compatible versions of Java, Spring Boot, React
    and dependencies at implementation time; document selected versions.
-   Prefer well-maintained libraries.
-   Avoid deprecated APIs.
-   Use clear names and small, cohesive classes.
-   Keep configuration externalized.
-   Add tests for meaningful behavior.
-   Do not leave critical paths as TODOs or mocked implementations while
    presenting them as complete.
-   If a scanner requires an external installation or
    license/configuration, document it and provide a graceful
    unavailable state.
-   Provide reproducible local setup instructions.
-   Update the README as implementation evolves.

## 15.4 AI-specific restrictions

-   MVP 0 and MVP 1 must not call an LLM.
-   Do not add a mandatory AI API key.
-   Do not send repository contents to a hosted model.
-   Keep all future AI integrations behind the provider abstraction.
-   Make model/provider settings configurable.
-   Include tests proving that AI-disabled operation works.
-   Do not hard-code a supposedly free model or assume a free tier is
    unlimited.
-   Do not use AI output as the source of truth for security, dependency
    or coverage metrics.

## 15.5 Completion report

At the end of each MVP, provide:

-   Features implemented.
-   Architecture and important design decisions.
-   Files/modules created or changed.
-   Commands to run the application.
-   Commands to run tests.
-   External tools/services required.
-   Features not implemented and why.
-   Known limitations.
-   Security considerations.
-   Recommended next steps.

------------------------------------------------------------------------

# 16. Definition of Done

MVP 1 is complete only when all of the following are true:

-   A developer can start the application locally using documented
    instructions.
-   A developer can submit a valid public GitHub Java + Spring Boot +
    Maven repository URL.
-   The platform resolves and records a commit SHA.
-   The platform safely ingests repository metadata and supported source
    files.
-   It detects technology and basic project structure.
-   It inventories Maven dependencies.
-   It produces configured code-quality and security findings with
    evidence, or clearly reports why the relevant scanner is
    unavailable.
-   It identifies tests and uses actual JaCoCo data when present.
-   It generates a consolidated repository report.
-   The UI displays the report and finding details.
-   A failed analyzer does not make all other analysis results
    disappear.
-   The system enforces repository processing limits and does not
    execute untrusted code on the API server.
-   Secrets are not exposed in logs or reports.
-   The application works with AI disabled and with no AI API key.
-   Automated tests cover the main happy path and important failure
    paths.
-   README and configuration examples are complete enough for another
    developer to run the project.

------------------------------------------------------------------------

# 17. Recommended Decisions at a Glance

  -----------------------------------------------------------------------
  Area                                Recommendation
  ----------------------------------- -----------------------------------
  Initial deep analyzer support        Public Java + Spring Boot + Maven; generic analysis for other stacks

  Frontend                            React

  Backend                             Java + Spring Boot

  Architecture                        Modular monolith initially

  Database                            PostgreSQL

  Migrations                          Flyway or Liquibase

  GitHub access                       GitHub API first; clone only when
                                      required

  Java parsing                        JavaParser or a maintained
                                      equivalent, isolated in the Java analyzer pack

  Analyzer architecture                Technology-independent core with pluggable language/framework analyzer packs

  Initial generic support              Language/file detection, repository structure, manifest detection and capability reporting for all repositories

  Future analyzer packs                JavaScript/TypeScript (Node/React/Angular), Python, Go and .NET, prioritized incrementally

  Code quality                        SonarQube/SonarScanner, PMD or
                                      Checkstyle as appropriate

  Security scanning                   Semgrep plus dependency
                                      vulnerability scanning

  Coverage                            JaCoCo report ingestion

  AI in MVP 1                         None

  Local AI option                     Ollama

  Hosted AI options                   Configurable providers such as
                                      Gemini or OpenRouter, subject to
                                      current free-tier terms

  Embeddings                          Local/configurable embedding
                                      provider, introduced in MVP 4

  Vector storage                      pgvector, introduced in MVP 4

  Dependency graph                    Relational graph tables initially

  Agent orchestration                 Normal Spring Boot workflows first;
                                      framework later if justified

  Repository execution                Disabled by default; isolated
                                      worker if later enabled

  Cost control                        Selective prompts, caching,
                                      incremental analysis, bounded
                                      tokens/calls

  Evidence                            Commit SHA, file path, line range,
                                      rule and analysis method

  First implementation scope          MVP 0 + MVP 1 only
  -----------------------------------------------------------------------

------------------------------------------------------------------------

# 18. Final Product Outcome

When all phases are complete, a developer should be able to submit a
GitHub repository URL and progressively use the platform to:

1.  Understand the technology stack and project structure across supported repositories, with clear capability and coverage indicators.
2.  View the architecture, modules, APIs and data relationships.
3.  Identify quality, security, dependency and testing concerns.
4.  Ask questions and receive repository-grounded explanations.
5.  Estimate the impact of changing a class, method or API.
6.  Run a controlled multi-step investigation into repository health and
    technical risks.

The platform's differentiator is not simply that it uses AI. Its
differentiator is that it combines **deterministic code intelligence,
repository-specific evidence, optional AI reasoning and cost-conscious
architecture** in one progressive developer experience.
