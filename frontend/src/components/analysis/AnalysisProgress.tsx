import { useEffect, useState } from 'react'
import { Link } from 'react-router'
import { useAnalysisRun, useCancelRun, useRepository } from '../../hooks/queries'
import { useAnalyzeRepository } from '../../hooks/useAnalyzeRepository'
import { formatDateTime, formatDuration, shortSha } from '../../lib/format'
import { isTerminal, type AnalysisRun, type Execution, type RunPhase } from '../../types/api'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import CopyButton from '../ui/CopyButton'
import ErrorState from '../ui/ErrorState'
import { Accordion } from '../ui/Controls'
import { Stepper, type Step } from '../ui/Flow'
import Icon from '../ui/icons'
import { Notice, ProgressBar, SkeletonRows } from '../ui/Layout'
import RunStatusLabel from '../ui/RunStatusLabel'
import StatusLabel from '../ui/StatusLabel'

const STEPS: { phase: RunPhase; label: string }[] = [
  { phase: 'QUEUED', label: 'Queued' },
  { phase: 'INGESTING', label: 'Fetching code' },
  { phase: 'DETECTING', label: 'Detecting technologies' },
  { phase: 'ANALYZING', label: 'Running analyzers' },
  { phase: 'FINISHED', label: 'Finished' },
]

const STAGE: Record<RunPhase, number> = { QUEUED: 0, INGESTING: 1, DETECTING: 2, ANALYZING: 3, FINISHED: 4 }

/** The display state of one execution while the run is (or was) active. */
export type ExecutionState = 'QUEUED' | 'RUNNING' | 'COMPLETED' | 'COMPLETED_WITH_WARNINGS' | 'FAILED' | 'SKIPPED' | 'NOT_CONFIGURED' | 'NOT_SUPPORTED' | 'UNAVAILABLE' | 'TIMED_OUT' | 'CANCELLED' | 'NOT_ANALYZED'

export function executionState(execution: Execution, runActive: boolean): ExecutionState {
  if (execution.status === 'NOT_ANALYZED') {
    if (!runActive) return 'NOT_ANALYZED'
    return execution.startedAt && !execution.completedAt ? 'RUNNING' : 'QUEUED'
  }
  return execution.status
}

/**
 * Aggregate progress while analyzers run: executions that reached a result / all executions. It is a coarse
 * measure (analyzers differ greatly in cost), not a time estimate. Null outside the ANALYZING phase or
 * before any execution is listed: it is not measured then, and is never shown as 0%.
 */
export function aggregateProgress(run: AnalysisRun): { done: number; total: number } | null {
  if (run.phase !== 'ANALYZING' || run.executions.length === 0) return null
  const done = run.executions.filter((e) => e.status !== 'NOT_ANALYZED').length
  return { done, total: run.executions.length }
}

function useNow(active: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const id = setInterval(() => setNow(Date.now()), 1000)
    return () => clearInterval(id)
  }, [active])
  return now
}

const isRateLimit = (run: AnalysisRun) => !!run.retryAfter

function stateBadge(state: ExecutionState) {
  if (state === 'QUEUED') return <Badge tone="neutral" icon="hourglass">Queued</Badge>
  if (state === 'RUNNING') return <Badge tone="running" icon="spinner">Running</Badge>
  return <StatusLabel status={state} />
}

