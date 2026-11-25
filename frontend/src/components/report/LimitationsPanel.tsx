import type { Limitation } from '../../types/api'
import Icon from '../ui/icons'
import { Card } from '../ui/Layout'
import StatusLabel from '../ui/StatusLabel'

const ICON: Record<string, string> = { FAILED: 'error', TIMED_OUT: 'clock', UNAVAILABLE: 'info', NOT_CONFIGURED: 'config', NOT_SUPPORTED: 'skip', SKIPPED: 'skip' }

/** One card per limitation the API returns, with its analyzer: data driven, any number of cards. */
export default function LimitationsPanel({ limitations }: { limitations: Limitation[] }) {
  return (
    <Card icon="shield" title="Analytical Boundaries & Guardrails" meta="What this report could not determine, in the analyzers' own words">
      <div role="region" aria-label="Limitations">
        {limitations.length === 0 ? (
          <p className="text-body-md text-fg-2">No limitations were reported for this analysis.</p>
        ) : (
          <ul className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
            {limitations.map((limitation, index) => (
              <li key={`${limitation.analyzerId ?? 'none'}-${index}`} className="space-y-2 rounded-lg border border-line bg-canvas p-3">
                <div className="flex items-center justify-between gap-2">
                  <span className="flex items-center gap-1.5 text-body-md font-semibold">
                    <Icon name={ICON[limitation.status] ?? 'info'} size={14} className="text-fg-2" />
                    {limitation.category.toLowerCase().replace('_', ' ')}
                  </span>
                  <StatusLabel status={limitation.status} />
                </div>
                <p className="text-body-md text-fg-2">{limitation.reason}</p>
                {limitation.analyzerId && <p className="font-mono text-body-sm text-muted">{limitation.analyzerId}</p>}
              </li>
            ))}
          </ul>
        )}
      </div>
    </Card>
  )
}
