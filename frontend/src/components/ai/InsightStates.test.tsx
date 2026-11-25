import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { REPO_ID, SNAP_NEW, aiProvidersEnabled, insightOk } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { Insight } from '../../types/api'
import InsightPanel from './InsightPanel'
import InsightResult from './InsightResult'

const INSIGHTS = `/api/v1/repositories/${REPO_ID}/snapshots/${SNAP_NEW}/insights`
const failed = (status: Insight['status'], reason: string): Insight => ({ ...insightOk, status, aiGenerated: false, explanation: null, structured: null, evidence: [], reason })

const view = (insight: Insight, extra: Partial<Parameters<typeof InsightResult>[0]> = {}) =>
  renderPage(<InsightResult insight={insight} {...extra} />)

function enable(answer: Insight | (() => Promise<Insight>) = insightOk) {
  server.use(
    http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)),
    http.post(INSIGHTS, async () => HttpResponse.json(typeof answer === 'function' ? await answer() : answer)),
  )
}

describe('insight card: success variants', () => {
  it('numbers the sections, shows cached / fallback / truncated notes and the missing-evidence callout', () => {
    view({
      ...insightOk,
      fromCache: true,
      fallback: true,
      structured: { ...(insightOk.structured ?? {}), missingEvidence: ['callers of OrderService'] },
      evidence: insightOk.evidence.map((e, i) => ({ ...e, truncated: i === 0 })),
    })
    const card = screen.getByRole('article', { name: 'AI insight' })
    expect(within(card).getByText(/01 Explanation/)).toBeInTheDocument()
    expect(within(card).getByText(/Served from cache: no model call was made/)).toBeInTheDocument()
    expect(within(card).getByText(/did not answer; this was answered by/)).toBeInTheDocument()
    expect(within(card).getByText(/exceeded the model's limit/)).toBeInTheDocument()
    const missing = within(card).getByRole('region', { name: 'Missing evidence' })
    expect(missing).toHaveTextContent('callers of OrderService')
    expect(within(card).getByRole('button', { name: 'Copy answer' })).toBeInTheDocument()
  })

  it('renders a fenced diff with coloured lines and a Copy diff button, never HTML', () => {
    const { container } = view({ ...insightOk, explanation: 'Fix:\n\n```diff\n- old line\n+ new line\n```\n\n<script>x</script>' })
    expect(screen.getByRole('button', { name: /Copy diff/ })).toBeInTheDocument()
    const added = screen.getByText('+ new line').closest('div')!
    const removed = screen.getByText('- old line').closest('div')!
    expect(added.className).toContain('t-success')
    expect(removed.className).toContain('t-failed')
    expect(container.querySelector('script')).toBeNull()
  })
})

describe('insight card: failures (plain reason, one sensible action)', () => {
  it('timed out offers Try again', async () => {
    let retried = false
    view(failed('TIMEOUT', 'No answer within 60s.'), { onRetry: () => (retried = true) })
    expect(screen.getByText('The AI provider did not answer in time')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Try again' }))
    expect(retried).toBe(true)
  })

  it('rate-limited offers Use local Ollama only when a local provider is permitted', async () => {
    let used = false
    const { unmount } = view(failed('RATE_LIMITED', 'Quota exceeded.'))
    expect(screen.queryByRole('button', { name: 'Use local Ollama' })).toBeNull()
    unmount()
    view(failed('RATE_LIMITED', 'Quota exceeded.'), { onUseLocal: () => (used = true) })
    await userEvent.click(screen.getByRole('button', { name: 'Use local Ollama' }))
    expect(used).toBe(true)
  })

  it('a refused connection shows the ollama serve command and Check again, in the failed style', () => {
    view(failed('ERROR', 'Connection refused'), { onRetry: () => {} })
    expect(screen.getByText('The AI provider failed')).toBeInTheDocument()
    expect(screen.getByText(/ollama serve/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Check again' })).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveClass('tone-failed')
  })

  it('an invalid answer is not shown and offers Try again; failures other than ERROR are not red', () => {
    view(failed('INVALID_OUTPUT', 'not valid JSON'), { onRetry: () => {} })
    expect(screen.getByText('The model returned an invalid answer, so it is not shown')).toBeInTheDocument()
    expect(screen.getByRole('status')).toHaveClass('tone-warning')
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument()
  })

  it('skipped explains the call cap and links to AI Settings', () => {
    view(failed('SKIPPED', 'cap'))
    expect(screen.getByText(/Call limit for this analysis reached/)).toBeInTheDocument()
    expect(screen.getByText('AI_MAX_CALLS_PER_ANALYSIS')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'AI Settings' })).toHaveAttribute('href', '/settings/ai')
  })
})

describe('insight trigger: availability, asking and cancel', () => {
  it('shows a checking state while the AI status loads', () => {
    server.use(http.get('/api/v1/ai/providers', () => new Promise(() => {})))
    renderPage(<InsightPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} type="EXPLAIN_CLASS" targetId="s" />)
    expect(screen.getByText('Checking whether AI is available…')).toBeInTheDocument()
  })

  it('Cancel abandons the request and ignores a late answer', async () => {
    let release: (value: Insight) => void = () => {}
    enable(() => new Promise<Insight>((resolve) => (release = resolve)))
    renderPage(<InsightPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} type="EXPLAIN_CLASS" targetId="s" />)
    const button = await screen.findByRole('button', { name: 'Explain' })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)
    expect(await screen.findByRole('status', { name: 'Asking the model' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Cancel' }))
    release(insightOk)
    await new Promise((r) => setTimeout(r, 100))
    expect(screen.queryByRole('article', { name: 'AI insight' })).toBeNull()
    expect(screen.queryByRole('status', { name: 'Asking the model' })).toBeNull()
  })

  it('with AI on, no call is made until the button is clicked', async () => {
    let calls = 0
    server.use(
      http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)),
      http.post(INSIGHTS, () => {
        calls++
        return HttpResponse.json(insightOk)
      }),
    )
    renderPage(<InsightPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} type="EXPLAIN_CLASS" targetId="s" />)
    await waitFor(() => expect(screen.getByRole('button', { name: 'Explain' })).toBeEnabled())
    expect(calls).toBe(0)
  })
})
