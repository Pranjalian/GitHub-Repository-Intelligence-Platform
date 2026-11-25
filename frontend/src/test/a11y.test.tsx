import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { axe } from 'vitest-axe'
import { describe, expect, it, vi } from 'vitest'
import MainLayout from '../components/layout/MainLayout'
import { ThemeProvider } from '../lib/theme'
import AddRepositoryPage from '../pages/AddRepositoryPage'
import AISettingsPage from '../pages/AISettingsPage'
import ArchitecturePage from '../pages/ArchitecturePage'
import DashboardPage from '../pages/DashboardPage'
import ImpactAnalysisPage from '../pages/ImpactAnalysisPage'
import NotFoundPage from '../pages/NotFoundPage'
import QAPage from '../pages/QAPage'
import RepositoryOverviewPage from '../pages/RepositoryOverviewPage'
import SearchPage from '../pages/SearchPage'
import { REPO_ID, SHA_NEW, SNAP_NEW, aiProvidersEnabled, architectureReport, indexStatus, matrix, repository, report, snapshot } from './fixtures'
import { server } from './server'
import { makeQueryClient } from './utils'
import type { Finding, ReportSection } from '../types/api'

vi.mock('../lib/mermaid', () => ({ getMermaid: () => Promise.resolve({ run: () => Promise.resolve() }) }))

const BASE = `/api/v1/repositories/${REPO_ID}`
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })
const section = (category: ReportSection['category'], status: ReportSection['status'], extra: Partial<ReportSection> = {}): ReportSection => ({ category, status, analyzers: [], findingCountsBySeverity: null, summary: '', ...extra })
const finding: Finding = {
  id: 'f1', severity: 'HIGH', confidence: 'LIKELY', category: 'CODE_QUALITY', title: 'Empty catch block', description: 'Hides errors.', filePath: 'src/main/java/A.java', startLine: 10, endLine: 12, module: '.', ruleId: 'EmptyCatchBlock', analyzerId: 'java-code-analyzer', analysisMethod: 'DETERMINISTIC_SCANNER', evidence: { excerpt: 'catch (Exception e) {}' }, sourceUrl: null, suggestedNextStep: 'Handle it.', fingerprint: 'fp-1',
}
const javaSections: ReportSection[] = [
  section('STRUCTURE', 'COMPLETED'),
  section('DEPENDENCIES', 'COMPLETED'),
  section('CODE_QUALITY', 'COMPLETED', { analyzers: [{ id: 'pmd', version: '1', status: 'COMPLETED', capabilityLevel: 'DEEP', coverage: null }], findingCountsBySeverity: { CRITICAL: 0, HIGH: 1, MEDIUM: 0, LOW: 0, INFO: 0 } }),
  section('SECURITY', 'COMPLETED', { findingCountsBySeverity: { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 } }),
  section('TESTS', 'COMPLETED'),
  section('ARCHITECTURE', 'COMPLETED'),
]
const pythonSections: ReportSection[] = [
  section('STRUCTURE', 'COMPLETED'),
  section('DEPENDENCIES', 'NOT_SUPPORTED', { summary: 'No analyzer exists for dependencies in this repository.' }),
  section('CODE_QUALITY', 'NOT_SUPPORTED', { summary: 'No analyzer exists for code quality in this repository.' }),
  section('SECURITY', 'COMPLETED', { findingCountsBySeverity: { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 } }),
  section('TESTS', 'NOT_SUPPORTED', { summary: 'No analyzer exists for tests in this repository.' }),
  section('ARCHITECTURE', 'NOT_SUPPORTED', { summary: 'No analyzer exists for architecture in this repository.' }),
]

function stub(sections: ReportSection[]) {
  server.use(
    http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)),
    http.get('/api/v1/repositories', () => HttpResponse.json({ ...page([repository()]), size: 20 })),
    http.get(BASE, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]))),
    http.get(`${BASE}/reports/${SNAP_NEW}`, () => HttpResponse.json({ ...report(SNAP_NEW, SHA_NEW), sections, capabilityMatrix: matrix, dependencyCount: sections[1].status === 'COMPLETED' ? 1 : null })),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/findings`, () => HttpResponse.json(page([finding]))),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/dependencies`, () => HttpResponse.json(page([]))),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/architecture`, () => HttpResponse.json(architectureReport)),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/index`, () => HttpResponse.json(indexStatus('COMPLETED'))),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/impact-analysis/symbols`, () => HttpResponse.json({ status: 'OK', reason: null, query: '', symbols: [] })),
    http.get('/api/v1/ai/usage', () => HttpResponse.json({ items: [], page: 0, size: 25, totalElements: 0, totalPages: 0 })),
  )
}

function renderInShell(path: string, element: React.ReactElement, route: string) {
  return render(
    <ThemeProvider>
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route element={<MainLayout />}>
              <Route path={route} element={element} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}

/** jsdom has no layout, so colour contrast is checked by the token test (styles/contrast.test.ts), not here. */
const AXE = { rules: { 'color-contrast': { enabled: false } } }

async function expectNoViolations(container: HTMLElement) {
  const results = await axe(container, AXE)
  const summary = results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html.slice(0, 80)).join(' | ')}`)
  expect(summary).toEqual([])
}

