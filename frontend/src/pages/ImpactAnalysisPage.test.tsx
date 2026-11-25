import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { REPO_ID, SHA_NEW, SNAP_NEW, aiProvidersDisabled, aiProvidersEnabled, report, repository, snapshot } from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { ImpactAnalysis, ImpactRequest, SymbolPickerResponse, TargetSuggestions } from '../types/api'
import ImpactAnalysisPage from './ImpactAnalysisPage'

const BASE = `/api/v1/repositories/${REPO_ID}`
const SNAPSHOT = `${BASE}/snapshots/${SNAP_NEW}`
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })
const route = { path: '/repositories/:repositoryId/impact', initialEntry: `/repositories/${REPO_ID}/impact?snapshot=${SNAP_NEW}` }

const TARGET_ID = '33333333-3333-3333-3333-333333333333'
const CALLER_ID = '44444444-4444-4444-4444-444444444444'
const CONTROLLER_ID = '55555555-5555-5555-5555-555555555555'

const pickerOk: SymbolPickerResponse = {
  status: 'OK',
  reason: null,
  query: '',
  symbols: [
    {
      id: TARGET_ID,
      kind: 'METHOD',
      name: 'PaymentService.calculatePayment(Order order)',
      qualifiedName: 'com.example.pay.service.PaymentService#calculatePayment(Order order)',
      filePath: 'src/main/java/com/example/pay/service/PaymentService.java',
      line: 22,
      layer: 'SERVICE',
    },
  ],
}

function impact(extra: Partial<ImpactAnalysis> = {}): ImpactAnalysis {
  return {
    repositoryId: REPO_ID,
    snapshotId: SNAP_NEW,
    runId: '66666666-6666-6666-6666-666666666666',
    commitSha: SHA_NEW,
    status: 'OK',
    reason: null,
    target: {
      id: TARGET_ID,
      kind: 'METHOD',
      name: 'PaymentService.calculatePayment(Order order)',
      qualifiedName: 'com.example.pay.service.PaymentService#calculatePayment(Order order)',
      layer: 'SERVICE',
      filePath: 'src/main/java/com/example/pay/service/PaymentService.java',
      startLine: 22,
      endLine: 28,
      sourceUrl: 'https://github.com/acme/pay/blob/abc/src/main/java/com/example/pay/service/PaymentService.java#L22-L28',
      modulePath: null,
    },
    depth: 3,
    components: [
      {
        id: CALLER_ID,
        kind: 'METHOD',
        name: 'OrderService.checkout(Order order)',
        qualifiedName: 'com.example.pay.service.OrderService#checkout(Order order)',
        layer: 'SERVICE',
        depth: 1,
        viaId: TARGET_ID,
        relationships: ['CALLS'],
        confidence: 'DETECTED',
        reason: 'Calls the component one step closer to the target (OrderService.java:12).',
        filePath: 'src/main/java/com/example/pay/service/OrderService.java',
        line: 12,
        sourceUrl: 'https://github.com/acme/pay/blob/abc/src/main/java/com/example/pay/service/OrderService.java#L12',
        modulePath: null,
      },
      {
        id: CONTROLLER_ID,
        kind: 'METHOD',
        name: 'OrderController.place(Order order)',
        qualifiedName: 'com.example.pay.web.OrderController#place(Order order)',
        layer: 'CONTROLLER',
        depth: 2,
        viaId: CALLER_ID,
        relationships: ['CALLS'],
        confidence: 'POSSIBLE',
        reason: 'Calls the component one step closer to the target. The path goes through dynamic dispatch or an ambiguous call.',
        filePath: 'src/main/java/com/example/pay/web/OrderController.java',
        line: 14,
        sourceUrl: null,
        modulePath: null,
      },
    ],
    endpoints: [
      {
        httpMethod: 'POST',
        route: '/orders',
        routeResolved: true,
        controller: 'com.example.pay.web.OrderController',
        handler: 'OrderController.place(Order order)',
        confidence: 'POSSIBLE',
        filePath: 'src/main/java/com/example/pay/web/OrderController.java',
        line: 13,
        sourceUrl: null,
        viaId: CONTROLLER_ID,
      },
    ],
    relatedData: [
      {
        kind: 'REPOSITORY',
        id: '77777777-0000-0000-0000-000000000001',
        qualifiedName: 'com.example.pay.repository.PaymentRepository',
        relationship: 'used by PaymentService.calculatePayment(Order order) (calls)',
        via: 'PaymentService.calculatePayment(Order order)',
        confidence: 'DETECTED',
        filePath: null,
        line: null,
        sourceUrl: null,
      },
    ],
    modules: [],
    tests: [
      {
        testSymbol: 'com.example.pay.service.PaymentServiceTests',
        filePath: 'src/test/java/com/example/pay/service/PaymentServiceTests.java',
        methods: ['computesTheTotal'],
        relation: 'DIRECT_CALL',
        confidence: 'DETECTED',
        line: 18,
        sourceUrl: null,
        via: null,
      },
    ],
    regressionAreas: [
      { kind: 'ENDPOINT', area: 'POST /orders', reason: 'Its handler reaches the target.', confidence: 'POSSIBLE' },
      { kind: 'GAP', area: 'No test names X', reason: 'Check by hand.', confidence: 'DETECTED' },
    ],
    risk: {
      label: 'Heuristic risk score (0-100). It adds up the factors below; it is not a probability.',
      score: 37,
      maxScore: 100,
      level: 'MEDIUM',
      factors: [
        { id: 'DEPENDENCY_DEPTH', label: 'Dependency depth', value: '2 hops', points: 10, maxPoints: 15, explanation: 'The change can travel 2 steps.' },
        { id: 'DEPENDENT_COUNT', label: 'Number of dependents', value: '2', points: 5, maxPoints: 25, explanation: 'Affected methods found.' },
      ],
      note: 'Based on static analysis only.',
    },
    limitations: ['Reflection, runtime proxies and AOP are not seen.', 'Dynamic dispatch is approximated and labelled POSSIBLE.'],
    truncated: false,
    totalDependents: 2,
    diagram: { chain: null, note: null },
    aiSummary: null,
    ...extra,
  }
}

