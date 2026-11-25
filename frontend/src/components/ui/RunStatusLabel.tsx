import type { RunStatus } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

const labels: Record<RunStatus, { text: string; tone: BadgeTone; icon: string }> = {
  QUEUED: { text: 'Queued', tone: 'neutral', icon: 'hourglass' },
  INGESTING: { text: 'Fetching code', tone: 'running', icon: 'spinner' },
  ANALYZING: { text: 'Analyzing', tone: 'running', icon: 'spinner' },
  COMPLETED: { text: 'Completed', tone: 'success', icon: 'check-circle' },
  COMPLETED_WITH_WARNINGS: { text: 'Completed with warnings', tone: 'warning', icon: 'warning' },
  FAILED: { text: 'Failed', tone: 'failed', icon: 'error' },
  CANCELLED: { text: 'Cancelled', tone: 'neutral', icon: 'skip' },
}

export default function RunStatusLabel({ status }: { status: RunStatus | null | undefined }) {
  if (!status) {
    return (
      <Badge tone="neutral" icon="info">
        Not analyzed
      </Badge>
    )
  }
  const entry = labels[status] ?? { text: String(status), tone: 'neutral' as BadgeTone, icon: 'info' }
  return (
    <Badge tone={entry.tone} icon={entry.icon}>
      {entry.text}
    </Badge>
  )
}
