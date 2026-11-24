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
  /** Id of the type's code symbol (MVP 3): for the symbol endpoint and AI insights. */
  symbolId?: string | null
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

// ── code symbols and AI insights (MVP 3) ─────────────────────────────────────────────────────

export type SymbolType = 'CLASS' | 'INTERFACE' | 'ENUM' | 'RECORD' | 'ANNOTATION' | 'METHOD' | 'CONSTRUCTOR' | 'FIELD'

export interface CodeSymbol {
  id: string
  type: SymbolType
  name: string
  fullyQualifiedName: string
  signature: string | null
  filePath: string
  startLine: number | null
  endLine: number | null
  sourceUrl: string | null
  annotations: string[]
  members: CodeSymbol[]
}

export type InsightType =
  | 'EXPLAIN_CLASS'
  | 'EXPLAIN_METHOD'
  | 'EXPLAIN_FINDING'
  | 'SUGGEST_REFACTORING'
  | 'SUMMARIZE_MODULE'
  | 'TECH_DEBT_SUMMARY'

export type AIStatus = 'OK' | 'UNAVAILABLE' | 'SKIPPED' | 'RATE_LIMITED' | 'TIMEOUT' | 'INVALID_OUTPUT' | 'ERROR'

export interface InsightRequest {
  type: InsightType
  targetId?: string | null
  provider?: string | null
}

export interface InsightEvidence {
  id: string
  kind: string
  filePath: string | null
  startLine: number | null
  endLine: number | null
  sourceUrl: string | null
  truncated: boolean
  text: string
}

export interface Insight {
  type: InsightType
  targetId: string | null
  targetLabel: string | null
  status: AIStatus
  aiGenerated: boolean
  explanation: string | null
  structured: Record<string, unknown> | null
  provider: string | null
  model: string | null
  promptVersion: string | null
  fromCache: boolean
  fallback: boolean
  reason: string | null
  evidence: InsightEvidence[]
  withheldLines: number
  warnings: string[]
  limitations: string[]
  commitSha: string | null
  generatedAt: string | null
  setupGuideUrl: string | null
}

export type DataHandling = 'LOCAL' | 'HOSTED'
export type ProviderHealthStatus = 'UP' | 'DOWN' | 'NOT_CONFIGURED' | 'RATE_LIMITED' | 'UNKNOWN'

export interface AIProvider {
  id: string
  enabled: boolean
  active: boolean
  dataHandling: DataHandling | null
  dataHandlingAcknowledged: boolean
  permitted: boolean
  chatModel: string | null
  apiKeyConfigured: boolean
  health: { status: ProviderHealthStatus; detail: string | null; checkedAt: string | null; unavailableUntil: string | null }
  capabilities: {
    supportsChat: boolean
    supportsStructuredOutput: boolean
    supportsEmbeddings: boolean
    supportsStreaming: boolean
    maxContextTokens: number | null
    modelName: string | null
    source: string
  } | null
  pricingConfigured: boolean
  currency: string | null
}

export interface AIProviders {
  enabled: boolean
  available: boolean
  unavailableReason: string | null
  defaultProvider: string | null
  fallbackProviders: string[]
  setupGuideUrl: string | null
  limits: {
    maxInputTokens: number
    maxOutputTokens: number
    maxCallsPerAnalysis: number
    timeoutSeconds: number
    maxRetries: number
    charsPerToken: number
  }
  cache: { enabled: boolean; ttl: string }
  features: { feature: string; provider: string | null; model: string | null }[]
  providers: AIProvider[]
}

export type UsageSource = 'REPORTED' | 'ESTIMATED' | 'UNKNOWN'

export interface AIUsageRecord {
  id: string
  createdAt: string
  provider: string
  model: string | null
  feature: string
  promptVersion: string
  repositoryId: string | null
  snapshotId: string | null
  runId: string | null
  status: AIStatus
  cacheHit: boolean
  inputTokens: number | null
  outputTokens: number | null
  estimatedInputTokens: number | null
  estimatedOutputTokens: number | null
  usageSource: UsageSource
  estimatedCost: number | null
  costSource: 'REPORTED_TOKENS' | 'ESTIMATED_TOKENS' | null
  currency: string | null
  latencyMs: number | null
  detail: string | null
}

