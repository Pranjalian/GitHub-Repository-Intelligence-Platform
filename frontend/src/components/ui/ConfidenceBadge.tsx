import type { Confidence } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

/** Solid check = DETECTED (deterministic), dashed outline = LIKELY (heuristic), sparkle = POSSIBLE (inferred). */
const labels: Record<Confidence, { text: string; tone: BadgeTone; icon: string; dashed?: boolean }> = {
  DETECTED: { text: 'Detected', tone: 'detected', icon: 'check-circle' },
  LIKELY: { text: 'Likely', tone: 'likely', icon: 'info', dashed: true },
  POSSIBLE: { text: 'Possible', tone: 'possible', icon: 'sparkle' },
  NOT_VERIFIED: { text: 'Not verified', tone: 'neutral', icon: 'info' },
}

export default function ConfidenceBadge({ confidence }: { confidence: Confidence }) {
  const entry = labels[confidence] ?? { text: String(confidence ?? 'Unknown'), tone: 'neutral' as BadgeTone, icon: 'info' }
  return (
    <Badge tone={entry.tone} icon={entry.icon} dashed={entry.dashed}>
      {entry.text}
    </Badge>
  )
}
