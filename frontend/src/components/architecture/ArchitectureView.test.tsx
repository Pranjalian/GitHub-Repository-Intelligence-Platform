import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, within } from '@testing-library/react'
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

    const endpoints = screen.getByRole('region', { name: 'REST endpoints' })
    expect(within(endpoints).getAllByRole('row')).toHaveLength(3)
    expect(endpoints).toHaveTextContent('POST/api/ordersOrderController.create(CreateOrderRequest)CreateOrderRequestOrderResponse')
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

    expect(screen.getByRole('heading', { name: 'Architecture analysis is not supported for this technology' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Unsupported technologies' })).toHaveTextContent(
      'TypeScript: no analyzer pack for TypeScript',
    )
    expect(screen.queryByRole('region', { name: 'Layers' })).not.toBeInTheDocument()
    expect(screen.queryByRole('figure')).not.toBeInTheDocument()
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
