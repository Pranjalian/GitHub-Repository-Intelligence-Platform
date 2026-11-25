import Badge from '../ui/Badge'

/** Marks text written by a model, so it is never mistaken for an analysis result. */
export default function AIGeneratedBadge() {
  return (
    <Badge tone="ai" icon="sparkle" title="Written by an AI model from the evidence listed below. It can be wrong; check the evidence.">
      AI-generated
    </Badge>
  )
}
