import { useMutation, useQueryClient } from '@tanstack/react-query'
import { useNavigate } from 'react-router'
import { analyzeRepository } from '../api/repositories'

/** Submits a URL; on 202 (new run) or 200 (reused run) goes to the repository page carrying the run id. */
export function useAnalyzeRepository() {
  const navigate = useNavigate()
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: ({ url, forceRescan }: { url: string; forceRescan: boolean }) => analyzeRepository(url, forceRescan),
    onSuccess: async (response) => {
      await queryClient.invalidateQueries({ queryKey: ['repositories'] })
      navigate(
        `/repositories/${response.repositoryId}?snapshot=${response.snapshotId}&run=${response.analysisRunId}`,
      )
    },
  })
}
