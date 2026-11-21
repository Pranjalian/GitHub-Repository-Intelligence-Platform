import type { ExecutionStatus } from './status'

/** Mirrors the backend RunStatus enum. */
export type RunStatus =
  | 'QUEUED'
  | 'INGESTING'
  | 'ANALYZING'
  | 'COMPLETED'
  | 'COMPLETED_WITH_WARNINGS'
  | 'FAILED'
  | 'CANCELLED'

export const TERMINAL_RUN_STATUSES: readonly RunStatus[] = ['COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'CANCELLED']
export const COMPLETED_RUN_STATUSES: readonly RunStatus[] = ['COMPLETED', 'COMPLETED_WITH_WARNINGS']

export const isTerminal = (status: RunStatus | undefined): boolean => !!status && TERMINAL_RUN_STATUSES.includes(status)
export const isCompleted = (status: RunStatus | undefined): boolean => !!status && COMPLETED_RUN_STATUSES.includes(status)

export type AnalysisCategory =
  | 'TECHNOLOGY'
  | 'STRUCTURE'
  | 'DEPENDENCIES'
  | 'CODE_QUALITY'
  | 'SECURITY'
  | 'TESTS'
  | 'ARCHITECTURE'
export type Confidence = 'DETECTED' | 'LIKELY' | 'POSSIBLE' | 'NOT_VERIFIED'
export type CapabilityLevel = 'GENERIC' | 'BASIC' | 'DEEP' | 'UNSUPPORTED' | 'UNAVAILABLE'
export type Severity = 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO'
export type TechnologyKind =
  | 'LANGUAGE'
  | 'FRAMEWORK'
  | 'BUILD_TOOL'
  | 'PACKAGE_MANAGER'
  | 'DATABASE'
  | 'TEST_FRAMEWORK'
  | 'DEPLOYMENT'

export interface PageResponse<T> {
  items: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface AnalyzeRepositoryResponse {
  repositoryId: string
  snapshotId: string
  commitSha: string
  branch: string | null
  analysisRunId: string
  status: RunStatus
  reused: boolean
  warnings: string[]
}

export interface RunSummary {
  id: string
  status: RunStatus
  forceRescan: boolean
  requestedAt: string
  startedAt: string | null
  completedAt: string | null
  failureReason: string | null
}

export interface Snapshot {
  id: string
  commitSha: string
  branch: string | null
  ingestionStatus: string
  fileCount: number | null
  totalBytes: number | null
  ingestedAt: string | null
  createdAt: string
  latestRun: RunSummary | null
}

export interface Repository {
  id: string
  host: string
  owner: string
  name: string
  canonicalUrl: string
  defaultBranch: string | null
  visibility: string | null
  createdAt: string
  updatedAt: string
  latestSnapshot: Snapshot | null
}

export interface Execution {
  analyzerId: string
  category: AnalysisCategory
  status: ExecutionStatus
  startedAt: string | null
  completedAt: string | null
  durationMs: number | null
  errorSummary: string | null
}

export type RunPhase = 'QUEUED' | 'INGESTING' | 'DETECTING' | 'ANALYZING' | 'FINISHED'

export interface AnalysisRun {
  runId: string
  repositoryId: string
  snapshotId: string
  commitSha: string
  status: RunStatus
  failureReason: string | null
  retryAfter: string | null
  requestedAt: string
  startedAt: string | null
  completedAt: string | null
  phase: RunPhase
  executions: Execution[]
}

export interface Evidence {
  path: string
  line: number | null
  signal: string | null
}

export interface Technology {
  name: string
  kind: TechnologyKind
  version: string | null
  confidence: Confidence
  sharePercent: number | null
  detectorId: string | null
  evidence: Evidence[] | null
}

export interface ReportModule {
  name: string
  path: string
  buildFilePath: string | null
  ecosystem: string | null
  responsibility: string | null
  evidence: Evidence[] | null
}

export interface Structure {
  modules: ReportModule[]
  entryPoints: string[]
  summary: Record<string, unknown> | null
}

export interface CapabilityMatrixRow {
  technology: string
  category: AnalysisCategory
  analyzerId: string | null
  analyzerVersion: string | null
  capabilityLevel: CapabilityLevel
  executionStatus: ExecutionStatus
  limitationReason: string | null
}

export interface Limitation {
  analyzerId: string | null
  category: AnalysisCategory
  status: ExecutionStatus
  reason: string
}

export interface ConsolidatedReport {
  repository: { id: string; owner: string; name: string; url: string }
  snapshot: { id: string; commitSha: string; branch: string | null; analyzedAt: string | null }
  run: { id: string; status: RunStatus; completedAt: string | null }
  technologies: Technology[]
  structure: Structure
  capabilityMatrix: CapabilityMatrixRow[]
  sections: ReportSection[]
  limitations: Limitation[]
}

export interface AnalyzerSummary {
  id: string
  version: string
  status: ExecutionStatus
  capabilityLevel: CapabilityLevel | null
  coverage: Record<string, unknown> | null
}

/** One section per category; `findingCountsBySeverity` is null (never zeros) when the category was not analyzed. */
export interface ReportSection {
  category: AnalysisCategory
  status: ExecutionStatus
  analyzers: AnalyzerSummary[]
  findingCountsBySeverity: Record<Severity, number> | null
  summary: string
}

export type AnalysisMethod = 'DETERMINISTIC_SCANNER' | 'PARSER' | 'HEURISTIC' | 'GRAPH_INFERENCE' | 'AI_INTERPRETATION'

export interface Finding {
  id: string
  severity: Severity
  confidence: Confidence
  category: AnalysisCategory
  title: string
  description: string | null
  filePath: string | null
  startLine: number | null
  endLine: number | null
  module: string | null
  ruleId: string
  analyzerId: string
  analysisMethod: AnalysisMethod
  evidence: Record<string, unknown> | null
  sourceUrl: string | null
  suggestedNextStep: string | null
  fingerprint: string
}

export type VulnerabilityStatus = 'NOT_CHECKED' | 'NOT_CHECKABLE' | 'NO_KNOWN_VULNERABILITIES' | 'VULNERABLE'
export type VersionSource = 'DECLARED' | 'PROPERTY' | 'MANAGED_LOCAL' | 'MANAGED_REMOTE_UNRESOLVED' | 'UNRESOLVED'

export interface Dependency {
  id: string
  analyzerId: string
  ecosystem: 'MAVEN' | 'NPM'
  manifestPath: string
  module: string | null
  namespace: string | null
  name: string
  declaredVersion: string | null
  resolvedVersion: string | null
  versionSource: VersionSource
  scope: string | null
  direct: boolean
  managementEntry: boolean
  vulnerabilityStatus: VulnerabilityStatus
  vulnerabilityIds: string[] | null
  note: string | null
}

export interface TestRecord {
  testFilePath: string
  testSymbol: string | null
  testMethodCount: number | null
  framework: string | null
  productionSymbol: string | null
  mappingMethod: string | null
  confidence: Confidence | null
}

export interface TestsReport {
  testClasses: number | null
  testMethods: number | null
  methodsByFramework: Record<string, number> | null
  records: TestRecord[]
  untestedClasses: string[]
}

export interface CoverageRecord {
  reportPath: string
  scope: 'CLASS' | 'PACKAGE' | 'BUNDLE'
  name: string
  lineCovered: number | null
  lineMissed: number | null
  branchCovered: number | null
  branchMissed: number | null
}

export interface CoverageReport {
  status: ExecutionStatus
  message: string
  records: CoverageRecord[]
}

export interface FindingFilters {
  category?: AnalysisCategory
  severity?: Severity | ''
  module?: string
  file?: string
  sort?: 'severity' | 'file' | 'category' | 'rule'
  page?: number
  size?: number
}

// ── architecture (MVP 2) ─────────────────────────────────────────────────────────────────────

export type ArchitectureLayer = 'CONTROLLER' | 'SERVICE' | 'REPOSITORY' | 'ENTITY' | 'DTO' | 'CONFIGURATION' | 'INFRASTRUCTURE'
export type LayerMethod = 'ANNOTATION' | 'TYPE_HIERARCHY' | 'API_SIGNATURE' | 'PACKAGE_HEURISTIC'
export type EdgeResolution = 'RESOLVED' | 'UNRESOLVED' | 'AMBIGUOUS'
export type EntityRelationshipType = 'ONE_TO_MANY' | 'MANY_TO_ONE' | 'ONE_TO_ONE' | 'MANY_TO_MANY' | 'REPOSITORY_FOR'
export type PatternType = 'STRATEGY' | 'FACTORY' | 'ADAPTER' | 'REPOSITORY'

export interface LayerClass {
  name: string
  fullyQualifiedName: string
  filePath: string
  line: number | null
  confidence: Confidence
  method: LayerMethod
  evidence: Evidence[]
  sourceUrl: string | null
}

export interface ArchitectureLayerGroup {
  layer: ArchitectureLayer
  total: number
  classes: LayerClass[]
}

export interface ArchitectureEndpoint {
  httpMethod: string
  route: string
  routeResolved: boolean
  controller: string | null
  handler: string | null
  requestType: string | null
  responseType: string | null
  filePath: string
  line: number | null
  sourceUrl: string | null
}

export interface EntityRelation {
  field: string | null
  type: EntityRelationshipType
  target: string | null
  targetResolved: boolean
  attributes: Record<string, string>
}

export interface ArchitectureEntity {
  name: string
  fullyQualifiedName: string
  table: string | null
  filePath: string
  line: number | null
  sourceUrl: string | null
  relationships: EntityRelation[]
  repositories: string[]
}

export interface Injection {
  from: string | null
  to: string | null
  resolution: EdgeResolution
  confidence: Confidence | null
  via: string | null
  member: string | null
  filePath: string | null
  line: number | null
  sourceUrl: string | null
}

export interface ChainStep {
  name: string
  fullyQualifiedName: string
  layer: ArchitectureLayer | null
}

export interface ModuleDependency {
  source: string
  target: string
  evidence: Evidence[]
}

export interface ArchitectureCycle {
  rule: string
  severity: Severity
  confidence: Confidence
  title: string
  description: string | null
  members: string[]
  edges: Record<string, unknown>[]
  filePath: string | null
  line: number | null
  sourceUrl: string | null
}

export interface PatternCandidate {
  pattern: PatternType
  confidence: Confidence
  summary: string | null
  symbols: string[]
  evidence: Record<string, unknown>
}

export interface ArchitectureDiagrams {
  layers: string | null
  modules: string | null
  apiFlow: string | null
  entities: string | null
  notes: string[]
}

/** Architecture of one snapshot; `supported` is false (and the lists are empty) when no analyzer applies. */
export interface ArchitectureReport {
  repositoryId: string
  snapshotId: string
  runId: string
  commitSha: string
  supported: boolean
  status: ExecutionStatus
  analyzers: { id: string; version: string; status: ExecutionStatus; capabilityLevel: CapabilityLevel | null }[]
  unsupported: string[]
  summary: Record<string, number>
  layers: ArchitectureLayerGroup[]
  endpoints: ArchitectureEndpoint[]
  entities: ArchitectureEntity[]
  injections: Injection[]
  diChains: ChainStep[][]
  moduleDependencies: ModuleDependency[]
  cycles: ArchitectureCycle[]
  patterns: PatternCandidate[]
  diagrams: ArchitectureDiagrams
  limitations: string[]
}
