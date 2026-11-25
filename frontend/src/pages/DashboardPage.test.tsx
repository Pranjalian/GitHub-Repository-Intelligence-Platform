import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { SHA_NEW, repository } from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { Repository, RepositorySummary, RunStatus } from '../types/api'
import DashboardPage, { combinedSeverity } from './DashboardPage'

const page = (items: Repository[]) => ({ items, page: 0, size: 20, totalElements: items.length, totalPages: 1 })

function summary(over: Partial<RepositorySummary> = {}): RepositorySummary {
  return {
    snapshotId: 's',
    commitSha: SHA_NEW,
    primaryLanguage: 'Java',
    primaryLanguageShare: 84.5,
    frameworks: ['Spring Boot'],
    dependencyCount: 3,
    findingsBySeverity: {
      CODE_QUALITY: { CRITICAL: 0, HIGH: 2, MEDIUM: 8, LOW: 14, INFO: 0 },
      SECURITY: { CRITICAL: 1, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 },
    },
    openFindings: 25,
    indexStatus: 'COMPLETED',
    latestRunStatus: 'COMPLETED',
    analysisDurationMs: 18000,
    analyzedAt: '2026-11-19T10:01:00Z',
    ...over,
  }
}

function repo(id: string, name: string, status: RunStatus, over: Partial<Repository> = {}): Repository {
  const base = repository()
  return {
    ...base,
    id,
    name,
    canonicalUrl: `https://github.com/octo/${name}`,
    latestSnapshot: { ...base.latestSnapshot!, latestRun: { ...base.latestSnapshot!.latestRun!, status, failureReason: status === 'FAILED' ? 'GitHub rate limit' : null } },
    ...over,
  }
}

const stats = { repositoriesTotal: 2, repositoriesAnalyzed: 1, indexedForSearch: 1, openFindings: 25, activeRuns: 1 }

function serve(items: Repository[], s: unknown = stats) {
  server.use(
    http.get('/api/v1/repositories', () => HttpResponse.json(page(items))),
    http.get('/api/v1/repositories/stats', () => HttpResponse.json(s)),
  )
}

