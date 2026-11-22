import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  askQuestion,
  getIndexStatus,
  requestIndex,
  searchSnapshot,
  getAIProviders,
  getAIUsage,
  getSymbol,
  requestInsight,
  cancelAnalysisRun,
  getArchitecture,
  getCoverage,
  getDependencies,
  getFindings,
  getTests,
  getAnalysisRun,
  getReport,
  getRepository,
  listRepositories,
  listSnapshots,
} from '../api/repositories'
import {
  isIndexInFlight,
  isTerminal,
  type FindingFilters,
  type InsightRequest,
  type QuestionRequest,
  type SearchParams,
} from '../types/api'

export const useRepositories = (page: number, size = 20) =>
  useQuery({
    queryKey: ['repositories', page, size],
    queryFn: () => listRepositories(page, size),
    placeholderData: keepPreviousData,
  })

export const useRepository = (repositoryId: string) =>
  useQuery({ queryKey: ['repository', repositoryId], queryFn: () => getRepository(repositoryId) })

export const useSnapshots = (repositoryId: string) =>
  useQuery({ queryKey: ['snapshots', repositoryId], queryFn: () => listSnapshots(repositoryId) })

/** Polls a run every {@code pollIntervalMs} while it is not terminal, then stops. */
export const useAnalysisRun = (runId: string | undefined, pollIntervalMs = 3000) =>
  useQuery({
    queryKey: ['analysis-run', runId],
    queryFn: () => getAnalysisRun(runId as string),
    enabled: !!runId,
    refetchInterval: (query) => (isTerminal(query.state.data?.status) ? false : pollIntervalMs),
  })

export const useReport = (repositoryId: string, snapshotId: string | undefined, enabled: boolean) =>
  useQuery({
    queryKey: ['report', repositoryId, snapshotId],
    queryFn: () => getReport(repositoryId, snapshotId as string),
    enabled: enabled && !!snapshotId,
  })

export function useCancelRun(runId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => cancelAnalysisRun(runId),
    onSuccess: (run) => {
      queryClient.setQueryData(['analysis-run', runId], run)
    },
  })
}

export const useFindings = (repositoryId: string, snapshotId: string, filters: FindingFilters) =>
  useQuery({
    queryKey: ['findings', repositoryId, snapshotId, filters],
    queryFn: () => getFindings(repositoryId, snapshotId, filters),
    placeholderData: keepPreviousData,
  })

export const useDependencies = (
  repositoryId: string,
  snapshotId: string,
  filters: { ecosystem?: string; module?: string; scope?: string; vulnerable?: boolean; page?: number; size?: number },
) =>
  useQuery({
    queryKey: ['dependencies', repositoryId, snapshotId, filters],
    queryFn: () => getDependencies(repositoryId, snapshotId, filters),
    placeholderData: keepPreviousData,
  })

export const useTests = (repositoryId: string, snapshotId: string) =>
  useQuery({ queryKey: ['tests', repositoryId, snapshotId], queryFn: () => getTests(repositoryId, snapshotId) })

export const useCoverage = (repositoryId: string, snapshotId: string) =>
  useQuery({ queryKey: ['coverage', repositoryId, snapshotId], queryFn: () => getCoverage(repositoryId, snapshotId) })

export const useArchitecture = (repositoryId: string, snapshotId: string | undefined) =>
  useQuery({
    queryKey: ['architecture', repositoryId, snapshotId],
    queryFn: () => getArchitecture(repositoryId, snapshotId as string),
    enabled: !!snapshotId,
  })

// ── MVP 3 ────────────────────────────────────────────────────────────────────────────────────

/** AI configuration; cheap to refetch, shared by every insight button. */
export const useAIProviders = () =>
  useQuery({ queryKey: ['ai-providers'], queryFn: getAIProviders, staleTime: 30_000 })

export const useAIUsage = (filters: { provider?: string; feature?: string; page?: number; size?: number }) =>
  useQuery({ queryKey: ['ai-usage', filters], queryFn: () => getAIUsage(filters), placeholderData: keepPreviousData })

export const useSymbol = (repositoryId: string, snapshotId: string, symbolId: string | null) =>
  useQuery({
    queryKey: ['symbol', repositoryId, snapshotId, symbolId],
    queryFn: () => getSymbol(repositoryId, snapshotId, symbolId as string),
    enabled: !!symbolId,
  })

/** One insight request; the result stays in the mutation until the panel is closed. */
export function useInsight(repositoryId: string, snapshotId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: InsightRequest) => requestInsight(repositoryId, snapshotId, request),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['ai-usage'] }),
  })
}

// ── MVP 4 ────────────────────────────────────────────────────────────────────────────────────

/** Index state of a snapshot; polled while a job is queued or running. */
export const useIndexStatus = (repositoryId: string, snapshotId: string | undefined, pollIntervalMs = 2000) =>
  useQuery({
    queryKey: ['index', repositoryId, snapshotId],
    queryFn: () => getIndexStatus(repositoryId, snapshotId as string),
    enabled: !!snapshotId,
    refetchInterval: (query) => (isIndexInFlight(query.state.data?.status) ? pollIntervalMs : false),
  })

export function useRequestIndex(repositoryId: string, snapshotId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: () => requestIndex(repositoryId, snapshotId),
    onSuccess: (status) => {
      queryClient.setQueryData(['index', repositoryId, snapshotId], status)
    },
  })
}

/** Runs only for a submitted query (params null until then). */
export const useSearch = (repositoryId: string, snapshotId: string | undefined, params: SearchParams | null) =>
  useQuery({
    queryKey: ['search', repositoryId, snapshotId, params],
    queryFn: () => searchSnapshot(repositoryId, snapshotId as string, params as SearchParams),
    enabled: !!snapshotId && !!params,
  })

export function useAskQuestion(repositoryId: string, snapshotId: string) {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (request: QuestionRequest) => askQuestion(repositoryId, snapshotId, request),
    onSettled: () => queryClient.invalidateQueries({ queryKey: ['ai-usage'] }),
  })
}
