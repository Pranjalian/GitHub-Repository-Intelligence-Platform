import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import FindingsList from '../findings/FindingsList'
import { REPO_ID, SHA_NEW, SNAP_NEW, aiProvidersEnabled, insightOk } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { Finding, Insight, InsightRequest } from '../../types/api'
import InsightPanel from './InsightPanel'

const INSIGHTS = `/api/v1/repositories/${REPO_ID}/snapshots/${SNAP_NEW}/insights`

function aiEnabled(answer: Insight = insightOk) {
  const requests: InsightRequest[] = []
  server.use(
    http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)),
    http.post(INSIGHTS, async ({ request }) => {
      requests.push((await request.json()) as InsightRequest)
      return HttpResponse.json(answer)
    }),
  )
  return requests
}

const panel = (type: InsightRequest['type'] = 'EXPLAIN_CLASS', targetId: string | null = 'sym-1') => (
  <InsightPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} type={type} targetId={targetId} />
)

describe('InsightPanel', () => {
  it('requests the insight on click and shows it with the AI badge, provider and evidence links', async () => {
    const requests = aiEnabled()
    renderPage(panel())

    const button = await screen.findByRole('button', { name: 'Explain' })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)

    const article = await screen.findByRole('article', { name: 'AI insight' })
    expect(requests).toEqual([{ type: 'EXPLAIN_CLASS', targetId: 'sym-1', provider: null }])
    expect(within(article).getByText('AI-generated')).toBeInTheDocument()
    expect(within(article).getByText(/ollama · llama3\.2 · prompt explain-class\.v1/)).toBeInTheDocument()
    expect(within(article).getByText('Uses OrderRepository')).toBeInTheDocument()
    expect(within(article).getByText('orElseThrow without a message')).toBeInTheDocument()
    const evidence = within(article).getByRole('region', { name: 'Source evidence' })
    const links = within(evidence).getAllByRole('link')
    expect(links[0]).toHaveAttribute('href', `https://github.com/acme/shop/blob/${SHA_NEW}/src/main/java/OrderService.java#L12-L60`)
    expect(within(evidence).getByText('truncated')).toBeInTheDocument()
    expect(within(evidence).getByText(/1 line\(s\)/)).toBeInTheDocument()
  })

  it('renders Markdown but never raw HTML, images, scripts or model-supplied links', async () => {
    aiEnabled()
    const { container } = renderPage(panel())

    const button = await screen.findByRole('button', { name: 'Explain' })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)
    const article = await screen.findByRole('article', { name: 'AI insight' })

    expect(within(article).getByText('OrderService', { selector: 'strong' })).toBeInTheDocument()
    expect(container.querySelector('img')).toBeNull()
    expect(container.querySelector('script')).toBeNull()
    expect(container.querySelector('a[href^="javascript"]')).toBeNull()
    expect(article.textContent).toContain('click')
    expect(article.innerHTML).not.toContain('evil.example')
  })

  it('is disabled with the reason and a link to the AI settings when AI is unavailable', async () => {
    renderPage(panel())

    // the reason appears in the line beside the button and in the button's tooltip
    expect((await screen.findAllByText(/AI is disabled \(ai\.enabled=false\)/)).length).toBeGreaterThanOrEqual(1)
    expect(screen.getByRole('button', { name: 'Explain' })).toBeDisabled()
    expect(screen.getByRole('link', { name: 'AI settings and setup guide' })).toHaveAttribute('href', '/settings/ai')
  })

  it('shows why there is no answer for a non-OK status and never the content', async () => {
    aiEnabled({
      ...insightOk,
      status: 'INVALID_OUTPUT',
      aiGenerated: false,
      explanation: null,
      structured: null,
      evidence: [],
      reason: 'The model\'s answer was rejected: the answer is not valid JSON.',
    })
    renderPage(panel())

    const button = await screen.findByRole('button', { name: 'Explain' })
    await waitFor(() => expect(button).toBeEnabled())
    await userEvent.click(button)

    expect(await screen.findByText('The model returned an invalid answer, so it is not shown')).toBeInTheDocument()
    expect(screen.getByText(/the answer is not valid JSON/)).toBeInTheDocument()
    expect(screen.queryByText('AI-generated')).toBeNull()
  })

  it('sends the preferred provider stored in this browser', async () => {
    const requests = aiEnabled()
    window.localStorage.setItem('repointel.ai.preferredProvider', 'ollama')
    try {
      renderPage(panel('TECH_DEBT_SUMMARY', null))
      const button = await screen.findByRole('button', { name: 'Explain' })
      await waitFor(() => expect(button).toBeEnabled())
      await userEvent.click(button)
      await screen.findByRole('article', { name: 'AI insight' })
      expect(requests).toEqual([{ type: 'TECH_DEBT_SUMMARY', targetId: null, provider: 'ollama' }])
    } finally {
      window.localStorage.removeItem('repointel.ai.preferredProvider')
    }
  })
})

describe('insight flow from a finding (mock API)', () => {
  const finding: Finding = {
    id: 'f-42',
    severity: 'HIGH',
    confidence: 'LIKELY',
    category: 'CODE_QUALITY',
    title: 'Empty catch block',
    description: 'Empty catch blocks hide errors.',
    filePath: 'src/main/java/A.java',
    startLine: 10,
    endLine: 12,
    module: '.',
    ruleId: 'EmptyCatchBlock',
    analyzerId: 'java-code-analyzer',
    analysisMethod: 'DETERMINISTIC_SCANNER',
    evidence: { excerpt: 'catch (Exception e) {}' },
    sourceUrl: `https://github.com/acme/shop/blob/${SHA_NEW}/src/main/java/A.java#L10-L12`,
    suggestedNextStep: 'Handle or log the exception.',
    fingerprint: 'fp-42',
  }

  it('goes from clicking a finding to an AI explanation of it', async () => {
    const requests = aiEnabled({ ...insightOk, type: 'EXPLAIN_FINDING', targetId: 'f-42', targetLabel: 'Empty catch block' })
    server.use(
      http.get(`/api/v1/repositories/${REPO_ID}/snapshots/${SNAP_NEW}/findings`, () =>
        HttpResponse.json({ items: [finding], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
      ),
    )
    renderPage(<FindingsList repositoryId={REPO_ID} snapshotId={SNAP_NEW} category="CODE_QUALITY" modules={['.']} />)

    await userEvent.click(await screen.findByRole('button', { name: /Empty catch block/ }))
    const detail = screen.getByRole('complementary', { name: 'Finding detail' })
    const explain = within(detail).getByRole('button', { name: 'Explain this finding' })
    await waitFor(() => expect(explain).toBeEnabled())
    await userEvent.click(explain)

    expect(await within(detail).findByText('AI-generated')).toBeInTheDocument()
    expect(requests).toEqual([{ type: 'EXPLAIN_FINDING', targetId: 'f-42', provider: null }])
  })
})