// ── MVP 4: search index, deterministic search and repository Q&A ─────────────────────────────

export type IndexState = 'NOT_INDEXED' | 'QUEUED' | 'INDEXING' | 'COMPLETED' | 'COMPLETED_WITH_WARNINGS' | 'FAILED'

export type EmbeddingState = 'PENDING' | 'NOT_CONFIGURED' | 'UNAVAILABLE' | 'COMPLETED' | 'PARTIAL' | 'FAILED'

export interface IndexStatus {
  snapshotId: string
  status: IndexState
  embeddingStatus: EmbeddingState | null
  embeddingDetail: string | null
  embeddingProvider: string | null
  embeddingModel: string | null
  embeddingModelVersion: string | null
  embeddingDimensions: number | null
  filesIndexed: number | null
  filesSkipped: number | null
  chunkCount: number | null
  embeddedChunks: number | null
  reusedEmbeddings: number | null
  newEmbeddings: number | null
  detail: string | null
  warnings: string[]
  requestedAt: string | null
  startedAt: string | null
  completedAt: string | null
  vectorStore: string | null
}

export const isIndexInFlight = (status: IndexState | undefined | null) => status === 'QUEUED' || status === 'INDEXING'

export const isIndexSearchable = (status: IndexState | undefined | null) =>
  status === 'COMPLETED' || status === 'COMPLETED_WITH_WARNINGS'

export type SearchType = 'text' | 'symbol'

export interface SearchParams {
  q: string
  type: SearchType
  language?: string
  module?: string
  fileType?: string
}

export interface SearchResult {
  kind: 'SYMBOL' | 'CHUNK'
  chunkId: string | null
  symbolId: string | null
  symbolType: string | null
  name: string | null
  qualifiedName: string | null
  signature: string | null
  filePath: string
  startLine: number | null
  endLine: number | null
  language: string | null
  module: string | null
  chunkType: string | null
  section: string | null
  score: number | null
  snippet: string | null
  sourceUrl: string | null
  redacted: boolean
}

export interface SearchResponse {
  query: string
  type: 'TEXT' | 'SYMBOL'
  status: 'OK' | 'NOT_INDEXED' | 'NOT_SUPPORTED'
  reason: string | null
  commitSha: string
  source: 'SYMBOL_GRAPH' | 'CHUNK_NAMES' | 'KEYWORD_INDEX' | null
  results: SearchResult[]
  terms: string[]
  index: IndexStatus
}

export type AnswerStatus = 'OK' | 'INSUFFICIENT_EVIDENCE' | 'NOT_INDEXED' | AIStatus

export interface AnswerSource {
  ref: string | null
  chunkId: string
  filePath: string
  startLine: number
  endLine: number
  chunkType: string
  label: string | null
  snippet: string | null
  sourceUrl: string | null
  score: number | null
}

export interface QuestionAnswer {
  conversationId: string | null
  messageId: string | null
  question: string
  status: AnswerStatus
  aiGenerated: boolean
  answer: string | null
  insufficientEvidence: boolean
  clarifyingQuestion: string | null
  missingEvidence: string[]
  sources: AnswerSource[]
  retrieved: AnswerSource[]
  searched: string | null
  searchResults: SearchResult[]
  provider: string | null
  model: string | null
  promptVersion: string | null
  fromCache: boolean
  fallback: boolean
  reason: string | null
  warnings: string[]
  limitations: string[]
  commitSha: string | null
  generatedAt: string | null
  index: IndexStatus | null
}

export interface QuestionRequest {
  question: string
  conversationId?: string | null
  provider?: string | null
}

// ── MVP 5: impact analysis ─────────────────────────────────────────────────────────────────────

export interface PickerSymbol {
  id: string
  kind: string
  name: string
  qualifiedName: string
  filePath: string
  line: number | null
  layer: string | null
}

