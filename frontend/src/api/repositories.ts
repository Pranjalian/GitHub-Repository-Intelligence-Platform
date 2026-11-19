import type {
  AnalysisRun,
  AnalyzeRepositoryResponse,
  ConsolidatedReport,
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
