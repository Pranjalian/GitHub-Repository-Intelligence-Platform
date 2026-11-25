import type { CapabilityLevel, VulnerabilityStatus } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

const depth: Record<CapabilityLevel, { text: string; tone: BadgeTone; icon: string }> = {
  DEEP: { text: 'Deep', tone: 'primary', icon: 'check-circle' },
  BASIC: { text: 'Basic', tone: 'likely', icon: 'info' },
  GENERIC: { text: 'Generic', tone: 'info', icon: 'info' },
  UNSUPPORTED: { text: 'Unsupported', tone: 'neutral', icon: 'skip' },
  UNAVAILABLE: { text: 'Unavailable', tone: 'neutral', icon: 'info' },
}

export function CapabilityBadge({ level }: { level: CapabilityLevel }) {
  const entry = depth[level] ?? { text: String(level), tone: 'neutral' as BadgeTone, icon: 'info' }
  return (
    <Badge tone={entry.tone} icon={entry.icon}>
      {entry.text}
    </Badge>
  )
}

const vulnerability: Record<VulnerabilityStatus, { text: string; tone: BadgeTone; icon: string }> = {
  NOT_CHECKED: { text: 'Not checked', tone: 'neutral', icon: 'info' },
  NOT_CHECKABLE: { text: 'Not checkable', tone: 'neutral', icon: 'skip' },
  NO_KNOWN_VULNERABILITIES: { text: 'No known vulnerabilities', tone: 'success', icon: 'check-circle' },
  VULNERABLE: { text: 'Vulnerable', tone: 'critical', icon: 'error' },
}

/** "Not checked" is never rendered as a clean result. */
export function VulnerabilityStatusBadge({ status }: { status: VulnerabilityStatus }) {
  const entry = vulnerability[status] ?? { text: String(status), tone: 'neutral' as BadgeTone, icon: 'info' }
  return (
    <Badge tone={entry.tone} icon={entry.icon}>
      {entry.text}
    </Badge>
  )
}

const methods: Record<string, BadgeTone> = {
  GET: 'success',
  POST: 'primary',
  PUT: 'medium',
  PATCH: 'likely',
  DELETE: 'critical',
}

export function HttpMethodBadge({ method }: { method: string }) {
  return <Badge tone={methods[method?.toUpperCase()] ?? 'neutral'}>{method || '?'}</Badge>
}

export function ProviderBadge({ kind }: { kind: 'local' | 'hosted' | 'unavailable' }) {
  if (kind === 'local') {
    return (
      <Badge tone="success" icon="monitor">
        Local
      </Badge>
    )
  }
  if (kind === 'hosted') {
    return (
      <Badge tone="warning" icon="globe">
        Hosted
      </Badge>
    )
  }
  return (
    <Badge tone="neutral" icon="info">
      AI unavailable
    </Badge>
  )
}

const kinds: Record<string, string> = {
  METHOD: 'method',
  CLASS: 'class',
  INTERFACE: 'interface',
  CONFIG: 'config',
  FIELD: 'file',
  ENUM: 'class',
  RECORD: 'class',
}

export function KindBadge({ kind }: { kind: string }) {
  return (
    <Badge tone="neutral" icon={kinds[kind?.toUpperCase()] ?? 'file'}>
      {kind || 'Symbol'}
    </Badge>
  )
}