function stub(options: { aiEnabled: boolean; picker?: SymbolPickerResponse; result?: ImpactAnalysis; suggestions?: TargetSuggestions }) {
  const requests: ImpactRequest[] = []
  server.use(
    http.get(BASE, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]))),
    http.get(`${BASE}/reports/${SNAP_NEW}`, () => HttpResponse.json(report(SNAP_NEW, SHA_NEW))),
    http.get(`${SNAPSHOT}/impact-analysis/symbols`, () => HttpResponse.json(options.picker ?? pickerOk)),
    http.post(`${SNAPSHOT}/impact-analysis`, async ({ request }) => {
      requests.push((await request.json()) as ImpactRequest)
      return HttpResponse.json(options.result ?? impact())
    }),
    http.post(`${SNAPSHOT}/impact-analysis/suggestions`, () => HttpResponse.json(options.suggestions)),
    http.get('/api/v1/ai/providers', () => HttpResponse.json(options.aiEnabled ? aiProvidersEnabled : aiProvidersDisabled)),
  )
  return requests
}

async function pickAndAnalyze(label = 'Analyze impact') {
  await userEvent.click(await screen.findByRole('button', { name: /PaymentService\.calculatePayment/ }))
  await userEvent.click(screen.getByRole('button', { name: label }))
}

