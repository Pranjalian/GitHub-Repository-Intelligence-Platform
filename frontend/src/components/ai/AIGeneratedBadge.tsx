/** Marks text written by a model, so it is never mistaken for an analysis result. */
export default function AIGeneratedBadge() {
  return (
    <span
      className="inline-flex items-center rounded bg-violet-100 px-2 py-0.5 text-xs font-semibold text-violet-800"
      title="Written by an AI model from the evidence listed below. It can be wrong; check the evidence."
    >
      AI-generated
    </span>
  )
}
