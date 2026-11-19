import type { Confidence } from '../../types/api'
import Badge, { type BadgeTone } from './Badge'

const labels: Record<Confidence, { text: string; tone: BadgeTone }> = {
  DETECTED: { text: 'Detected', tone: 'success' },
  LIKELY: { text: 'Likely', tone: 'info' },
  POSSIBLE: { text: 'Possible', tone: 'warning' },
  NOT_VERIFIED: { text: 'Not verified', tone: 'neutral' },
}

export default function ConfidenceBadge({ confidence }: { confidence: Confidence }) {
  const { text, tone } = labels[confidence]
  return <Badge tone={tone}>{text}</Badge>
}