describe('Impact analysis page', () => {
  it('picks a symbol, sends it with the depth, and shows the tree, endpoints, tests, risk factors and limitations', async () => {
    const requests = stub({ aiEnabled: false })
    renderPage(<ImpactAnalysisPage />, route)

    expect(await screen.findByRole('button', { name: 'Analyze impact' })).toBeDisabled()
    await userEvent.click(await screen.findByRole('radio', { name: 'Full (6)' }))
    await pickAndAnalyze()

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ targetSymbolId: TARGET_ID, depth: 6, includeAiSummary: false, changeDescription: null })

    const components = await screen.findByRole('region', { name: 'Affected components' })
    expect(within(components).getByText('OrderService.checkout(Order order)')).toBeInTheDocument()
    expect(within(components).getAllByText('Detected')[0]).toBeInTheDocument()
    // the controller is nested under the service it calls, with its weaker confidence and a source link only when one exists
    const nested = within(components).getByRole('button', { name: 'Inspect OrderController.place(Order order)' }).closest('li')
    expect(nested?.parentElement?.closest('li')).toHaveTextContent('OrderService.checkout')
    expect(within(nested as HTMLElement).getByText('Possible')).toBeInTheDocument()
    expect(within(components).getByRole('link', { name: /OrderService\.java:12/ })).toHaveAttribute('href', expect.stringContaining('#L12'))

    expect(within(screen.getByRole('region', { name: 'Endpoints' })).getByText('/orders')).toBeInTheDocument()
    expect(within(screen.getByRole('region', { name: 'Tests' })).getByText('com.example.pay.service.PaymentServiceTests')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Tests' })).toHaveTextContent('calls the target')
    expect(screen.getByRole('region', { name: 'Suggested regression areas' })).toHaveTextContent('POST /orders')

    const risk = screen.getByRole('region', { name: 'Risk factors' })
    expect(within(risk).getByTestId('risk-score')).toHaveTextContent('37')
    expect(within(risk).getByText('Heuristic risk score, not a probability.')).toBeInTheDocument()
    expect(within(risk).getByText('Dependency depth')).toBeInTheDocument()
    expect(within(risk).getByText(/10 \/ 15/)).toBeInTheDocument()
    expect(within(risk).getByText('MEDIUM')).toBeInTheDocument()

    const limitations = screen.getByRole('region', { name: 'Limitations' })
    expect(limitations).toHaveTextContent('Reflection, runtime proxies and AOP')
    expect(screen.queryByText('AI-generated')).not.toBeInTheDocument()
  })

  it('hides only the description input when AI is unavailable: the picker and the analysis stay', async () => {
    stub({ aiEnabled: false })
    renderPage(<ImpactAnalysisPage />, route)

    expect(await screen.findByRole('search')).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: /PaymentService\.calculatePayment/ })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Analyze impact' })).toBeInTheDocument()
    expect(screen.getByText('Change description needs AI, which is unavailable. Pick a symbol instead.')).toBeInTheDocument()
    await waitFor(() => expect(screen.getByText(/AI explanations are unavailable/)).toBeInTheDocument())
    expect(screen.queryByLabelText('Change description')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Suggest symbols' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /summarize with AI/ })).not.toBeInTheDocument()
  })

  it('with AI available, suggests symbols for a description, needs a confirmation, and marks the summary as AI-generated', async () => {
    const requests = stub({
      aiEnabled: true,
      suggestions: {
        status: 'OK',
        aiGenerated: true,
        detail: null,
        provider: 'ollama',
        model: 'llama3.2',
        promptVersion: 'suggest-impact-targets.v1',
        warnings: [],
        suggestions: [
          {
            symbolId: TARGET_ID,
            kind: 'METHOD',
            name: 'PaymentService.calculatePayment(Order order)',
            qualifiedName: 'com.example.pay.service.PaymentService#calculatePayment(Order order)',
            filePath: 'src/main/java/com/example/pay/service/PaymentService.java',
            line: 22,
            reason: 'It computes the totals.',
          },
        ],
      },
      result: impact({
        aiSummary: {
          status: 'OK',
          aiGenerated: true,
          summary: 'Changing it **affects** the order flow.',
          keyPoints: ['Two callers'],
          regressionFocus: ['POST /orders'],
          provider: 'ollama',
          model: 'llama3.2',
          promptVersion: 'summarize-impact.v1',
          fromCache: false,
          detail: null,
          warnings: [],
          generatedAt: null,
        },
      }),
    })
    renderPage(<ImpactAnalysisPage />, route)

    await userEvent.click(await screen.findByLabelText('Change description'))
    await userEvent.paste('round totals to whole cents')
    await userEvent.click(screen.getByRole('button', { name: 'Suggest symbols' }))
    const suggested = await screen.findByRole('list', { name: 'Suggested symbols' })
    expect(within(suggested).getByText('It computes the totals.')).toBeInTheDocument()
    // nothing is analysed until a person confirms and runs it
    expect(requests).toHaveLength(0)
    await userEvent.click(within(suggested).getByRole('button', { name: 'Use this symbol' }))
    await userEvent.click(screen.getByRole('button', { name: 'Analyze and summarize with AI' }))

    await waitFor(() => expect(requests).toHaveLength(1))
    expect(requests[0]).toMatchObject({ includeAiSummary: true, changeDescription: 'round totals to whole cents' })
    const summary = await screen.findByRole('region', { name: 'AI summary' })
    expect(within(summary).getByText('AI-generated')).toBeInTheDocument()
    expect(within(summary).getByText('affects')).toBeInTheDocument()
    expect(within(summary).getByText('Two callers')).toBeInTheDocument()
  })

  it('explains an unavailable summary without touching the analysis', async () => {
    stub({
      aiEnabled: true,
      result: impact({
        aiSummary: {
          status: 'UNAVAILABLE',
          aiGenerated: false,
          summary: null,
          keyPoints: [],
          regressionFocus: [],
          provider: null,
          model: null,
          promptVersion: 'summarize-impact.v1',
          fromCache: false,
          detail: 'No permitted provider.',
          warnings: [],
          generatedAt: null,
        },
      }),
    })
    renderPage(<ImpactAnalysisPage />, route)

    await pickAndAnalyze('Analyze and summarize with AI')

    expect(await screen.findByText(/AI summary unavailable: No permitted provider\./)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Affected components' })).toBeInTheDocument()
    expect(screen.queryByText('AI-generated')).not.toBeInTheDocument()
  })

  it('says so for a repository without a symbol graph', async () => {
    stub({
      aiEnabled: false,
      picker: { status: 'NOT_SUPPORTED', reason: 'Impact analysis needs the symbol graph of the architecture analysis, which exists for Java and Spring repositories only.', query: '', symbols: [] },
    })
    renderPage(<ImpactAnalysisPage />, route)

    expect(await screen.findByText(/exists for Java and Spring repositories only/)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Analyze impact' })).toBeDisabled()
  })

  it('view filters hide items without changing the underlying result, and Export JSON equals the response', async () => {
    const result = impact()
    stub({ aiEnabled: false, result })
    let exported = ''
    const originalCreate = URL.createObjectURL
    URL.createObjectURL = (blob: Blob) => {
      void blob.text().then((t) => (exported = t))
      return 'blob:test'
    }
    URL.revokeObjectURL = () => {}
    try {
      renderPage(<ImpactAnalysisPage />, route)
      await pickAndAnalyze()
      await screen.findByRole('region', { name: 'Tests' })
      expect(screen.getByRole('region', { name: 'Endpoints' })).toHaveTextContent('/orders')

      await userEvent.click(screen.getByLabelText('Include test callers'))
      expect(screen.getByRole('region', { name: 'Tests' })).toHaveTextContent('No test was linked')
      await userEvent.click(screen.getByLabelText('HTTP endpoint ingress'))
      expect(screen.getByRole('region', { name: 'Endpoints' })).toHaveTextContent('No REST endpoint handler')
      await userEvent.click(screen.getByLabelText('Include test callers'))
      expect(screen.getByRole('region', { name: 'Tests' })).toHaveTextContent('PaymentServiceTests')

      await userEvent.click(screen.getByRole('button', { name: 'Export JSON' }))
      await waitFor(() => expect(exported).not.toBe(''))
      expect(JSON.parse(exported)).toEqual(JSON.parse(JSON.stringify(result)))
    } finally {
      URL.createObjectURL = originalCreate
    }
  })

  it('tabs show counts and filter the groups; the tests table has no execution status and the command is text only', async () => {
    stub({ aiEnabled: false })
    server.use(http.get(`${BASE}/reports/${SNAP_NEW}`, () => HttpResponse.json({ ...report(SNAP_NEW, SHA_NEW), technologies: [{ name: 'Maven', kind: 'BUILD_TOOL', version: null, confidence: 'DETECTED', sharePercent: null, detectorId: 'x', evidence: [] }] })))
    renderPage(<ImpactAnalysisPage />, route)
    await pickAndAnalyze()
    const region = await screen.findByRole('region', { name: 'Affected components' })
    expect(within(region).getByRole('tab', { name: /Routes/ })).toHaveTextContent('1')
    expect(within(region).getByRole('tab', { name: /Callers/ })).toHaveTextContent('2')
    await userEvent.click(within(region).getByRole('tab', { name: /Routes/ }))
    expect(within(region).queryByText('OrderService.checkout(Order order)')).toBeNull()

    const tests = screen.getByRole('region', { name: 'Tests' })
    expect(within(tests).getByRole('table')).not.toHaveTextContent(/passing|failed|run test/i)
    expect(within(tests).queryByRole('button', { name: /Run/ })).toBeNull()
    expect(tests).toHaveTextContent('Suggested command (not run by RepoIntel)')
    expect(tests).toHaveTextContent('./mvnw test -Dtest=PaymentServiceTests')
    expect(tests).toHaveTextContent('1 detected')
  })

  it('a negative risk factor is labelled as reducing risk, and the band bar and permanent notice are present', async () => {
    stub({
      aiEnabled: false,
      result: impact({
        risk: {
          label: 'Heuristic',
          score: 72,
          maxScore: 100,
          level: 'HIGH',
          factors: [
            { id: 'A', label: 'Public API exposure', value: 'yes', points: 20, maxPoints: 20, explanation: 'exposed' },
            { id: 'B', label: 'Test dampener', value: '3 tests', points: -12, maxPoints: 15, explanation: 'tests exist' },
          ],
          note: 'static only',
        },
      }),
    })
    renderPage(<ImpactAnalysisPage />, route)
    await pickAndAnalyze()
    const risk = await screen.findByRole('region', { name: 'Risk factors' })
    expect(within(risk).getByText('HIGH')).toBeInTheDocument()
    expect(within(risk).getByText('Heuristic risk score, not a probability.')).toBeInTheDocument()
    expect(within(risk).getByText('reduces risk')).toBeInTheDocument()
    expect(within(risk).getByLabelText(/Risk bands/)).toBeInTheDocument()
  })

  it('says nothing depends on the symbol when there are no dependents', async () => {
    stub({ aiEnabled: false, result: impact({ components: [], endpoints: [], relatedData: [], tests: [], totalDependents: 0 }) })
    renderPage(<ImpactAnalysisPage />, route)
    await pickAndAnalyze()
    expect(await screen.findByText('Nothing depends on this symbol in this snapshot.')).toBeInTheDocument()
  })
})
