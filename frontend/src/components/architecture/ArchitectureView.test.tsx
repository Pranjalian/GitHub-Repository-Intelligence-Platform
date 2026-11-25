import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { describe, expect, it, vi } from 'vitest'
import { architectureReport, unsupportedArchitecture } from '../../test/fixtures'
import { makeQueryClient } from '../../test/utils'
import ArchitectureView from './ArchitectureView'

vi.mock('../../lib/mermaid', () => ({ getMermaid: () => Promise.resolve({ run: () => Promise.resolve() }) }))

// From MVP 3 the layer list shows whether AI explanations are available, which needs a query client.
const renderView = (report = architectureReport) =>
  render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter>
        <ArchitectureView report={report} />
      </MemoryRouter>
    </QueryClientProvider>,
  )

describe('ArchitectureView', () => {
  it('renders every section of a Spring Boot architecture', () => {
    renderView()

    expect(screen.getByRole('region', { name: 'Overview' })).toHaveTextContent('Types27')
    const diagrams = screen.getByRole('region', { name: 'Diagrams' })
    for (const title of ['Layers', 'API flow', 'Entity relationships', 'Modules']) {
      expect(within(diagrams).getByRole('figure', { name: title })).toBeInTheDocument()
    }
    expect(within(diagrams).getByRole('list', { name: 'Diagram notes' })).toHaveTextContent('40 of 120 classes')

    const layers = screen.getByRole('region', { name: 'Layers' })
    expect(within(layers).getByRole('link', { name: 'OrderController' })).toHaveAttribute(
      'href',
      expect.stringContaining('/web/OrderController.java#L15'),
    )
    expect(layers).toHaveTextContent('annotation: @RestController')
    expect(layers).toHaveTextContent('package name only: package com.example.shop.service')
    expect(within(layers).getAllByText('Likely')).toHaveLength(1)
    // the "How" tag of each type row
    expect(within(layers).getAllByText('annotation').length).toBeGreaterThan(0)
    expect(within(layers).getAllByText('package name only').length).toBeGreaterThan(0)

    const endpoints = screen.getByRole('region', { name: 'REST endpoints' })
    expect(within(endpoints).getAllByRole('row')).toHaveLength(3)
    expect(endpoints).toHaveTextContent('POST/api/ordersOrderController.create(CreateOrderRequest)CreateOrderRequestOrderResponse')
    expect(endpoints).toHaveTextContent('2 mapped routes')
    expect(within(endpoints).getByText('path not resolved')).toBeInTheDocument()

    const entities = screen.getByRole('region', { name: 'JPA entities' })
    expect(entities).toHaveTextContent('table orders')
    expect(entities).toHaveTextContent('lines one-to-many OrderLine mappedBy=order')
    expect(entities).toHaveTextContent('Repository: OrderRepository')

    expect(screen.getByRole('region', { name: 'Dependency injection chains' })).toHaveTextContent(
      'OrderController → OrderService → OrderRepository → Order',
    )
    const injections = screen.getByRole('region', { name: 'Injected dependencies' })
    expect(injections).toHaveTextContent('org.springframework.web.client.RestTemplate')
    expect(within(injections).getByText('unresolved')).toBeInTheDocument()

    expect(screen.getByRole('region', { name: 'Module dependencies' })).toHaveTextContent('web → core')
    const cycles = screen.getByRole('region', { name: 'Dependency cycles' })
    expect(within(cycles).getByRole('alert')).toHaveTextContent('AuditService → ReportService (injects')
    const patterns = screen.getByRole('region', { name: 'Design pattern candidates' })
    expect(patterns).toHaveTextContent('strategy')
    expect(within(patterns).getByText('Possible')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Limitations of this architecture analysis' })).toHaveTextContent(
      'no classpath',
    )
  })

  it('shows a clear "not supported" state for non-Java repositories', () => {
    renderView(unsupportedArchitecture)

    expect(screen.getByText('Architecture analysis is not supported for this technology')).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Unsupported technologies' })).toHaveTextContent(
      'TypeScript: no analyzer pack for TypeScript',
    )
    expect(screen.queryByRole('region', { name: 'Layers' })).not.toBeInTheDocument()
    expect(screen.queryByRole('figure')).not.toBeInTheDocument()
  })

  it('endpoints can be filtered by method and text', async () => {
    renderView()
    const endpoints = screen.getByRole('region', { name: 'REST endpoints' })
    await userEvent.selectOptions(within(endpoints).getByLabelText('HTTP method'), 'POST')
    expect(within(endpoints).getAllByRole('row')).toHaveLength(2)
    await userEvent.type(within(endpoints).getByLabelText('Search endpoints'), 'zzz')
    expect(within(endpoints).getAllByRole('row')).toHaveLength(1)
  })

  it('shows "No cycles detected" when there are none, and the unresolved chip points to the limitations', () => {
    renderView({ ...architectureReport, cycles: [] })
    expect(screen.getByRole('region', { name: 'Dependency cycles' })).toHaveTextContent('No cycles detected.')
    expect(screen.getByRole('link', { name: /unresolved or ambiguous edges/ })).toHaveAttribute('href', '#architecture-limitations')
  })

  it('the diagram toolbar is keyboard operable: zoom, fit, full screen (Esc exits) and copy source', async () => {
    renderView()
    const layers = screen.getByRole('figure', { name: 'Layers' })
    const toolbar = within(layers).getByRole('toolbar')
    const canvas = within(layers).getByTestId('mermaid-diagram')
    expect(canvas.style.transform).toContain('scale(1)')
    await userEvent.click(within(toolbar).getByRole('button', { name: 'Zoom in' }))
    expect(canvas.style.transform).toContain('scale(1.25)')
    await userEvent.click(within(toolbar).getByRole('button', { name: 'Fit' }))
    expect(canvas.style.transform).toContain('scale(1)')
    await userEvent.click(within(toolbar).getByRole('button', { name: 'Full screen' }))
    expect(layers.className).toContain('fixed')
    await userEvent.keyboard('{Escape}')
    expect(layers.className).not.toContain('fixed')
    expect(within(toolbar).getByRole('button', { name: 'Copy source' })).toBeInTheDocument()
    expect(within(layers).getByText('Mermaid source')).toBeInTheDocument()
  })

  it('renders repository-derived text as text, never as markup', () => {
    const { container } = renderView({
      ...architectureReport,
      endpoints: [{ ...architectureReport.endpoints[0], route: '/x<img src=x onerror="alert(1)">' }],
    })

    expect(screen.getByRole('region', { name: 'REST endpoints' })).toHaveTextContent('/x<img src=x onerror="alert(1)">')
    expect(container.querySelector('img')).toBeNull()
  })
})
