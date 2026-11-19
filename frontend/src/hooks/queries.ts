import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import {
  cancelAnalysisRun,
  getAnalysisRun,
  getReport,
  getRepository,
  listRepositories,
  listSnapshots,
} from '../api/repositories'
import { isTerminal } from '../types/api'

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
