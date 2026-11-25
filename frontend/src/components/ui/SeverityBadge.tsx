import type { Severity } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

const config: Record<Severity, { tone: BadgeTone; icon: string }> = {
  CRITICAL: { tone: 'critical', icon: 'error' },
  HIGH: { tone: 'high', icon: 'warning' },
  MEDIUM: { tone: 'medium', icon: 'warning' },
  LOW: { tone: 'low', icon: 'info' },
  INFO: { tone: 'info', icon: 'info' },
}

export default function SeverityBadge({ severity }: { severity: Severity }) {
  const entry = config[severity] ?? { tone: 'neutral' as BadgeTone, icon: 'info' }
  return (
    <Badge tone={entry.tone} icon={entry.icon}>
      {severity ? severity.charAt(0) + severity.slice(1).toLowerCase() : 'Unknown'}
    </Badge>
  )
}
