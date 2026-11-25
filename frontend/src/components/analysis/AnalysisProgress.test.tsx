import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { REPO_ID, RUN_ID, repository, run } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { Execution, RunPhase, RunStatus } from '../../types/api'
import AnalysisProgress, { aggregateProgress, executionState } from './AnalysisProgress'

const RUN_URL = `/api/v1/analysis-runs/${RUN_ID}`

function renderProgress() {
  server.use(http.get(`/api/v1/repositories/${REPO_ID}`, () => HttpResponse.json(repository())))
  return renderPage(<AnalysisProgress runId={RUN_ID} pollIntervalMs={25} />)
}

const exec = (over: Partial<Execution>): Execution => ({
  analyzerId: 'x',
  category: 'STRUCTURE',
  status: 'COMPLETED',
  startedAt: null,
  completedAt: null,
  durationMs: null,
  errorSummary: null,
  ...over,
})

describe('AnalysisProgress', () => {
  const cases: [RunStatus, string][] = [
    ['QUEUED', 'Queued'],
    ['INGESTING', 'Fetching code'],
    ['ANALYZING', 'Analyzing'],
    ['COMPLETED', 'Completed'],
    ['COMPLETED_WITH_WARNINGS', 'Completed with warnings'],
    ['FAILED', 'Failed'],
    ['CANCELLED', 'Cancelled'],
  ]

  it.each(cases)('renders the %s status in the status tile', async (status, label) => {
    server.use(http.get(RUN_URL, () => HttpResponse.json(run(status))))
    renderProgress()
    const tiles = await screen.findByLabelText('Run status')
    expect(within(tiles).getByText(label)).toBeInTheDocument()
  })

  const phases: [RunPhase, number][] = [
    ['QUEUED', 0],
    ['INGESTING', 1],
    ['DETECTING', 2],
    ['ANALYZING', 3],
  ]
  it.each(phases)('the stepper marks the %s phase active and the earlier ones done', async (phase, index) => {
    server.use(http.get(RUN_URL, () => HttpResponse.json(run('ANALYZING', { phase }))))
    renderProgress()
    const stepper = await screen.findByRole('list', { name: 'Analysis stages' })
    const items = within(stepper).getAllByRole('listitem')
    expect(items).toHaveLength(5)
    items.forEach((item, i) => {
      if (i === index) expect(item).toHaveAttribute('aria-current', 'step')
      else expect(item).not.toHaveAttribute('aria-current')
      if (i < index) expect(item).toHaveTextContent('(done)')
    })
    expect(screen.getByText(`Stage ${index + 1} of 5`)).toBeInTheDocument()
  })

  it('header counts match the executions and failed rows show the redacted summary behind View trace', async () => {
    server.use(
      http.get(RUN_URL, () =>
        HttpResponse.json(
          run('ANALYZING', {
            phase: 'ANALYZING',
            executions: [
              exec({ analyzerId: 'a-done', status: 'COMPLETED', durationMs: 5200 }),
              exec({ analyzerId: 'b-running', status: 'NOT_ANALYZED', startedAt: '2026-11-19T10:00:01Z' }),
              exec({ analyzerId: 'c-queued', status: 'NOT_ANALYZED' }),
              exec({ analyzerId: 'd-failed', status: 'FAILED', errorSummary: 'PMD crashed: token [REDACTED]' }),
              exec({ analyzerId: 'e-jacoco', category: 'TESTS', status: 'NOT_CONFIGURED', reason: 'No committed JaCoCo report' }),
            ],
          }),
        ),
      ),
    )
    renderProgress()
    expect(await screen.findByLabelText('Analyzer counts')).toHaveTextContent('5 Configured · 1 Completed · 1 Running · 1 Failed · 1 Not configured')
    expect(screen.getByText('3 of 5 analyzers finished')).toBeInTheDocument()
    expect(screen.getByText('5.2s')).toBeInTheDocument()
    // the failed trace is hidden until expanded, and shows only the summary
    expect(screen.queryByText('PMD crashed: token [REDACTED]')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /View trace/ }))
    expect(screen.getByText('PMD crashed: token [REDACTED]')).toBeInTheDocument()
    // Not configured shows the reason and is not styled as an error
    const row = screen.getByText('e-jacoco').closest('tr')!
    expect(within(row).getByText('No committed JaCoCo report')).toBeInTheDocument()
    expect(within(row).getByText('Not configured').closest('span')).toHaveClass('tone-neutral')
    expect(row.className).not.toContain('t-failed')
    expect(within(screen.getByText('d-failed').closest('tr')!).getByText('Failed').closest('span')).toHaveClass('tone-failed')
  })

  it('a phase without a measurable duration shows no duration, never 0', async () => {
    server.use(http.get(RUN_URL, () => HttpResponse.json(run('ANALYZING', { phase: 'ANALYZING', executions: [exec({ status: 'COMPLETED', durationMs: null })] }))))
    renderProgress()
    const row = (await screen.findByText('x')).closest('tr')!
    expect(row).toHaveTextContent('—')
    expect(row.textContent).not.toMatch(/\b0\b/)
  })

  it('aggregate progress is only measured while analyzers run', () => {
    expect(aggregateProgress(run('INGESTING', { phase: 'INGESTING' }))).toBeNull()
    expect(aggregateProgress(run('ANALYZING', { phase: 'ANALYZING', executions: [] }))).toBeNull()
    expect(aggregateProgress(run('ANALYZING', { phase: 'ANALYZING', executions: [exec({}), exec({ status: 'NOT_ANALYZED' })] }))).toEqual({ done: 1, total: 2 })
  })

  it('executionState distinguishes queued from running and keeps NOT_ANALYZED once the run ended', () => {
    expect(executionState(exec({ status: 'NOT_ANALYZED' }), true)).toBe('QUEUED')
    expect(executionState(exec({ status: 'NOT_ANALYZED', startedAt: 'x' }), true)).toBe('RUNNING')
    expect(executionState(exec({ status: 'NOT_ANALYZED' }), false)).toBe('NOT_ANALYZED')
  })

  it('ticks the elapsed time locally while the run is active', async () => {
    const started = new Date(Date.now() - 5000).toISOString()
    server.use(http.get(RUN_URL, () => HttpResponse.json(run('ANALYZING', { phase: 'ANALYZING', startedAt: started }))))
    renderProgress()
    expect(await screen.findByText(/^[5-9]\.\ds$/)).toBeInTheDocument()
  })

  it('polls while the run is active and stops on a terminal status', async () => {
    const statuses: RunStatus[] = ['QUEUED', 'INGESTING', 'ANALYZING', 'COMPLETED']
    let calls = 0
    server.use(
      http.get(RUN_URL, () => {
        const status = statuses[Math.min(calls, statuses.length - 1)]
        calls++
        return HttpResponse.json(run(status, { phase: status === 'COMPLETED' ? 'FINISHED' : 'ANALYZING' }))
      }),
    )
    renderProgress()

    const tiles = await screen.findByLabelText('Run status')
    await waitFor(() => expect(within(tiles).getByText('Completed')).toBeInTheDocument(), { timeout: 3000 })
    const callsAtCompletion = calls
    expect(callsAtCompletion).toBe(4)

    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(calls).toBe(callsAtCompletion)
    expect(screen.getByRole('button', { name: 'Cancel Analysis' })).toBeDisabled()
    expect(screen.getByRole('link', { name: /View report/ })).toHaveAttribute('href', expect.stringContaining('/repositories/'))
  })

  it('failed: shows the reason and Retry resubmits with a forced rescan', async () => {
    let body: unknown
    server.use(
      http.get(RUN_URL, () => HttpResponse.json(run('FAILED', { failureReason: 'Boom happened.', phase: 'FINISHED' }))),
      http.post('/api/v1/repositories/analyze', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ repositoryId: REPO_ID, snapshotId: 's', analysisRunId: 'r2', commitSha: 'c', status: 'QUEUED', reused: false, branch: 'main', warnings: [] }, { status: 202 })
      }),
    )
    renderProgress()
    expect(await screen.findByRole('alert')).toHaveTextContent('Boom happened.')
    await waitFor(() => expect(screen.getByRole('button', { name: 'Retry' })).toBeEnabled())
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await waitFor(() => expect(body).toEqual({ url: 'https://github.com/octo/demo', forceRescan: true }))
  })

  it('rate limited: shows the retry time, keeps Retry disabled until then and tips about the token', async () => {
    const future = new Date(Date.now() + 3_600_000).toISOString()
    server.use(http.get(RUN_URL, () => HttpResponse.json(run('FAILED', { failureReason: 'GitHub API rate limit reached.', retryAfter: future, phase: 'FINISHED' }))))
    renderProgress()
    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent(/GitHub rate limit reached\. Retry after/)
    expect(alert).toHaveTextContent('60 to 5,000 requests per hour')
    expect(alert).toHaveTextContent('You can try again after')
    expect(screen.getByRole('button', { name: 'Retry' })).toBeDisabled()
  })

  it('cancelled: says when it stopped and offers Re-run', async () => {
    server.use(http.get(RUN_URL, () => HttpResponse.json(run('CANCELLED', { completedAt: '2026-11-19T10:05:00Z', phase: 'FINISHED' }))))
    renderProgress()
    expect(await screen.findByText(/This analysis was cancelled at/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Re-run' })).toBeInTheDocument()
  })

  it('cancel asks for confirmation, shows Cancelling... and ignores a dismissed confirmation', async () => {
    let cancelled = false
    let release: () => void = () => {}
    server.use(
      http.get(RUN_URL, () => HttpResponse.json(run(cancelled ? 'CANCELLED' : 'ANALYZING', { phase: 'ANALYZING' }))),
      http.post(`${RUN_URL}/cancel`, async () => {
        await new Promise<void>((resolve) => (release = resolve))
        cancelled = true
        return HttpResponse.json(run('CANCELLED'), { status: 202 })
      }),
    )
    renderProgress()

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel Analysis' }))
    await userEvent.click(screen.getByRole('button', { name: 'Keep running' }))
    expect(screen.getByRole('button', { name: 'Cancel Analysis' })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Cancel Analysis' }))
    await userEvent.click(screen.getByRole('button', { name: 'Yes, cancel' }))
    expect(await screen.findByRole('button', { name: 'Cancelling…' })).toBeDisabled()
    release()
    await waitFor(() => expect(screen.getByText(/This analysis was cancelled/)).toBeInTheDocument())
  })
})
