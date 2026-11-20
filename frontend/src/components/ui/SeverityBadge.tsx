import type { Severity } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

const severityTone: Record<Severity, BadgeTone> = {
  CRITICAL: 'danger',
  HIGH: 'danger',
  MEDIUM: 'warning',
  LOW: 'info',
  INFO: 'neutral',
}

export default function SeverityBadge({ severity }: { severity: Severity }) {
  return <Badge tone={severityTone[severity]}>{severity.charAt(0) + severity.slice(1).toLowerCase()}</Badge>
}

