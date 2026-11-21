# MVP 2 Completion Report: Architecture Intelligence

**Date:** 2026-11-21
**Plan:** [implementationPlan.md](implementationPlan.md), Steps 2.1 to 2.6
**Branch:** `mvp2` (not merged)
**Result:** All six steps implemented and tested: backend `mvnw clean verify` 619 tests, 0 failures (2 skipped: the existing Semgrep-tagged tests); frontend 124 tests, lint, typecheck and build green. Nine of the ten MVP 2 exit criteria are verified on the evaluation fixtures and on spring-petclinic in Docker (section 5a). The tenth, diagrams rendering in Mermaid, is verified up to parsing with the real Mermaid parser and strict mode; the rendered SVG was not looked at in a browser (section 8).

## 1. Features implemented

| Area | Delivered |
|---|---|
| Schema | Flyway `V5`: `code_symbol`, `dependency_edge`, `api_endpoint`, `entity_relationship` (raw parser facts); `layer_assignment`, `pattern_candidate` (inferred); `module_dependency`. All rows carry `snapshot_id` and `run_id` and cascade from them |
| SPI | `ARCHITECTURE` category, `ArchitectureAnalyzer`, `ArchitectureResult` (items linked by keys; the orchestrator turns keys into ids and drops, and counts, anything naming an unknown symbol) |
| Parsing | `SpringArchitectureAnalyzer` (Java pack, DEEP): types, methods, constructors, fields with annotations (literal attributes only) and modifiers; edges for extends/implements, imports, field/parameter/return types incl. type arguments, injection, method calls and instantiation. Third-party targets stay `UNRESOLVED` with their name, duplicates are `AMBIGUOUS`, JDK types are left out |
| Injection | `@Autowired`/`@Inject`/`@Resource` fields, annotated or single constructors of beans, Lombok `@RequiredArgsConstructor`/`@AllArgsConstructor` (LIKELY), setter injection, collection/map/Optional/ObjectProvider injection by element type |
| Spring discovery | Layers (annotation and Spring Data hierarchy DETECTED; DTO from endpoint signatures LIKELY; package names LIKELY; plain `@Component` POSSIBLE); REST endpoints (class + method paths, all HTTP methods, project constants resolved, other expressions kept in braces and flagged); request/response types unwrapped from `ResponseEntity`, `List`, `Optional`, `Mono`, ...; JPA associations with `mappedBy`, `fetch`, `cascade`, join column/table; repository-to-entity mapping from generics; injection cycles (MEDIUM) and type cycles (LOW) via Tarjan; pattern candidates from structure only (Spring Data repository DETECTED, strategy LIKELY, factory and adapter POSSIBLE) |
| Modules | Maven module dependencies from the MVP 1 reactor model; every symbol carries its module path |
| Report API | `GET /api/v1/repositories/{id}/snapshots/{snapshotId}/architecture`: layers with evidence and GitHub links, endpoints, entities (table, relationships, repositories), injections with resolution, DI chains, module dependencies, cycles, patterns, counts by edge resolution, limitations, and Mermaid diagrams (layers, modules, API flow, entity relationships), capped with notes; `supported: false` and the unsupported technologies when no architecture analyzer applies |
| Frontend | Architecture tab (only when an architecture analyzer ran) and `/repositories/:id/arch?snapshot=` page: overview, cycle warnings, diagrams (Mermaid 11.17.2, lazy-loaded, `securityLevel: 'strict'`, SVG text labels), layers, endpoints, entities, chains, injections, modules, patterns, limitations; an explicit "not supported" state for non-Java repositories |

## 2. Architecture and key decisions

