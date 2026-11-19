import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { RUN_ID, run } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { RunStatus } from '../../types/api'
import AnalysisProgress from './AnalysisProgress'

const RUN_URL = `/api/v1/analysis-runs/${RUN_ID}`

function renderProgress() {
  return renderPage(<AnalysisProgress runId={RUN_ID} pollIntervalMs={25} />)
}

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

  it.each(cases)('renders the %s status', async (status, label) => {
    server.use(http.get(RUN_URL, () => HttpResponse.json(run(status))))
    renderProgress()

    expect(await screen.findByText(label)).toBeInTheDocument()
  })

  it('shows the phase and the status of each analyzer', async () => {
    server.use(
      http.get(RUN_URL, () =>
        HttpResponse.json(
          run('ANALYZING', {
            phase: 'ANALYZING',
            executions: [
              { analyzerId: 'manifest-technology-detector', category: 'TECHNOLOGY', status: 'COMPLETED', startedAt: null, completedAt: null, durationMs: 5, errorSummary: null },
              { analyzerId: 'generic-structure-analyzer', category: 'STRUCTURE', status: 'NOT_ANALYZED', startedAt: null, completedAt: null, durationMs: null, errorSummary: null },
            ],
          }),
        ),
      ),
    )
    renderProgress()

    expect(await screen.findByText('Running analyzers')).toBeInTheDocument()
    expect(screen.getByText('manifest-technology-detector')).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()
    expect(screen.getByText('Waiting')).toBeInTheDocument()
  })

  it('polls while the run is active and stops on a terminal status', async () => {
    const statuses: RunStatus[] = ['QUEUED', 'INGESTING', 'ANALYZING', 'COMPLETED']
    let calls = 0
    server.use(
      http.get(RUN_URL, () => {
        const status = statuses[Math.min(calls, statuses.length - 1)]
        calls++
        return HttpResponse.json(run(status))
      }),
    )
    renderProgress()

    expect(await screen.findByText('Completed', {}, { timeout: 3000 })).toBeInTheDocument()
    const callsAtCompletion = calls
    expect(callsAtCompletion).toBe(4)

    await new Promise((resolve) => setTimeout(resolve, 200))
    expect(calls).toBe(callsAtCompletion)
    expect(screen.queryByRole('button', { name: 'Cancel' })).not.toBeInTheDocument()
  })

  it('shows the failure reason and the rate-limit reset time', async () => {
    server.use(
      http.get(RUN_URL, () =>
        HttpResponse.json(
          run('FAILED', {
            failureReason: 'GitHub API rate limit reached.',
            retryAfter: '2026-11-19T16:52:19Z',
            phase: 'FINISHED',
          }),
        ),
      ),
    )
    renderProgress()

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('GitHub API rate limit reached.')
    expect(alert).toHaveTextContent('You can try again after')
  })

  it('cancels an active run', async () => {
    let cancelled = false
    server.use(
      http.get(RUN_URL, () => HttpResponse.json(run(cancelled ? 'CANCELLED' : 'ANALYZING', { phase: 'ANALYZING' }))),
      http.post(`${RUN_URL}/cancel`, () => {
        cancelled = true
        return HttpResponse.json(run('CANCELLED'), { status: 202 })
      }),
    )
    renderProgress()

    await userEvent.click(await screen.findByRole('button', { name: 'Cancel' }))

    await waitFor(() => expect(screen.getByText('This analysis was cancelled.')).toBeInTheDocument())
  })
})