const R = `/repositories/${REPO_ID}`
const Q = `?snapshot=${SNAP_NEW}`

describe.each(['dark', 'light'])('accessibility (axe), %s theme', (theme) => {
  const setup = () => {
    window.localStorage.setItem('repointel.theme', theme)
    stub(javaSections)
  }

  it('dashboard', async () => {
    setup()
    const { container } = renderInShell('/', <DashboardPage />, '/')
    await screen.findByRole('link', { name: 'octo/demo' })
    await expectNoViolations(container)
  })

  it('add repository', async () => {
    setup()
    const { container } = renderInShell('/repositories/new', <AddRepositoryPage />, '/repositories/new')
    await screen.findByLabelText('Repository URL')
    await expectNoViolations(container)
  })

  it.each(['', '&tab=dependencies', '&tab=code-quality', '&tab=security', '&tab=tests'])('report tab %s', async (tab) => {
    setup()
    const { container } = renderInShell(`${R}${Q}${tab}`, <RepositoryOverviewPage />, '/repositories/:repositoryId')
    await screen.findByRole('heading', { name: 'octo/demo' })
    await screen.findByRole('tabpanel', undefined).catch(() => screen.findByRole('region', { name: 'Summary' }))
    await new Promise((r) => setTimeout(r, 150))
    await expectNoViolations(container)
  })

  it('architecture', async () => {
    setup()
    const { container } = renderInShell(`${R}/arch${Q}`, <ArchitecturePage />, '/repositories/:repositoryId/arch')
    await screen.findByRole('region', { name: 'Layers' })
    await expectNoViolations(container)
  })

  it('code search', async () => {
    setup()
    const { container } = renderInShell(`${R}/search${Q}`, <SearchPage />, '/repositories/:repositoryId/search')
    await screen.findByText('Search this commit')
    await expectNoViolations(container)
  })

  it('ask', async () => {
    setup()
    const { container } = renderInShell(`${R}/qa${Q}`, <QAPage />, '/repositories/:repositoryId/qa')
    await screen.findByRole('textbox')
    await expectNoViolations(container)
  })

  it('impact analysis', async () => {
    setup()
    const { container } = renderInShell(`${R}/impact${Q}`, <ImpactAnalysisPage />, '/repositories/:repositoryId/impact')
    await screen.findByRole('search')
    await expectNoViolations(container)
  })

  it('AI settings', async () => {
    setup()
    const { container } = renderInShell('/settings/ai', <AISettingsPage />, '/settings/ai')
    await screen.findByText(/AI Insights:/)
    await expectNoViolations(container)
  })

  it('not found', async () => {
    setup()
    const { container } = renderInShell('/nope', <NotFoundPage />, '*')
    await screen.findByText('Page not found')
    await expectNoViolations(container)
  })
})

describe('"Not supported" for a Python + JavaScript repository', () => {
  const python = (tab: string) => {
    stub(pythonSections)
    server.use(
      http.get(`${BASE}/snapshots/${SNAP_NEW}/architecture`, () =>
        HttpResponse.json({ ...architectureReport, supported: false, unsupported: ['Python: no analyzer pack for Python', 'JavaScript: no analyzer pack for JavaScript'] }),
      ),
    )
    return renderInShell(`${R}${Q}${tab}`, <RepositoryOverviewPage />, '/repositories/:repositoryId')
  }

  it('Overview says what is not supported and what is available instead, with no zeros for unsupported data', async () => {
    const { container } = python('')
    expect(await screen.findByText(/Deep analysis is not supported for this technology/)).toBeInTheDocument()
    expect(screen.getByText(/structure, language share, the generic secret scan and code search/)).toBeInTheDocument()
    const cards = screen.getByRole('region', { name: 'Summary' })
    expect(cards.textContent).toContain('Not analyzed')
    await expectNoViolations(container)
  })

  it.each([
    ['&tab=dependencies', 'Dependencies', 'No analyzer exists for dependencies'],
    ['&tab=code-quality', 'Code Quality', 'No analyzer exists for code quality'],
    ['&tab=tests', 'Tests & Coverage', 'No analyzer exists for tests'],
    ['&tab=architecture', 'Architecture', 'No analyzer exists for architecture'],
  ])('%s tab is a neutral unavailable state with the reason', async (tab, name, reason) => {
    python(tab)
    const panel = await screen.findByRole('tabpanel', { name })
    expect(panel).toHaveTextContent(reason)
    expect(panel.querySelector('[role="alert"]')).toBeNull()
  })

  it('the sidebar shows no number for categories that were not analyzed, only a labelled marker', async () => {
    python('')
    await screen.findByText(/Deep analysis is not supported/)
    const nav = screen.getByRole('navigation', { name: 'Main' })
    const deps = nav.querySelector('a[aria-label="Dependencies"]') as HTMLElement
    expect(deps.textContent).not.toMatch(/\d/)
    expect(deps.querySelector('[aria-label*="not analyzed" i], [aria-label*="No analyzer" i]')).not.toBeNull()
  })
})