- **No `JavaSymbolSolver` (ADR-17).** Without a classpath the symbol solver cannot resolve library types either, and it needs Guava and Javassist. A source-level resolver (imports, package, nested and inherited nested types, a project index) is deterministic and fast, and it labels instead of guessing: `UNRESOLVED` keeps the imported name, `AMBIGUOUS` lists candidates. JDK types are recognised by name (platform class loader, never initialised) and left out of the graph.
- **One analyzer, two stages.** Analyzers run in parallel and never read each other's results, so symbol extraction (Step 2.2) and Spring discovery (Step 2.3) run inside the same `SpringArchitectureAnalyzer`. Parsing code lives in the Java pack (`analysis.packs.java.architecture`); the core `architecture` package only stores and reports. The planned `architecture/parser` and `architecture/graph` packages were not created (documented in architecture.md).
- **Additions to the planned schema (ADR-18).** `entity_relationship` holds JPA associations and repository-to-entity mappings; cycles are `ARCHITECTURE` findings (`injection-cycle`, `type-cycle`), so they reuse severity, evidence, fingerprints and the findings endpoint.
- **Facts vs. inferences.** Parser facts and inferred descriptions are separate tables. DI chains and diagrams are computed at read time from resolved edges only, so nothing unresolved is ever drawn as a link.
- **Layer precedence.** Annotation and Spring Data hierarchy (DETECTED), then endpoint signature (DTO, LIKELY), then package name (LIKELY). A package match outranks a merely POSSIBLE `@Component`. Constant holders and utility classes get no layer from their package.
- **Cycle noise control.** Type cycles made only of entities (bidirectional JPA associations) and edges between a type and its nested types are not reported; a type cycle identical to an injection cycle is reported once.
- **Mermaid safety.** Node ids are synthetic; labels are quoted, control characters are flattened, text is capped at 80 characters, and `# " < > & ` | [ ] { } ( ) ; \` become numeric entity codes; `erDiagram` names are reduced to identifiers. The frontend renders with `securityLevel: 'strict'` and `htmlLabels: false`, and only sets the source as text content (no `dangerouslySetInnerHTML`). Inputs are sorted so a commit always gives the same diagram source.
- **Caps.** 50,000 symbols and 200,000 edges per run (options), types kept before members; diagrams cap nodes (most connected first) and say so; at most 500 classes per layer and 100 DI chains in the report; 50 cycle findings.
- **Versions.** Mermaid pinned to 11.17.2, the mature last 11.x release: 12.x was about ten weeks old. Upgrade when 12 has settled.

## 3. Files and modules created

- Backend `architecture` module: `domain` (7 entities, enums, `AnnotationInfo`), `infrastructure/persistence` (repositories), `application` (`ArchitectureReportService`, `ArchitectureDiagramService`, `MermaidWriter`), `api` (`ArchitectureController`, `ArchitectureReportDTO`).
- Java pack: `SpringArchitectureAnalyzer`, `architecture/` (`JavaProjectModel`, `TypeResolver`, `Annotations`, `SymbolGraphBuilder`, `SpringDiscovery`, `ModuleLayout`), rules in `resources/architecture/spring-architecture.yml`.
- SPI and orchestration: `ArchitectureAnalyzer`, `ArchitectureResult`, `ArchitectureResultWriter`; `AnalyzerResult.architecture()`; `ReportService.scope()` shared with the architecture report.
- Evaluation fixtures with `EXPECTED.md`: `spring-boot-architecture` (layers, DTOs, 7 endpoints incl. constant and unresolved paths, JPA associations, an injection cycle, strategy/factory/adapter candidates, a `PaymentFactory` that only looks like a factory), `spring-multi-module` (web → core). Golden Mermaid files in `backend/src/test/resources/golden/`.
- Frontend: `ArchitecturePage`, `components/architecture/*`, `lib/mermaid.ts`, types, API client and hook.

## 4. Run commands

```sh
# with Docker (repo root)        docker compose up --build        # UI http://localhost:5173
# backend (from backend/)        ./mvnw spring-boot:test-run      # embedded PostgreSQL
# frontend (from frontend/)      pnpm install && pnpm dev
```

## 5. Test commands

```sh
./mvnw -B clean verify                                                  # backend, includes Spotless
pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test --run && pnpm build   # frontend
./mvnw test -Dtest=ArchitectureE2eTests -Dgolden.update=true            # regenerate golden diagrams after an intended change
```

New backend tests: schema and bulk insert (3,300 symbols, 12,000 edges in about 3 s), symbol graph (13), Spring discovery (10), analyzer against the documented fixture expectations, result writer on PostgreSQL, diagram grammar and hostile labels (6), architecture end-to-end (Spring Boot fixture with golden diagrams, multi-module, React unsupported with the MVP 1 report intact, 404 across repositories). New frontend tests: Mermaid strict mode and failure state, every section, unsupported state, text-only rendering, page and tab with a mocked API, and the real Mermaid parser on every golden diagram.

## 5a. Docker and real-repository verification

Run on the development machine (Windows 11, Docker Desktop 29.7.2) with `docker compose up --build`, the existing data volume kept, no `GITHUB_TOKEN`.

| Check | Result |
|---|---|
| Upgrade of existing data | Flyway applied `V5 - architecture tables` to the persistent MVP 1 database; all services healthy |
| Registry | 3 detectors and 10 analyzers, including `spring-architecture-analyzer` |
| Real repository | `spring-projects/spring-petclinic` (commit `500158f`) with `forceRescan`: run `COMPLETED`; the architecture analyzer took about 3.4 s |
| Layers | 6 controllers, 3 Spring Data repositories (TYPE_HIERARCHY), 9 entities incl. mapped superclasses, `Vets` as DTO (LIKELY, from `@ResponseBody`), 3 configuration classes, `PetTypeFormatter` as POSSIBLE infrastructure |
| Endpoints | all 17 MVC routes with methods (e.g. `POST /owners/{ownerId}/pets/{petId}/visits/new`), none unresolved |
| Entities | tables `owners`, `pets`, `types`, `visits`, `specialties`, `vets`; `Owner.pets` 1:n, `Pet.type` n:1, `Pet.visits` 1:n, `Vet.specialties` m:n; repositories mapped to Owner, PetType, Vet |
| DI chains | controller → repository → entity (petclinic has no service layer), e.g. `PetController → PetTypeRepository → PetType` |
| Cycles / patterns | no cycles; 3 repository candidates (DETECTED), no false strategy/factory/adapter |
| Counts | 25 types, 413 relationships: 162 resolved, 247 unresolved (library types and inherited Spring Data methods), 4 ambiguous |
| Diagrams | layers, API flow (15 of 17 endpoints, with a note) and entity diagram generated; all three parse with Mermaid 11.17.2 |
| MVP 1 | all sections still present and analysed (28 dependencies, 19 test classes / 81 methods, PMD and Semgrep completed); ARCHITECTURE is a new section with a DEEP matrix row |

A first run exposed two noisy limitations (an analyzer line duplicating the report's count, and one "not supported" line per markup language); both were fixed and re-checked with a second forced run.

## 6. External tools

None new are required. JavaParser was already a dependency; Mermaid runs in the browser.

## 7. Security considerations

Repository code is still only parsed, never compiled or run; JDK types are looked up by name without initialisation, and nothing from the repository is loaded. Annotation attributes are stored only when they are literals and are redacted and length-capped like all evidence; parser warnings never contain source. Mermaid labels are escaped server-side and rendered in strict mode client-side; the hostile-label test covers quote breaking, markup and click directives. Snapshot scoping and the 404 on a foreign repository are shared with the MVP 1 report.

## 8. Not implemented or not verified

- **Diagrams in a real browser**: not checked. The Mermaid source of the fixtures and of petclinic parses with the real parser, strict mode is asserted, and the React component is tested with Mermaid mocked (jsdom cannot lay out SVG), but nobody looked at the rendered SVG in a browser. Recommended before relying on the visuals.
- **Real-repository smoke test**: only spring-petclinic (single module, no service layer, no REST DTOs beyond one). A multi-module Spring Boot repository and one with Lombok-heavy code were not tried; both are covered only by fixtures.
- **Performance on large repositories**: bulk insert was measured (3,300 symbols / 12,000 edges in about 3 s on embedded PostgreSQL) and caps exist, but no repository with tens of thousands of types was analysed. The report loads all symbols of a run into memory.
- **Gradle modules, functional (router) endpoints, request mappings on interfaces, `@Bean` wiring, XML configuration**: not implemented (stated as limitations).
- **Request/response field shapes** (DTO fields per endpoint) and **layer-violation rules** (e.g. controller calling a repository directly): not part of the plan; not implemented.
- **AI explanations of the architecture** (the plan's "AI optional"): out of scope until MVP 3.
- Items still open from MVP 1 (GitHub-token smoke test, Semgrep CLI integration test) are unchanged.

## 9. Known limitations

See the README section "Known limitations" (architecture bullet): no classpath, calls only with a visible receiver type, no reflection/proxies/conditional beans/`@Bean`/XML, no interface-declared or functional endpoints, Maven only, test sources excluded by default; package-only layers and non-repository pattern candidates are never DETECTED.

## 10. Recommended next steps

Look at the architecture page in a browser with petclinic and the fixtures, then try one multi-module and one Lombok-based Spring Boot repository with a `GITHUB_TOKEN`. If performance matters for large repositories, page the layer lists and load symbols by type only. Consider layer-violation findings (controller → repository without a service) as a cheap, evidence-based addition. Then start MVP 3, whose AI explanations can use this report as their only input.
