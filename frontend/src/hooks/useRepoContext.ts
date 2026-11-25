import { useMatch, useSearchParams } from 'react-router'
import { chooseSnapshot } from '../lib/snapshots'
import { isCompleted } from '../types/api'
import { useCoverage, useReport, useRepository, useSnapshots } from './queries'

/**
 * The repository (and commit) the current URL belongs to, for the shell: sidebar context card, nav
 * badges and the header breadcrumb. Reads the same cached queries the pages use, so it adds no requests.
 * `repositoryId` is undefined on global pages.
 */
export function useRepoContext() {
  const match = useMatch({ path: '/repositories/:repositoryId', end: false })
  const raw = match?.params.repositoryId
  const repositoryId = raw && raw !== 'new' ? raw : undefined
  const [searchParams] = useSearchParams()
  const repository = useRepository(repositoryId ?? '')
  const snapshots = useSnapshots(repositoryId ?? '')
  // enabled flags below keep these inert on global pages
  const selected = snapshots.data ? chooseSnapshot(snapshots.data.items, searchParams.get('snapshot')) : undefined
  const completed = isCompleted(selected?.latestRun?.status)
  const report = useReport(repositoryId ?? '', selected?.id, !!repositoryId && completed)
  const coverage = useCoverage(repositoryId ?? '', selected?.id ?? '')
  return {
    repositoryId,
    repository: repositoryId ? repository.data : undefined,
    snapshots: repositoryId ? snapshots.data?.items : undefined,
    selected: repositoryId ? selected : undefined,
    completed,
    report: repositoryId ? report.data : undefined,
    coverage: repositoryId && completed && report.data ? coverage.data : undefined,
  }
}
