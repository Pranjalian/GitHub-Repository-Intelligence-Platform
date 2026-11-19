import { formatDateTime, shortSha } from '../../lib/format'
import type { Snapshot } from '../../types/api'

const statusText: Record<string, string> = {
  QUEUED: 'Queued',
  INGESTING: 'Fetching code',
  ANALYZING: 'Analyzing',
  COMPLETED: 'Completed',
  COMPLETED_WITH_WARNINGS: 'Completed with warnings',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

export default function SnapshotSelector({
  snapshots,
  selectedId,
  onSelect,
}: {
  snapshots: Snapshot[]
  selectedId: string | undefined
  onSelect: (snapshotId: string) => void
}) {
  return (
    <label className="flex items-center gap-2 text-sm">
      <span className="font-medium">Snapshot</span>
      <select
        value={selectedId ?? ''}
        onChange={(event) => onSelect(event.target.value)}
        className="rounded border border-slate-300 bg-white px-2 py-1 text-sm"
      >
        {snapshots.map((snapshot) => (
          <option key={snapshot.id} value={snapshot.id}>
            {`${shortSha(snapshot.commitSha)} · ${formatDateTime(snapshot.createdAt)} · ${
              snapshot.latestRun ? statusText[snapshot.latestRun.status] : 'Not analyzed'
            }`}
          </option>
        ))}
      </select>
    </label>
  )
}
