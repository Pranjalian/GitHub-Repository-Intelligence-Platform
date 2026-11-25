import { analyzeRepository } from '../../api/repositories'
import { useState } from 'react'
import { useNavigate } from 'react-router'
import { formatDateTime, formatDuration, shortSha } from '../../lib/format'
import { isCompleted, type Repository, type Snapshot } from '../../types/api'
import Button from '../ui/Button'
import CopyButton from '../ui/CopyButton'
import Icon from '../ui/icons'
import RunStatusLabel from '../ui/RunStatusLabel'
import SnapshotSelector from './SnapshotSelector'

/** Repository, commit and run context at the top of every report tab. */
export default function ReportHeader({
  repository,
  snapshots,
  selected,
  onSelectSnapshot,
  durationMs,
}: {
  repository: Repository
  snapshots: Snapshot[]
  selected: Snapshot
  onSelectSnapshot: (id: string) => void
  durationMs?: number | null
}) {
  const navigate = useNavigate()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const run = selected.latestRun
  const wallClock = formatDuration(durationMs ?? null)

  async function reanalyze() {
    setBusy(true)
    setError(null)
    try {
      const response = await analyzeRepository(repository.canonicalUrl, true)
      navigate(`/repositories/${response.repositoryId}?snapshot=${response.snapshotId}&run=${response.analysisRunId}`)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Could not start the analysis')
    } finally {
      setBusy(false)
    }
  }

  return (
    <header className="space-y-2">
      <h1 className="text-headline-lg font-semibold tracking-tight">
        {repository.owner}/{repository.name}
      </h1>
      <div className="flex flex-wrap items-center justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <span className="inline-flex h-8 items-center gap-1.5 rounded border border-line bg-surface px-2 font-mono text-body-sm">
            <Icon name="code" size={14} />
            {selected.branch ?? repository.defaultBranch ?? 'unknown branch'}
          </span>
          <span className="inline-flex h-8 items-center gap-1.5 rounded border border-line bg-surface px-2 font-mono text-body-sm" title={selected.commitSha}>
            <Icon name="commit" size={14} />
            {shortSha(selected.commitSha)}
            <CopyButton value={selected.commitSha} label="Copy SHA" compact />
          </span>
          <SnapshotSelector snapshots={snapshots} selectedId={selected.id} onSelect={onSelectSnapshot} />
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <span className="flex items-center gap-2 text-body-md">
            <RunStatusLabel status={run?.status} />
            {isCompleted(run?.status) && wallClock && <span className="font-mono text-body-sm text-fg-2">{wallClock} wall-clock</span>}
          </span>
          <Button variant="secondary" icon="refresh" busy={busy} onClick={reanalyze}>
            Re-analyze
          </Button>
        </div>
      </div>
      {error && <p role="alert" className="text-body-md text-[color:var(--t-failed-fg)]">{error}</p>}
      <p className="flex flex-wrap items-center gap-x-4 gap-y-1 text-body-sm text-muted">
        <span>
          Analyzed: {formatDateTime(run?.completedAt)} ({Intl.DateTimeFormat().resolvedOptions().timeZone})
        </span>
        <a href={repository.canonicalUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-primary-text hover:underline">
          <Icon name="github" size={14} />
          {repository.canonicalUrl}
          <Icon name="external-link" size={12} />
        </a>
      </p>
    </header>
  )
}
