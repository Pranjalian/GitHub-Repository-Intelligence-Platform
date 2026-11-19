import type { ExecutionStatus } from '../../types/status'
import Badge, { type BadgeTone } from './Badge'

const labels: Record<ExecutionStatus, { text: string; tone: BadgeTone }> = {
  COMPLETED: { text: 'Completed', tone: 'success' },
  COMPLETED_WITH_WARNINGS: { text: 'Completed with warnings', tone: 'warning' },
  NOT_CONFIGURED: { text: 'Not configured', tone: 'neutral' },
  NOT_SUPPORTED: { text: 'Not supported', tone: 'neutral' },
  UNAVAILABLE: { text: 'Unavailable', tone: 'warning' },
  SKIPPED: { text: 'Skipped', tone: 'neutral' },
  FAILED: { text: 'Failed', tone: 'danger' },
  TIMED_OUT: { text: 'Timed out', tone: 'danger' },
  CANCELLED: { text: 'Cancelled', tone: 'neutral' },
  NOT_ANALYZED: { text: 'Not analyzed', tone: 'neutral' },
}

/** Renders an analyzer status with its honest label. A missing metric is never shown as zero. */
export default function StatusLabel({ status }: { status: ExecutionStatus }) {
  const { text, tone } = labels[status]
  return <Badge tone={tone}>{text}</Badge>
}
