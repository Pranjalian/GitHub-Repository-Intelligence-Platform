import type { ReactNode } from 'react'
import { useParams, useSearchParams } from 'react-router'
import { useRepository, useSnapshots } from '../../hooks/queries'
import { chooseSnapshot } from '../../lib/snapshots'
import { isCompleted } from '../../types/api'
import SnapshotSelector from '../report/SnapshotSelector'
import EmptyState from '../ui/EmptyState'
import ErrorState from '../ui/ErrorState'
import { SkeletonRows } from '../ui/Layout'

/**
 * Shared frame of the per-snapshot pages (Search, Q&A, Impact): the page title, the repository and
 * commit it is about, the snapshot selector (which rewrites ?snapshot=), and the content once the
 * snapshot has a completed analysis. Navigation to the other views is the sidebar's job.
 */
export default function SnapshotPageFrame({
  title,
  subtitle,
  children,
  actions,
}: {
  title: string
  subtitle?: string
  actions?: ReactNode
  children: (props: { repositoryId: string; snapshotId: string; commitSha: string; repositoryName: string; repositoryUrl: string }) => ReactNode
}) {
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
  const name = `${repository.data.owner}/${repository.data.name}`

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <h1 className="text-headline-lg font-semibold tracking-tight">{title}</h1>
          <p className="text-body-sm text-muted">
            {name} <span className="font-mono">@ {selected.commitSha.slice(0, 7)}</span>
            {subtitle && <span> · {subtitle}</span>}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {actions}
          <SnapshotSelector
            snapshots={snapshots.data.items}
            selectedId={selected.id}
            onSelect={(snapshotId) => {
              const next = new URLSearchParams(searchParams)
              next.set('snapshot', snapshotId)
              setSearchParams(next)
            }}
          />
        </div>
      </header>
      {completed ? (
        children({ repositoryId, snapshotId: selected.id, commitSha: selected.commitSha, repositoryName: name, repositoryUrl: repository.data.canonicalUrl })
      ) : (
        <EmptyState title="Not analyzed" description="This commit has no completed analysis yet." />
      )}
    </div>
  )
}
