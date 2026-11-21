import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
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
import { isTerminal, type FindingFilters } from '../types/api'

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
