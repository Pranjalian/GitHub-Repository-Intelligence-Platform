import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import ArchitectureView from '../architecture/ArchitectureView'
import { REPO_ID, SNAP_NEW, aiProvidersEnabled, architectureReport, insightOk } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { ArchitectureReport, CodeSymbol, InsightRequest } from '../../types/api'

vi.mock('../../lib/mermaid', () => ({ getMermaid: () => Promise.resolve({ run: () => Promise.resolve() }) }))

const BASE = `/api/v1/repositories/${REPO_ID}/snapshots/${SNAP_NEW}`

const withSymbolIds: ArchitectureReport = {
  ...architectureReport,
  layers: architectureReport.layers.map((group) => ({
    ...group,
    classes: group.classes.map((cls) => ({ ...cls, symbolId: `sym-${cls.name}` })),
  })),
}

const orderService: CodeSymbol = {
  id: 'sym-OrderService',
  type: 'CLASS',
  name: 'OrderService',
  fullyQualifiedName: 'com.example.shop.service.OrderService',
  signature: null,
  filePath: 'src/main/java/com/example/shop/service/OrderService.java',
  startLine: 12,
  endLine: 60,
  sourceUrl: null,
  annotations: ['Service'],
  members: [
    { id: 'm-create', type: 'METHOD', name: 'create(CreateOrderRequest)', fullyQualifiedName: 'com.example.shop.service.OrderService#create(CreateOrderRequest)', signature: 'create(CreateOrderRequest)', filePath: 'x', startLine: 30, endLine: 40, sourceUrl: null, annotations: [], members: [] },
    { id: 'f-orders', type: 'FIELD', name: 'orders', fullyQualifiedName: 'com.example.shop.service.OrderService#orders', signature: 'OrderRepository', filePath: 'x', startLine: 14, endLine: 14, sourceUrl: null, annotations: [], members: [] },
  ],
}

describe('AI insights on the architecture page', () => {
  it('says AI is unavailable above the layers when it is disabled', async () => {
    renderPage(<ArchitectureView report={withSymbolIds} />)

    expect(await screen.findByRole('note')).toHaveTextContent('AI explanations are unavailable: AI insights are unavailable: AI is disabled')
  })

  it('explains a class and one of its methods', async () => {
    const requests: InsightRequest[] = []
    server.use(
      http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)),
      http.get(`${BASE}/symbols/sym-OrderService`, () => HttpResponse.json(orderService)),
      http.post(`${BASE}/insights`, async ({ request }) => {
        requests.push((await request.json()) as InsightRequest)
        return HttpResponse.json(insightOk)
      }),
    )
    renderPage(<ArchitectureView report={withSymbolIds} />)

    await userEvent.click(await screen.findByRole('button', { name: 'AI insights for OrderService' }))
    const panel = screen.getByLabelText('AI insights for OrderService', { selector: 'div' })
    const explain = within(panel).getByRole('button', { name: 'Explain class' })
    await waitFor(() => expect(explain).toBeEnabled())
    await userEvent.click(explain)
    expect(await within(panel).findByText('AI-generated')).toBeInTheDocument()

    await userEvent.click(within(panel).getByRole('button', { name: 'Methods' }))
    const methods = await within(panel).findByRole('list', { name: 'Methods of OrderService' })
    expect(within(methods).getByText('create(CreateOrderRequest)')).toBeInTheDocument()
    expect(within(methods).queryByText('orders')).toBeNull()
    await userEvent.click(within(methods).getByRole('button', { name: 'Explain method' }))
    await waitFor(() => expect(requests).toHaveLength(2))
    expect(requests).toEqual([
      { type: 'EXPLAIN_CLASS', targetId: 'sym-OrderService', provider: null },
      { type: 'EXPLAIN_METHOD', targetId: 'm-create', provider: null },
    ])
  })
})
