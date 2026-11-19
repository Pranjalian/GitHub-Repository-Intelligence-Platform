import { useAnalysisRun, useCancelRun } from '../../hooks/queries'
import { formatDateTime } from '../../lib/format'
import { isTerminal, type RunPhase } from '../../types/api'
import Badge from '../ui/Badge'
import ErrorState from '../ui/ErrorState'
import RunStatusLabel from '../ui/RunStatusLabel'
import StatusLabel from '../ui/StatusLabel'

const phaseText: Record<RunPhase, string> = {
  QUEUED: 'Waiting in the queue',
  INGESTING: 'Fetching the repository code from GitHub',
  DETECTING: 'Detecting technologies',
  ANALYZING: 'Running analyzers',
  FINISHED: 'Finished',
}

/** Live progress of one run: polls while the run is active and stops once it reaches a terminal status. */
export default function AnalysisProgress({ runId, pollIntervalMs }: { runId: string; pollIntervalMs?: number }) {
  const { data: run, error, isPending } = useAnalysisRun(runId, pollIntervalMs)
  const cancel = useCancelRun(runId)

  if (isPending) {
    return <p role="status">Loading analysis status…</p>
  }
  if (error || !run) {
    return <ErrorState title="Could not load the analysis" message={error?.message ?? 'Unknown error'} />
  }
  const active = !isTerminal(run.status)

  return (
    <section aria-label="Analysis progress" className="rounded-lg border border-slate-200 bg-white p-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex items-center gap-2">
          <h2 className="text-base font-semibold">Analysis</h2>
          <RunStatusLabel status={run.status} />
        </div>
        {active && (
          <button
            type="button"
            onClick={() => cancel.mutate()}
            disabled={cancel.isPending}
            className="rounded border border-slate-300 px-3 py-1 text-sm hover:bg-slate-100 disabled:opacity-50"
          >
            {cancel.isPending ? 'Cancelling…' : 'Cancel'}
          </button>
        )}
      </div>
      <p className="mt-2 text-sm text-slate-600">{phaseText[run.phase]}</p>

      {run.status === 'FAILED' && (
        <div role="alert" className="mt-3 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          <p>{run.failureReason ?? 'The analysis failed.'}</p>
          {run.retryAfter && <p className="mt-1">You can try again after {formatDateTime(run.retryAfter)}.</p>}
        </div>
      )}
      {run.status === 'CANCELLED' && <p className="mt-3 text-sm text-slate-700">This analysis was cancelled.</p>}
      {cancel.error && <p className="mt-2 text-sm text-red-700">{cancel.error.message}</p>}

      {run.executions.length > 0 && (
        <ul className="mt-3 divide-y divide-slate-100 text-sm" aria-label="Analyzers">
          {run.executions.map((execution) => (
            <li key={execution.analyzerId} className="flex items-center justify-between gap-2 py-1.5">
              <span className="font-mono text-xs">{execution.analyzerId}</span>
              {execution.status === 'NOT_ANALYZED' && active ? (
                <Badge tone="neutral">Waiting</Badge>
              ) : (
                <StatusLabel status={execution.status} />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
