import { Link, useParams, useSearchParams } from 'react-router'
import ArchitectureSection from '../components/architecture/ArchitectureSection'
import SnapshotSelector from '../components/report/SnapshotSelector'
import EmptyState from '../components/ui/EmptyState'
import ErrorState from '../components/ui/ErrorState'
import { SkeletonRows } from '../components/ui/Layout'
import { useRepository, useSnapshots } from '../hooks/queries'
import { chooseSnapshot } from '../lib/snapshots'
import { isCompleted } from '../types/api'

/** `/repositories/:repositoryId/arch?snapshot=`: the architecture of one analysed commit. */
export default function ArchitecturePage() {
  const { repositoryId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const repository = useRepository(repositoryId)
  const snapshots = useSnapshots(repositoryId)

  if (repository.isPending || snapshots.isPending) {
    return <SkeletonRows rows={3} label="Loading repository" />
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

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-center justify-between gap-3">
        <div>
          <h1 className="text-headline-lg font-semibold tracking-tight">Application Architecture</h1>
          <p className="text-body-sm text-muted">
            {repository.data.owner}/{repository.data.name} <span className="font-mono">@ {selected.commitSha.slice(0, 7)}</span>
          </p>
        </div>
        <div className="flex items-center gap-3">
          <SnapshotSelector
            snapshots={snapshots.data.items}
            selectedId={selected.id}
            onSelect={(snapshotId) => setSearchParams({ snapshot: snapshotId })}
          />
          <Link to={`/repositories/${repositoryId}?snapshot=${selected.id}`} className="text-body-md text-primary-text hover:underline">
            Full report
          </Link>
        </div>
      </header>
      {completed ? (
        <ArchitectureSection repositoryId={repositoryId} snapshotId={selected.id} />
      ) : (
        <EmptyState title="Not analyzed" description="This commit has no completed analysis yet." />
      )}
    </div>
  )
}