describe('DashboardPage', () => {
  it('lists repositories with language, short commit, status, severity chips and index marker', async () => {
    serve([repo('r1', 'demo', 'COMPLETED', { summary: summary() })])
    renderPage(<DashboardPage />)

    const link = await screen.findByRole('link', { name: 'octo/demo' })
    expect(link).toHaveAttribute('href', '/repositories/r1')
    expect(within(screen.getByRole('list', { name: 'Repositories' })).getByText('Java')).toBeInTheDocument()
    expect(screen.getByText(SHA_NEW.slice(0, 7))).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Repositories' })).getByText('Completed')).toBeInTheDocument()
    const chips = screen.getByLabelText('Findings by severity')
    expect(within(chips).getByText('1 Crit')).toBeInTheDocument()
    expect(within(chips).getByText('2 High')).toBeInTheDocument()
    expect(within(screen.getByRole('list', { name: 'Repositories' })).getByText('Indexed for search')).toBeInTheDocument()
  })

  it('shows the KPI tiles from the stats endpoint', async () => {
    serve([repo('r1', 'demo', 'COMPLETED', { summary: summary() })])
    renderPage(<DashboardPage />)
    await screen.findByRole('link', { name: 'octo/demo' })
    const tiles = screen.getByRole('region', { name: 'Summary' })
    expect(await within(tiles).findByText('25')).toBeInTheDocument()
    expect(within(tiles).getByText('1 Running')).toBeInTheDocument()
  })

  it('a tile whose data is unavailable says Unavailable, never 0', async () => {
    serve([repo('r1', 'demo', 'COMPLETED', { summary: summary() })], { ...stats, openFindings: null })
    renderPage(<DashboardPage />)
    const tiles = await screen.findByRole('region', { name: 'Summary' })
    expect(await within(tiles).findByText('Unavailable')).toBeInTheDocument()
  })

  it('severity chips are "Not analyzed" when null and "--" while running, never zeros', async () => {
    serve([
      repo('r1', 'generic', 'COMPLETED', { summary: summary({ findingsBySeverity: { CODE_QUALITY: null, SECURITY: null }, openFindings: null }) }),
      repo('r2', 'busy', 'ANALYZING', { summary: null }),
      repo('r3', 'fresh', 'COMPLETED', { summary: null }),
    ])
    renderPage(<DashboardPage />)
    await screen.findByRole('link', { name: 'octo/busy' })
    expect(screen.getAllByText('Not analyzed').length).toBeGreaterThanOrEqual(2)
    expect(screen.getByLabelText('Findings: not available while the analysis runs')).toHaveTextContent('-- Crit')
    expect(screen.queryByText('0 Crit')).toBeNull()
  })

  it('combinedSeverity sums only analyzed categories and is null when none was analyzed', () => {
    expect(combinedSeverity(null)).toBeNull()
    expect(combinedSeverity(summary({ findingsBySeverity: { CODE_QUALITY: null, SECURITY: null } }))).toBeNull()
    expect(combinedSeverity(summary({ findingsBySeverity: { CODE_QUALITY: null, SECURITY: { CRITICAL: 1, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 } } }))?.CRITICAL).toBe(1)
  })

  it('index marker states: indexing and not indexed', async () => {
    serve([
      repo('r1', 'a', 'COMPLETED', { summary: summary({ indexStatus: 'INDEXING' }) }),
      repo('r2', 'b', 'COMPLETED', { summary: summary({ indexStatus: 'NOT_INDEXED' }) }),
    ])
    renderPage(<DashboardPage />)
    expect(await screen.findByText('Indexing...')).toBeInTheDocument()
    expect(screen.getByText('Not indexed')).toBeInTheDocument()
  })

  it('a running row shows its phase progress', async () => {
    serve([repo('r1', 'busy', 'INGESTING')])
    renderPage(<DashboardPage />)
    expect(await screen.findByRole('progressbar', { name: /fetching code|ingesting/i })).toBeInTheDocument()
  })

  it('a failed row shows the reason and Retry resubmits with a forced rescan', async () => {
    serve([repo('r1', 'broken', 'FAILED')])
    let body: unknown
    server.use(
      http.post('/api/v1/repositories/analyze', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ repositoryId: 'r1', snapshotId: 's1', analysisRunId: 'run1', commitSha: 'x', status: 'QUEUED', reused: false, branch: 'main', warnings: [] }, { status: 202 })
      }),
    )
    renderPage(<DashboardPage />)
    expect(await screen.findByText('GitHub rate limit')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Retry' }))
    await screen.findByTestId('location')
    expect(body).toEqual({ url: 'https://github.com/octo/broken', forceRescan: true })
  })

  it('copying the SHA does not navigate', async () => {
    serve([repo('r1', 'demo', 'COMPLETED', { summary: summary() })])
    renderPage(<DashboardPage />)
    await screen.findByRole('link', { name: 'octo/demo' })
    await userEvent.click(screen.getByRole('button', { name: /Copy SHA/ }))
    expect(screen.getByTestId('location')).toHaveTextContent('/')
    expect(screen.getByTestId('location').textContent).not.toContain('/repositories/')
  })

  it('filters by text, status and language, and offers Clear when nothing matches', async () => {
    serve([
      repo('r1', 'alpha', 'COMPLETED', { summary: summary() }),
      repo('r2', 'beta', 'FAILED', { summary: null }),
      repo('r3', 'gamma', 'COMPLETED', { summary: summary({ primaryLanguage: 'Python' }) }),
    ])
    renderPage(<DashboardPage />)
    await screen.findByRole('link', { name: 'octo/alpha' })

    await userEvent.click(screen.getByRole('button', { name: 'Failed' }))
    expect(screen.queryByRole('link', { name: 'octo/alpha' })).toBeNull()
    expect(screen.getByRole('link', { name: 'octo/beta' })).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'All' }))

    await userEvent.selectOptions(screen.getByLabelText('Language'), 'Python')
    expect(screen.getAllByRole('link', { name: /octo\// })).toHaveLength(1)
    await userEvent.selectOptions(screen.getByLabelText('Language'), '')

    await userEvent.type(screen.getByLabelText('Filter repositories'), 'zzz')
    expect(screen.getByText('No repositories match these filters')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Clear filters' }))
    expect(screen.getAllByRole('link', { name: /octo\// })).toHaveLength(3)
  })

  it('shows an empty state when there are no repositories', async () => {
    serve([])
    renderPage(<DashboardPage />)

    expect(await screen.findByText('No repositories analyzed yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /Add Repository/ })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: /spring-petclinic/ })).toBeInTheDocument()
  })

  it('shows the API error with a retry', async () => {
    server.use(http.get('/api/v1/repositories', () => HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 })))
    renderPage(<DashboardPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent('boom')
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument()
  })
})
