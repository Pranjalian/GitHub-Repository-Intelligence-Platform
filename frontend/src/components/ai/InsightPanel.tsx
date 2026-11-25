import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import { useAIProviders, useInsight } from '../../hooks/queries'
import { readPreferredProvider, writePreferredProvider } from '../../lib/aiPreference'
import type { Insight, InsightType } from '../../types/api'
import Button from '../ui/Button'
import { Tooltip } from '../ui/Controls'
import Icon from '../ui/icons'
import { Skeleton } from '../ui/Layout'
import InsightResult from './InsightResult'

/**
 * The shared AI card trigger for every AI entry point. Stages: idle (a restrained pill, nothing runs until
 * clicked), checking availability, asking (skeleton, elapsed counter, client-side Cancel: the request is
 * abandoned and a late answer ignored), the result, or the failure. A result has a Regenerate button that
 * asks again with `refresh: true` (the server skips its cache; the call still counts against the budget). When AI is unavailable the trigger
 * stays visible but disabled with the reason and a link to AI Settings; nothing else on the page changes.
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
  const [asking, setAsking] = useState(false)
  const [elapsed, setElapsed] = useState(0)
  const [result, setResult] = useState<Insight | null>(null)
  const [failure, setFailure] = useState<string | null>(null)
  const current = useRef(0)
  const available = providers.data?.available === true
  const checking = providers.isPending
  const reason = providers.data
    ? (providers.data.unavailableReason ?? 'AI insights are unavailable.')
    : providers.error
      ? 'The AI status could not be loaded.'
      : 'Checking whether AI is available…'
  const local = providers.data?.providers.find((p) => p.dataHandling === 'LOCAL' && p.active && p.permitted)

  useEffect(() => {
    if (!asking) return
    const id = setInterval(() => setElapsed((s) => s + 1), 1000)
    return () => clearInterval(id)
  }, [asking])

  function ask(provider: string | null = readPreferredProvider(), refresh = false) {
    const id = ++current.current
    setOpen(true)
    setAsking(true)
    setElapsed(0)
    setResult(null)
    setFailure(null)
    insight.mutate(
      { type, targetId: targetId ?? null, provider, ...(refresh ? { refresh: true } : {}) },
      {
        onSuccess: (data) => {
          if (current.current !== id) return // cancelled: the late answer is ignored
          setResult(data)
          setAsking(false)
        },
        onError: (error) => {
          if (current.current !== id) return
          setFailure(error.message)
          setAsking(false)
        },
      },
    )
  }

  function cancel() {
    current.current++
    setAsking(false)
    setOpen(false)
  }

  return (
    <div className={compact ? 'inline-block w-full' : 'space-y-2'}>
      <div className="flex flex-wrap items-center gap-2">
        {checking ? (
          <span role="status" className="inline-flex items-center gap-2 text-body-sm text-muted">
            <Icon name="spinner" size={14} />
            Checking whether AI is available…
          </span>
        ) : (
          <>
            <Tooltip text={available ? 'An AI model explains the deterministic evidence. The answer is marked AI-generated.' : `AI insights are unavailable. ${reason}`}>
              <Button variant="ai" icon="sparkle" onClick={() => ask()} disabled={!available || asking} className="h-7 rounded-full px-3 text-body-sm" title={available ? `${actionLabel} with AI` : reason}>
                {asking ? 'Asking…' : actionLabel}
              </Button>
            </Tooltip>
            {open && !asking && (
              <button type="button" onClick={() => setOpen(false)} className="text-body-sm text-muted hover:underline">
                Hide
              </button>
            )}
            {!available && !compact && providers.data && (
              <span className="text-body-sm text-muted">
                {reason}{' '}
                <Link to="/settings/ai" className="text-primary-text underline">
                  AI settings and setup guide
                </Link>
              </span>
            )}
          </>
        )}
      </div>
      {open && asking && (
        <div role="status" className="ai-surface space-y-2 p-3" aria-label="Asking the model">
          <div className="flex items-center justify-between text-body-sm text-fg-2">
            <span>Asking the model; this can take up to a minute… {elapsed}s</span>
            <Button variant="ghost" onClick={cancel} title="Stops waiting in this page; the request may still finish on the server">
              Cancel
            </Button>
          </div>
          <Skeleton className="h-3 w-3/4" />
          <Skeleton className="h-3 w-1/2" />
        </div>
      )}
      {open && failure && (
        <p role="alert" className="text-body-sm text-[color:var(--t-failed-fg)]">
          {failure}
        </p>
      )}
      {open && result && (
        <div className="mt-2">
          <InsightResult
            insight={result}
            onRetry={() => (result.status === 'ERROR' ? providers.refetch() : ask())}
            onRegenerate={() => ask(readPreferredProvider(), true)}
            onUseLocal={
              local
                ? () => {
                    writePreferredProvider(local.id)
                    ask(local.id)
                  }
                : undefined
            }
          />
        </div>
      )}
    </div>
  )
}