export interface SymbolPickerResponse {
  status: 'OK' | 'NOT_SUPPORTED'
  reason: string | null
  query: string
  symbols: PickerSymbol[]
}

export interface ImpactTarget {
  id: string
  kind: 'TYPE' | 'METHOD'
  name: string
  qualifiedName: string
  layer: string | null
  filePath: string | null
  startLine: number | null
  endLine: number | null
  sourceUrl: string | null
  modulePath: string | null
}

export interface ImpactComponent {
  id: string
  kind: 'TYPE' | 'METHOD'
  name: string
  qualifiedName: string
  layer: string | null
  depth: number
  viaId: string | null
  relationships: string[]
  confidence: Confidence
  reason: string
  filePath: string | null
  line: number | null
  sourceUrl: string | null
  modulePath: string | null
}

export interface ImpactEndpoint {
  httpMethod: string
  route: string
  routeResolved: boolean
  controller: string | null
  handler: string | null
  confidence: Confidence
  filePath: string | null
  line: number | null
  sourceUrl: string | null
  viaId: string | null
}

export interface ImpactRelatedData {
  kind: 'ENTITY' | 'REPOSITORY'
  id: string
  qualifiedName: string
  relationship: string
  via: string | null
  confidence: Confidence
  filePath: string | null
  line: number | null
  sourceUrl: string | null
}

export interface ImpactModule {
  modulePath: string
  reason: 'CONTAINS_AFFECTED' | 'DEPENDS_ON_TARGET_MODULE'
  depth: number
  affectedSymbols: number
  evidencePaths: string[]
  confidence: Confidence
}

export interface ImpactTest {
  testSymbol: string
  filePath: string
  methods: string[]
  relation: 'DIRECT_CALL' | 'NAMING_CONVENTION' | 'TYPE_REFERENCE' | 'AFFECTED_COMPONENT'
  confidence: Confidence
  line: number | null
  sourceUrl: string | null
  via: string | null
}

export interface RegressionArea {
  kind: 'ENDPOINT' | 'TEST' | 'COMPONENT' | 'MODULE' | 'GAP'
  area: string
  reason: string
  confidence: Confidence
}

export interface RiskFactor {
  id: string
  label: string
  value: string
  points: number
  maxPoints: number
  explanation: string
}

export interface ImpactRisk {
  label: string
  score: number
  maxScore: number
  level: 'LOW' | 'MEDIUM' | 'HIGH'
  factors: RiskFactor[]
  note: string
}

export interface ImpactAiSummary {
  status: AIStatus
  aiGenerated: boolean
  summary: string | null
  keyPoints: string[]
  regressionFocus: string[]
  provider: string | null
  model: string | null
  promptVersion: string | null
  fromCache: boolean
  detail: string | null
  warnings: string[]
  generatedAt: string | null
}

export interface ImpactAnalysis {
  repositoryId: string
  snapshotId: string
  runId: string
  commitSha: string
  status: 'OK' | 'NOT_SUPPORTED'
  reason: string | null
  target: ImpactTarget
  depth: number
  components: ImpactComponent[]
  endpoints: ImpactEndpoint[]
  relatedData: ImpactRelatedData[]
  modules: ImpactModule[]
  tests: ImpactTest[]
  regressionAreas: RegressionArea[]
  risk: ImpactRisk | null
  limitations: string[]
  truncated: boolean
  totalDependents: number
  diagram: { chain: string | null; note: string | null }
  aiSummary: ImpactAiSummary | null
}

export interface ImpactRequest {
  targetSymbolId: string
  changeDescription?: string | null
  depth?: number
  includeAiSummary?: boolean
  provider?: string | null
}

export interface TargetSuggestion {
  symbolId: string
  kind: string
  name: string
  qualifiedName: string
  filePath: string
  line: number | null
  reason: string | null
}

export interface TargetSuggestions {
  status: AIStatus
  aiGenerated: boolean
  detail: string | null
  provider: string | null
  model: string | null
  promptVersion: string | null
  suggestions: TargetSuggestion[]
  warnings: string[]
}
