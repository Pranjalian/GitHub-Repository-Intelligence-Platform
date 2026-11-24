import type { ReactNode } from 'react'
import { Link, useParams, useSearchParams } from 'react-router'
import { useRepository, useSnapshots } from '../../hooks/queries'
import { chooseSnapshot } from '../../lib/snapshots'
import { isCompleted } from '../../types/api'
import SnapshotSelector from '../report/SnapshotSelector'
import EmptyState from '../ui/EmptyState'
import ErrorState from '../ui/ErrorState'

/**
 * Shared frame of the per-snapshot pages (Search, Q&A): repository and commit header, snapshot selector,
 * links to the other views, and the page content once the snapshot has a completed analysis.
 */
export default function SnapshotPageFrame({
  title,
  children,
}: {
  title: string
  children: (props: { repositoryId: string; snapshotId: string; commitSha: string }) => ReactNode
}) {
  const { repositoryId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const repository = useRepository(repositoryId)
  const snapshots = useSnapshots(repositoryId)

  if (repository.isPending || snapshots.isPending) {
    return <p role="status">Loading repository…</p>
  }
  if (repository.error || snapshots.error) {
    const error = repository.error ?? snapshots.error
    return <ErrorState title="Could not load the repository" message={error?.message ?? 'Unknown error'} />
  }
  const selected = chooseSnapshot(snapshots.data.items, searchParams.get('snapshot'))
  if (!selected) {
    return <EmptyState title="Nothing analyzed yet" description="This repository has no snapshots." />
  }
  const completed = isCompleted(selected.latestRun?.status)
  const query = `?snapshot=${selected.id}`

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div className="min-w-0">
          <h2 className="text-xl font-semibold">
            {title} {repository.data.owner}/{repository.data.name}
          </h2>
          <p className="break-all font-mono text-xs text-slate-500">{selected.commitSha}</p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <SnapshotSelector
            snapshots={snapshots.data.items}
            selectedId={selected.id}
            onSelect={(snapshotId) => setSearchParams({ snapshot: snapshotId })}
          />
          <nav aria-label="Snapshot views" className="flex gap-3 text-sm">
            <Link to={`/repositories/${repositoryId}${query}`} className="text-blue-700 hover:underline">
              Report
            </Link>
            <Link to={`/repositories/${repositoryId}/search${query}`} className="text-blue-700 hover:underline">
              Search
            </Link>
            <Link to={`/repositories/${repositoryId}/qa${query}`} className="text-blue-700 hover:underline">
              Q&amp;A
            </Link>
            <Link to={`/repositories/${repositoryId}/impact${query}`} className="text-blue-700 hover:underline">
              Impact
            </Link>
          </nav>
        </div>
      </header>
      {completed ? (
        children({ repositoryId, snapshotId: selected.id, commitSha: selected.commitSha })
      ) : (
        <EmptyState title="Not analyzed" description="This commit has no completed analysis yet." />
      )}
    </div>
  )
}
