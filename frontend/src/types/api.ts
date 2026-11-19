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

export type AnalysisCategory = 'TECHNOLOGY' | 'STRUCTURE' | 'DEPENDENCIES' | 'CODE_QUALITY' | 'SECURITY' | 'TESTS'
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
  sections: unknown[]
  limitations: Limitation[]
}
