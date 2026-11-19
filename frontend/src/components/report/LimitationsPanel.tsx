import type { Limitation } from '../../types/api'
import StatusLabel from '../ui/StatusLabel'

export default function LimitationsPanel({ limitations }: { limitations: Limitation[] }) {
  return (
    <section aria-label="Limitations">
      <h2 className="text-base font-semibold">Limitations</h2>
      {limitations.length === 0 ? (
        <p className="mt-2 text-sm text-slate-600">No limitations were reported for this analysis.</p>
      ) : (
        <ul className="mt-2 space-y-2 text-sm">
          {limitations.map((limitation, index) => (
            <li key={`${limitation.analyzerId ?? 'none'}-${index}`} className="flex flex-wrap items-center gap-2">
              <StatusLabel status={limitation.status} />
              {limitation.analyzerId && <span className="font-mono text-xs">{limitation.analyzerId}</span>}
              <span>{limitation.reason}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