/** Live progress of one run: polls while the run is active (react-query pauses polling in a hidden tab) and stops at a terminal status. */
export default function AnalysisProgress({ runId, pollIntervalMs }: { runId: string; pollIntervalMs?: number }) {
  const { data: run, error, isPending } = useAnalysisRun(runId, pollIntervalMs)
  const cancel = useCancelRun(runId)
  const repository = useRepository(run?.repositoryId ?? '')
  const analyze = useAnalyzeRepository()
  const [confirming, setConfirming] = useState(false)
  const active = !!run && !isTerminal(run.status)
  const now = useNow(active)

  if (isPending) {
    return <SkeletonRows rows={3} label="Loading analysis status" />
  }
  if (error || !run) {
    return <ErrorState title="Could not load the analysis" message={error?.message ?? 'Unknown error'} />
  }

  const startedMs = new Date(run.startedAt ?? run.requestedAt).getTime()
  const elapsed = active ? formatDuration(Math.max(0, now - startedMs)) : run.startedAt && run.completedAt ? formatDuration(new Date(run.completedAt).getTime() - new Date(run.startedAt).getTime()) : null
  const progress = aggregateProgress(run)
  const stage = STAGE[run.phase]
  const steps: Step[] = STEPS.map((s, i) => ({ id: s.phase, label: s.label, state: i < stage || (run.status === 'COMPLETED' || run.status === 'COMPLETED_WITH_WARNINGS') ? 'done' : i === stage ? 'active' : 'pending' }))
  const retryAt = run.retryAfter ? new Date(run.retryAfter).getTime() : null
  const retryBlocked = retryAt !== null && retryAt > now
  const url = repository.data?.canonicalUrl
  const name = repository.data?.name ?? 'Repository'
  const states = run.executions.map((e) => executionState(e, active))
  const count = (...wanted: ExecutionState[]) => states.filter((s) => wanted.includes(s)).length
  const retry = () => url && analyze.mutate({ url, forceRescan: true })
  const reportHref = `/repositories/${run.repositoryId}?snapshot=${run.snapshotId}`

  return (
    <section aria-label="Analysis progress" className="space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="min-w-0">
          <p className="flex items-center gap-2 font-mono text-label-sm uppercase tracking-wider text-primary-text">
            Run <span title={run.runId}>{run.runId.slice(0, 8)}</span>
            <span className="inline-flex items-center gap-1 rounded bg-raised px-1.5 py-0.5 normal-case text-fg-2">
              <Icon name="commit" size={12} />
              {shortSha(run.commitSha)}
              <CopyButton value={run.commitSha} label="Copy SHA" compact />
            </span>
          </p>
          <h2 className="text-headline-lg font-semibold tracking-tight">{name} Analysis</h2>
        </div>
        <div className="flex items-center gap-2">
          {confirming && active ? (
            <>
              <span className="text-body-md">Cancel this analysis?</span>
              <Button
                variant="danger"
                busy={cancel.isPending}
                onClick={() => {
                  cancel.mutate()
                  setConfirming(false)
                }}
              >
                Yes, cancel
              </Button>
              <Button variant="ghost" onClick={() => setConfirming(false)}>
                Keep running
              </Button>
            </>
          ) : (
            <Button variant="secondary" icon="stop" disabled={!active} busy={cancel.isPending} onClick={() => setConfirming(true)}>
              {cancel.isPending ? 'Cancelling…' : 'Cancel Analysis'}
            </Button>
          )}
        </div>
      </header>
      {cancel.error && <p role="alert" className="text-body-md text-[color:var(--t-failed-fg)]">{cancel.error.message}</p>}

      <div className="grid gap-3 sm:grid-cols-3" aria-label="Run status">
        <div className="rounded-lg border border-line bg-surface p-3">
          <p className="font-mono text-label-sm uppercase tracking-wider text-muted">Status</p>
          <div className="mt-1 flex items-center gap-2">
            <RunStatusLabel status={run.status} />
            {active && <span className="text-body-sm text-fg-2">Stage {stage + 1} of {STEPS.length}</span>}
          </div>
        </div>
        <div className="rounded-lg border border-line bg-surface p-3">
          <p className="font-mono text-label-sm uppercase tracking-wider text-muted">Elapsed time</p>
          <p className="mt-1 font-mono text-headline-md" aria-live="off">{elapsed ?? 'Not recorded'}</p>
        </div>
        <div className="rounded-lg border border-line bg-surface p-3">
          <p className="font-mono text-label-sm uppercase tracking-wider text-muted">Aggregate progress</p>
          {progress ? (
            <div className="mt-1 space-y-1">
              <p className="text-body-md">{progress.done} of {progress.total} analyzers finished</p>
              <ProgressBar value={progress.done} max={progress.total} label="Analyzers finished" />
            </div>
          ) : (
            <p className="mt-1 text-body-md text-muted">Measured while analyzers run</p>
          )}
        </div>
      </div>

      {(active || run.status === 'COMPLETED' || run.status === 'COMPLETED_WITH_WARNINGS') && <Stepper label="Analysis stages" steps={steps} />}

      {run.status === 'FAILED' && (
        <div role="alert" className="tone tone-failed space-y-2 rounded-lg p-4">
          {isRateLimit(run) ? (
            <>
              <p className="font-semibold text-fg">GitHub rate limit reached. Retry after {retryAt ? new Date(retryAt).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' }) : 'later'}</p>
              <p className="text-body-md text-fg">A GitHub token raises the limit from 60 to 5,000 requests per hour (see the README, section &quot;GitHub token&quot;).</p>
            </>
          ) : (
            <p className="font-semibold text-fg">{run.failureReason ?? 'The analysis failed.'}</p>
          )}
          {isRateLimit(run) && run.failureReason && <p className="text-body-md text-fg-2">{run.failureReason}</p>}
          {run.retryAfter && <p className="text-body-md text-fg">You can try again after {formatDateTime(run.retryAfter)}.</p>}
          {run.executions.some((e) => e.status === 'FAILED') && (
            <p className="text-body-md text-fg">Failed analyzers: {run.executions.filter((e) => e.status === 'FAILED').map((e) => e.analyzerId).join(', ')}</p>
          )}
          <Button variant="secondary" icon="refresh" busy={analyze.isPending} disabled={!url || retryBlocked} onClick={retry}>
            Retry
          </Button>
        </div>
      )}
      {run.status === 'CANCELLED' && (
        <Notice>
          <p>This analysis was cancelled{run.completedAt ? ` at ${formatDateTime(run.completedAt)}` : ''}. Results of analyzers that had already finished are kept.</p>
          <div className="mt-2">
            <Button variant="secondary" icon="refresh" busy={analyze.isPending} disabled={!url} onClick={retry}>
              Re-run
            </Button>
          </div>
        </Notice>
      )}
      {(run.status === 'COMPLETED' || run.status === 'COMPLETED_WITH_WARNINGS') && (
        <div className="flex items-center gap-3">
          <Link to={reportHref}>
            <Button variant="primary" icon="arrow-right">View report</Button>
          </Link>
        </div>
      )}

      {run.executions.length > 0 && (
        <div className="space-y-2">
          <h3 className="text-headline-md font-semibold">Dispatched analyzers</h3>
          <p className="text-body-sm text-muted" aria-label="Analyzer counts">
            {run.executions.length} Configured · {count('COMPLETED', 'COMPLETED_WITH_WARNINGS')} Completed · {count('RUNNING')} Running · {count('FAILED', 'TIMED_OUT')} Failed · {count('NOT_CONFIGURED')} Not configured
          </p>
          <div className="overflow-x-auto rounded-lg border border-line">
            <table className="w-full text-left text-body-md">
              <caption className="sr-only">Analyzers of this run</caption>
              <thead className="bg-raised">
                <tr>
                  {['Analyzer', 'State', 'Duration', 'Result'].map((h) => (
                    <th key={h} scope="col" className="px-3 py-2 font-mono text-label-sm font-medium uppercase tracking-wider text-muted">{h}</th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {run.executions.map((execution, i) => {
                  const state = states[i]
                  const failed = state === 'FAILED' || state === 'TIMED_OUT'
                  const duration = state === 'RUNNING' && execution.startedAt ? `${formatDuration(Math.max(0, now - new Date(execution.startedAt).getTime()))} elapsed` : formatDuration(execution.durationMs)
                  return (
                    <tr key={execution.analyzerId} className={`border-t border-line ${failed ? 'bg-[color-mix(in_srgb,var(--t-failed)_8%,transparent)]' : ''}`}>
                      <td className="px-3 py-2 align-top">
                        <span className="font-mono text-body-sm">{execution.analyzerId}</span>
                        <span className="block text-body-sm text-muted">{execution.category.toLowerCase().replace('_', ' ')}</span>
                      </td>
                      <td className="px-3 py-2 align-top">{stateBadge(state)}</td>
                      <td className="px-3 py-2 align-top font-mono text-body-sm">{duration ?? '—'}</td>
                      <td className="px-3 py-2 align-top text-body-md">
                        {failed ? (
                          <Accordion title={<span className="text-[color:var(--t-failed-fg)]">View trace</span>}>
                            <p className="whitespace-pre-wrap break-words font-mono text-body-sm">{execution.errorSummary ?? execution.reason ?? 'No details were recorded.'}</p>
                          </Accordion>
                        ) : state === 'SKIPPED' || state === 'NOT_CONFIGURED' || state === 'NOT_SUPPORTED' || state === 'UNAVAILABLE' ? (
                          <span className="text-fg-2">
                            {execution.reason ?? execution.errorSummary ?? 'No reason was reported.'}
                            {state === 'NOT_CONFIGURED' && execution.category === 'TESTS' && <span className="block text-body-sm text-muted">See the README, section &quot;Optional tools and features&quot;.</span>}
                          </span>
                        ) : (
                          <span className="text-muted">{execution.reason ?? ''}</span>
                        )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
        </div>
      )}
    </section>
  )
}
