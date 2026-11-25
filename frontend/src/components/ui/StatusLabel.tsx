import type { ExecutionStatus } from '../../types/status'
import Badge, { type BadgeTone } from './Badge'

/** "Not analyzed / Not configured / Not supported / Unavailable" share one deliberate neutral style (never red). */
const labels: Record<ExecutionStatus, { text: string; tone: BadgeTone; icon: string }> = {
  COMPLETED: { text: 'Completed', tone: 'success', icon: 'check-circle' },
  COMPLETED_WITH_WARNINGS: { text: 'Completed with warnings', tone: 'warning', icon: 'warning' },
  NOT_CONFIGURED: { text: 'Not configured', tone: 'neutral', icon: 'config' },
  NOT_SUPPORTED: { text: 'Not supported', tone: 'neutral', icon: 'skip' },
  UNAVAILABLE: { text: 'Unavailable', tone: 'neutral', icon: 'info' },
  SKIPPED: { text: 'Skipped', tone: 'neutral', icon: 'skip' },
  FAILED: { text: 'Failed', tone: 'failed', icon: 'error' },
  TIMED_OUT: { text: 'Timed out', tone: 'failed', icon: 'clock' },
  CANCELLED: { text: 'Cancelled', tone: 'neutral', icon: 'skip' },
  NOT_ANALYZED: { text: 'Not analyzed', tone: 'neutral', icon: 'info' },
}

/** Renders an analyzer status with its honest label. A missing metric is never shown as zero. */
export default function StatusLabel({ status }: { status: ExecutionStatus }) {
  const entry = labels[status] ?? { text: String(status ?? 'Unknown'), tone: 'neutral' as BadgeTone, icon: 'info' }
  return (
    <Badge tone={entry.tone} icon={entry.icon}>
      {entry.text}
    </Badge>
  )
}
