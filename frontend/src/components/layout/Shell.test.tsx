import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from '../../lib/theme'
import { REPO_ID, SHA_NEW, SHA_OLD, SNAP_NEW, SNAP_OLD, aiProvidersDisabled, aiProvidersEnabled, matrix, repository, report, snapshot } from '../../test/fixtures'
import { server } from '../../test/server'
import { makeQueryClient } from '../../test/utils'
import type { AIProviders, ReportSection } from '../../types/api'
import { aiPill } from './Header'
import MainLayout from './MainLayout'
import { DIAGNOSTICS, navBadge } from './navModel'

const BASE = `/api/v1/repositories/${REPO_ID}`
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })
const section = (category: ReportSection['category'], status: ReportSection['status'], extra: Partial<ReportSection> = {}): ReportSection => ({ category, status, analyzers: [], findingCountsBySeverity: null, summary: 'not analyzed because', ...extra })

function stub(sections: ReportSection[], dependencyCount: number | null = 3) {
  server.use(
    http.get(BASE, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED'), snapshot(SNAP_OLD, SHA_OLD, 'COMPLETED')]))),
    http.get(`${BASE}/reports/:id`, ({ params }) => HttpResponse.json({ ...report(params.id as string, SHA_NEW), sections, capabilityMatrix: matrix, dependencyCount })),
  )
}

const java: ReportSection[] = [
  section('DEPENDENCIES', 'COMPLETED'),
  section('CODE_QUALITY', 'COMPLETED', { findingCountsBySeverity: { CRITICAL: 0, HIGH: 2, MEDIUM: 1, LOW: 0, INFO: 0 } }),
  section('SECURITY', 'NOT_SUPPORTED'),
  section('TESTS', 'COMPLETED'),
  section('ARCHITECTURE', 'NOT_SUPPORTED'),
]

function renderShell(path: string) {
  return render(
    <ThemeProvider>
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter initialEntries={[path]}>
          <Routes>
            <Route element={<MainLayout />}>
              <Route path="*" element={<p>page body</p>} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}

beforeEach(() => window.localStorage.clear())
afterEach(() => vi.unstubAllGlobals())

describe('sidebar', () => {
  it('shows the repository groups only inside a repository', async () => {
    renderShell('/')
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(within(nav).getByText('Global workspace')).toBeInTheDocument()
    expect(within(nav).queryByText('Diagnostics')).toBeNull()
    expect(within(nav).queryByRole('link', { name: 'Code Search' })).toBeNull()
  })

  it('inside a repository: context card, Diagnostics and Analytical engine, with real badges only', async () => {
    stub(java)
    renderShell(`/repositories/${REPO_ID}?snapshot=${SNAP_NEW}`)
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    expect(await within(nav).findByText('Diagnostics')).toBeInTheDocument()
    expect(within(nav).getByText('Analytical engine')).toBeInTheDocument()
    await waitFor(() => expect(within(nav).getByRole('link', { name: 'Dependencies' })).toHaveTextContent('3'))
    expect(within(nav).getByRole('link', { name: 'Code Quality' })).toHaveTextContent('3')
    // not analyzed: no number at all, a labelled marker with the reason instead of 0
    const security = within(nav).getByRole('link', { name: 'Security' })
    expect(security.textContent).not.toMatch(/\d/)
    expect(within(security).getByLabelText(/not analyzed because/)).toBeInTheDocument()
    // coverage badge only with a committed report: none here
    expect(within(nav).getByRole('link', { name: 'Tests & Coverage' }).textContent).not.toMatch(/%/)
    // Architecture stays visible with its reason when the analyzer did not run
    expect(within(nav).getByRole('link', { name: 'Architecture' })).toHaveAttribute('href', expect.stringContaining('/arch'))
    // AI-backed entries are marked
    expect(within(nav).getByRole('link', { name: 'Ask (Q&A)' })).toHaveTextContent('AI')
  })

  it('every nav link carries the selected snapshot, so search, questions and impact belong to that commit', async () => {
    stub(java)
    renderShell(`/repositories/${REPO_ID}?snapshot=${SNAP_OLD}`)
    const nav = await screen.findByRole('navigation', { name: 'Main' })
    await within(nav).findByText('Diagnostics')
    for (const name of ['Dependencies', 'Code Quality', 'Code Search', 'Ask (Q&A)', 'Impact Analysis', 'Architecture']) {
      expect(within(nav).getByRole('link', { name }).getAttribute('href'), name).toContain(`snapshot=${SNAP_OLD}`)
    }
    expect(within(nav).getByRole('link', { name: 'Dependencies' }).getAttribute('href')).toContain('tab=dependencies')
    // the header shows the same commit
    expect(await screen.findByText(SHA_OLD.slice(0, 7), { selector: 'span' })).toBeInTheDocument()
  })

  it('navBadge never returns a number for a category that was not analyzed', () => {
    const r = { ...report(SNAP_NEW, SHA_NEW), sections: java, dependencyCount: null }
    for (const item of DIAGNOSTICS) {
      const badge = navBadge(item, r, undefined)
      if (badge?.text) expect(badge.text, item.id).not.toBe('0')
    }
    expect(navBadge(DIAGNOSTICS[1], r, undefined)).toEqual({ reason: expect.any(String) }) // dependency count null
  })

  it('collapse state persists, and the rail keeps accessible names', async () => {
    renderShell('/')
    await userEvent.click(await screen.findByRole('button', { name: 'Collapse sidebar' }))
    expect(window.localStorage.getItem('repointel.sidebar')).toBe('collapsed')
    expect(screen.getByRole('link', { name: 'Dashboard' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Expand sidebar' })).toBeInTheDocument()
  })

  it('below 1280 px the sidebar is an overlay opened from the header menu button', async () => {
    vi.stubGlobal('matchMedia', () => ({ matches: false, addEventListener: () => {}, removeEventListener: () => {} }))
    renderShell('/')
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull()
    await userEvent.click(await screen.findByRole('button', { name: 'Open navigation' }))
    expect(screen.getByRole('dialog', { name: 'Navigation' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('navigation', { name: 'Main' })).toBeNull()
  })

  it('has a skip link and the main landmark', () => {
    renderShell('/')
    expect(screen.getByRole('link', { name: 'Skip to content' })).toHaveAttribute('href', '#main')
    expect(screen.getByRole('main')).toBeInTheDocument()
    expect(screen.getByRole('banner')).toBeInTheDocument()
  })
})

describe('AI status pill', () => {
  const hosted: AIProviders = {
    ...aiProvidersEnabled,
    defaultProvider: 'gemini',
    providers: [{ ...aiProvidersEnabled.providers[0], id: 'gemini', dataHandling: 'HOSTED', chatModel: 'gem-1' }],
  }
  it.each([
    ['off', aiProvidersDisabled, false, 'AI off'],
    ['local', aiProvidersEnabled, false, 'Local: Ollama (llama3.2)'],
    ['hosted', hosted, false, 'Hosted: Gemini (gem-1)'],
    ['unavailable', { ...aiProvidersEnabled, available: false }, false, 'AI unavailable'],
    ['failed request', undefined, true, 'AI unavailable'],
  ])('%s', (_name, data, failed, text) => {
    expect(aiPill(data as AIProviders | undefined, failed).text).toBe(text)
  })

  it('is rendered in the header and links to AI Settings', async () => {
    server.use(http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)))
    renderShell('/')
    const pill = await screen.findByRole('link', { name: /AI status: Local: Ollama/ })
    expect(pill).toHaveAttribute('href', '/settings/ai')
  })
})
