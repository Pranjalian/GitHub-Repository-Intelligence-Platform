import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import {
  REPO_ID,
  SHA_NEW,
  SNAP_NEW,
  architectureReport,
  matrix,
  report,
  repository,
  snapshot,
  unsupportedArchitecture,
} from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { ArchitectureReport, ReportSection } from '../types/api'
import ArchitecturePage from './ArchitecturePage'
import RepositoryOverviewPage from './RepositoryOverviewPage'

vi.mock('../lib/mermaid', () => ({ getMermaid: () => Promise.resolve({ run: () => Promise.resolve() }) }))

const BASE = `/api/v1/repositories/${REPO_ID}`

const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })

function stub(architecture: ArchitectureReport, sections: ReportSection[] = []) {
  const requests: string[] = []
  server.use(
    http.get(BASE, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]))),
    http.get(`${BASE}/reports/${SNAP_NEW}`, () =>
      HttpResponse.json({ ...report(SNAP_NEW, SHA_NEW), sections, capabilityMatrix: matrix }),
    ),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/architecture`, ({ request }) => {
      requests.push(new URL(request.url).pathname)
      return HttpResponse.json(architecture)
    }),
  )
  return requests
}

const section = (category: ReportSection['category'], status: ReportSection['status']): ReportSection => ({
  category,
  status,
  analyzers: [],
  findingCountsBySeverity: null,
  summary: '',
})

describe('architecture page', () => {
  it('shows the Spring Boot architecture of the selected snapshot', async () => {
    const requests = stub(architectureReport)
    renderPage(<ArchitecturePage />, {
      path: '/repositories/:repositoryId/arch',
      initialEntry: `/repositories/${REPO_ID}/arch?snapshot=${SNAP_NEW}`,
    })

    expect(await screen.findByRole('heading', { name: 'Architecture of octo/demo' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'REST endpoints' })).toHaveTextContent('/api/orders')
    expect(screen.getAllByRole('figure')).toHaveLength(4)
    expect(screen.getByRole('region', { name: 'Dependency injection chains' })).toHaveTextContent('OrderService')
    expect(requests).toEqual([`${BASE}/snapshots/${SNAP_NEW}/architecture`])
    expect(screen.getByRole('link', { name: 'Full report' })).toHaveAttribute('href', `/repositories/${REPO_ID}?snapshot=${SNAP_NEW}`)
  })

  it('explains that a non-Java repository is not supported', async () => {
    stub(unsupportedArchitecture)
    renderPage(<ArchitecturePage />, {
      path: '/repositories/:repositoryId/arch',
      initialEntry: `/repositories/${REPO_ID}/arch`,
    })

    expect(
      await screen.findByRole('heading', { name: 'Architecture analysis is not supported for this technology' }),
    ).toBeInTheDocument()
  })
})

describe('report dashboard architecture tab', () => {
  const renderDashboard = () =>
    renderPage(<RepositoryOverviewPage pollIntervalMs={25} />, {
      path: '/repositories/:repositoryId',
      initialEntry: `/repositories/${REPO_ID}`,
    })

  it('appears when an architecture analyzer ran and opens the architecture', async () => {
    stub(architectureReport, [section('STRUCTURE', 'COMPLETED'), section('ARCHITECTURE', 'COMPLETED_WITH_WARNINGS')])
    renderDashboard()

    const tabs = await screen.findByRole('tablist', { name: 'Report sections' })
    await userEvent.click(within(tabs).getByRole('tab', { name: 'Architecture' }))

    expect(await screen.findByRole('region', { name: 'Layers' })).toHaveTextContent('OrderController')
    expect(screen.getByRole('link', { name: 'Open the architecture page' })).toHaveAttribute(
      'href',
      `/repositories/${REPO_ID}/arch?snapshot=${SNAP_NEW}`,
    )
  })

  it('is hidden when no architecture analyzer applies', async () => {
    stub(unsupportedArchitecture, [section('STRUCTURE', 'COMPLETED'), section('ARCHITECTURE', 'NOT_SUPPORTED')])
    renderDashboard()

    const tabs = await screen.findByRole('tablist', { name: 'Report sections' })
    expect(within(tabs).queryByRole('tab', { name: 'Architecture' })).not.toBeInTheDocument()
  })
})
