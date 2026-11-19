import { useParams, useSearchParams } from 'react-router'
import AnalysisProgress from '../components/analysis/AnalysisProgress'
import CapabilityMatrix from '../components/report/CapabilityMatrix'
import LimitationsPanel from '../components/report/LimitationsPanel'
import SnapshotSelector from '../components/report/SnapshotSelector'
import StructurePanel from '../components/report/StructurePanel'
import TechStack from '../components/report/TechStack'
import CopyButton from '../components/ui/CopyButton'
import EmptyState from '../components/ui/EmptyState'
import ErrorState from '../components/ui/ErrorState'
import RunStatusLabel from '../components/ui/RunStatusLabel'
import { useReport, useRepository, useSnapshots } from '../hooks/queries'
import { formatDateTime } from '../lib/format'
import { isCompleted, type Snapshot } from '../types/api'

/** The snapshot to show: the one in the URL, else the latest completed, else the newest. */
function chooseSnapshot(snapshots: Snapshot[], requestedId: string | null): Snapshot | undefined {
  return (
    snapshots.find((snapshot) => snapshot.id === requestedId) ??
    snapshots.find((snapshot) => isCompleted(snapshot.latestRun?.status)) ??
    snapshots[0]
  )
}

export default function RepositoryOverviewPage({ pollIntervalMs }: { pollIntervalMs?: number }) {
  const { repositoryId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const repository = useRepository(repositoryId)
  const snapshots = useSnapshots(repositoryId)

  const selected = snapshots.data ? chooseSnapshot(snapshots.data.items, searchParams.get('snapshot')) : undefined
  const latestRun = selected?.latestRun ?? null
  // The run in the URL (just submitted) takes precedence for the snapshot it belongs to.
  const runId = searchParams.get('run') && searchParams.get('snapshot') === selected?.id ? searchParams.get('run') : latestRun?.id
  const completed = isCompleted(latestRun?.status)
  const report = useReport(repositoryId, selected?.id, completed)

  if (repository.isPending || snapshots.isPending) {
    return <p role="status">Loading repository…</p>
  }
  if (repository.error || snapshots.error) {
    const error = repository.error ?? snapshots.error
    return <ErrorState title="Could not load the repository" message={error?.message ?? 'Unknown error'} />
  }

  const repo = repository.data
  const items = snapshots.data.items
  if (items.length === 0 || !selected) {
    return (
      <EmptyState
        title="Nothing analyzed yet"
        description="This repository has no snapshots. Submit it again to start an analysis."
      />
    )
  }

  function selectSnapshot(snapshotId: string) {
    setSearchParams({ snapshot: snapshotId })
  }

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-xl font-semibold">
            {repo.owner}/{repo.name}
          </h2>
          <SnapshotSelector snapshots={items} selectedId={selected.id} onSelect={selectSnapshot} />
        </div>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-slate-500">URL</dt>
          <dd>
            <a href={repo.canonicalUrl} className="text-blue-700 hover:underline" rel="noreferrer">
              {repo.canonicalUrl}
            </a>
          </dd>
          <dt className="text-slate-500">Branch</dt>
          <dd>{selected.branch ?? repo.defaultBranch ?? 'Unknown'}</dd>
          <dt className="text-slate-500">Commit</dt>
          <dd className="flex items-center gap-2">
            <span className="font-mono text-xs">{selected.commitSha}</span>
            <CopyButton value={selected.commitSha} label="Copy SHA" />
          </dd>
          <dt className="text-slate-500">Analyzed</dt>
          <dd>{formatDateTime(latestRun?.completedAt)}</dd>
          <dt className="text-slate-500">Run status</dt>
          <dd>
            <RunStatusLabel status={latestRun?.status} />
          </dd>
        </dl>
      </header>

      {runId && !completed && <AnalysisProgress runId={runId} pollIntervalMs={pollIntervalMs} />}
      {!runId && !completed && (
        <EmptyState title="Not analyzed" description="This commit has no analysis run yet." />
      )}

      {completed && report.isPending && <p role="status">Loading report…</p>}
      {completed && report.error && <ErrorState title="Could not load the report" message={report.error.message} />}
      {completed && report.data && (
        <div className="space-y-8">
          <TechStack technologies={report.data.technologies} />
          <StructurePanel structure={report.data.structure} />
          <CapabilityMatrix rows={report.data.capabilityMatrix} />
          <LimitationsPanel limitations={report.data.limitations} />
        </div>
      )}
    </div>
  )
}
