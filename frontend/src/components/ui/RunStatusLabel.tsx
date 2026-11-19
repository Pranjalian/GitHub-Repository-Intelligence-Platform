import type { RunStatus } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

const labels: Record<RunStatus, { text: string; tone: BadgeTone }> = {
  QUEUED: { text: 'Queued', tone: 'neutral' },
  INGESTING: { text: 'Fetching code', tone: 'info' },
  ANALYZING: { text: 'Analyzing', tone: 'info' },
  COMPLETED: { text: 'Completed', tone: 'success' },
  COMPLETED_WITH_WARNINGS: { text: 'Completed with warnings', tone: 'warning' },
  FAILED: { text: 'Failed', tone: 'danger' },
  CANCELLED: { text: 'Cancelled', tone: 'neutral' },
}

export default function RunStatusLabel({ status }: { status: RunStatus | null | undefined }) {
  if (!status) {
    return <Badge tone="neutral">Not analyzed</Badge>
  }
  const { text, tone } = labels[status]
  return <Badge tone={tone}>{text}</Badge>
}
