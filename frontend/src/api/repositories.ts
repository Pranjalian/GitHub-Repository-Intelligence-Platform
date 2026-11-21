import type {
  AnalysisRun,
  ArchitectureReport,
  AnalyzeRepositoryResponse,
  ConsolidatedReport,
  CoverageReport,
  Dependency,
  Finding,
  FindingFilters,
  TestsReport,
  PageResponse,
  Repository,
  Snapshot,
} from '../types/api'
import { apiFetch } from './client'

export const analyzeRepository = (url: string, forceRescan: boolean) =>
  apiFetch<AnalyzeRepositoryResponse>('/api/v1/repositories/analyze', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ url, forceRescan }),
  })

export const listRepositories = (page = 0, size = 20) =>
  apiFetch<PageResponse<Repository>>(`/api/v1/repositories?page=${page}&size=${size}`)

export const getRepository = (repositoryId: string) => apiFetch<Repository>(`/api/v1/repositories/${repositoryId}`)

export const listSnapshots = (repositoryId: string, page = 0, size = 50) =>
  apiFetch<PageResponse<Snapshot>>(`/api/v1/repositories/${repositoryId}/snapshots?page=${page}&size=${size}`)

export const getAnalysisRun = (runId: string) => apiFetch<AnalysisRun>(`/api/v1/analysis-runs/${runId}`)

export const cancelAnalysisRun = (runId: string) =>
  apiFetch<AnalysisRun>(`/api/v1/analysis-runs/${runId}/cancel`, { method: 'POST' })

export const getReport = (repositoryId: string, snapshotId: string) =>
  apiFetch<ConsolidatedReport>(`/api/v1/repositories/${repositoryId}/reports/${snapshotId}`)

export const getArchitecture = (repositoryId: string, snapshotId: string) =>
  apiFetch<ArchitectureReport>(`/api/v1/repositories/${repositoryId}/snapshots/${snapshotId}/architecture`)

function query(params: Record<string, string | number | boolean | undefined | null>): string {
  const search = new URLSearchParams()
  for (const [key, value] of Object.entries(params)) {
    if (value !== undefined && value !== null && value !== '') {
      search.set(key, String(value))
    }
  }
  const text = search.toString()
  return text ? `?${text}` : ''
}

const snapshotBase = (repositoryId: string, snapshotId: string) =>
  `/api/v1/repositories/${repositoryId}/snapshots/${snapshotId}`

export const getFindings = (repositoryId: string, snapshotId: string, filters: FindingFilters) =>
  apiFetch<PageResponse<Finding>>(`${snapshotBase(repositoryId, snapshotId)}/findings${query({ ...filters })}`)

export const getDependencies = (
  repositoryId: string,
  snapshotId: string,
  filters: { ecosystem?: string; module?: string; scope?: string; vulnerable?: boolean; page?: number; size?: number },
) => apiFetch<PageResponse<Dependency>>(`${snapshotBase(repositoryId, snapshotId)}/dependencies${query({ ...filters })}`)

export const getTests = (repositoryId: string, snapshotId: string) =>
  apiFetch<TestsReport>(`${snapshotBase(repositoryId, snapshotId)}/tests`)

export const getCoverage = (repositoryId: string, snapshotId: string) =>
  apiFetch<CoverageReport>(`${snapshotBase(repositoryId, snapshotId)}/coverage`)
