import { formatDateTime, shortSha } from '../../lib/format'
import type { Snapshot } from '../../types/api'
import { Select } from '../ui/Controls'

const statusText: Record<string, string> = {
  QUEUED: 'Queued',
  INGESTING: 'Fetching code',
  ANALYZING: 'Analyzing',
  COMPLETED: 'Completed',
  COMPLETED_WITH_WARNINGS: 'Completed with warnings',
  FAILED: 'Failed',
  CANCELLED: 'Cancelled',
}

/** Each option shows the short SHA, the date and the run status; the first (newest) is marked Latest. */
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
    <Select label="Snapshot" value={selectedId ?? ''} onChange={(event) => onSelect(event.target.value)} className="font-mono">
      {snapshots.map((snapshot, index) => (
        <option key={snapshot.id} value={snapshot.id}>
          {`${shortSha(snapshot.commitSha)} · ${formatDateTime(snapshot.createdAt)} · ${
            snapshot.latestRun ? statusText[snapshot.latestRun.status] : 'Not analyzed'
          }${index === 0 ? ' (Latest)' : ''}`}
        </option>
      ))}
    </Select>
  )
}
