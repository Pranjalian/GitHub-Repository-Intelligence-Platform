import { useState } from 'react'
import { Link } from 'react-router'
import { useAIProviders, useInsight } from '../../hooks/queries'
import { readPreferredProvider } from '../../lib/aiPreference'
import type { InsightType } from '../../types/api'
import InsightResult from './InsightResult'

/**
 * A button that asks for one AI insight and shows the answer below it. When AI is unavailable the button
 * is disabled and says why (compact: only as a tooltip, for long lists), with a link to the AI settings.
 */
export default function InsightPanel({
  repositoryId,
  snapshotId,
  type,
  targetId,
  actionLabel = 'Explain',
  compact = false,
}: {
  repositoryId: string
  snapshotId: string
  type: InsightType
  targetId?: string | null
  actionLabel?: string
  compact?: boolean
}) {
  const providers = useAIProviders()
  const insight = useInsight(repositoryId, snapshotId)
  const [open, setOpen] = useState(false)
  const available = providers.data?.available === true
  const reason = providers.data
    ? (providers.data.unavailableReason ?? 'AI insights are unavailable.')
    : providers.error
      ? 'The AI status could not be loaded.'
      : 'Checking whether AI is available…'

  function ask() {
    setOpen(true)
    insight.mutate({ type, targetId: targetId ?? null, provider: readPreferredProvider() })
  }

  return (
    <div className={compact ? 'inline-block w-full' : 'space-y-2'}>
      <div className="flex flex-wrap items-center gap-2">
        <button
          type="button"
          onClick={ask}
          disabled={!available || insight.isPending}
          title={available ? `${actionLabel} with AI` : reason}
          className="rounded border border-violet-300 bg-violet-50 px-2 py-0.5 text-xs font-medium text-violet-800 hover:bg-violet-100 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {insight.isPending ? 'Asking…' : actionLabel}
        </button>
        {open && !insight.isPending && (
          <button type="button" onClick={() => setOpen(false)} className="text-xs text-slate-500 hover:underline">
            Hide
          </button>
        )}
        {!available && !compact && providers.data && (
          <span className="text-xs text-slate-600">
            {reason}{' '}
            <Link to="/settings/ai" className="text-blue-700 hover:underline">
              AI settings and setup guide
            </Link>
          </span>
        )}
      </div>
      {open && insight.isPending && (
        <p role="status" className="mt-1 text-xs text-slate-600">
          Asking the model; this can take up to a minute…
        </p>
      )}
      {open && insight.error && (
        <p role="alert" className="mt-1 text-xs text-red-700">
          {insight.error.message}
        </p>
      )}
      {open && insight.data && (
        <div className="mt-2">
          <InsightResult insight={insight.data} />
        </div>
      )}
    </div>
  )
}
