import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { REPO_ID, SHA_NEW, SNAP_NEW, aiProvidersEnabled, insightOk, repository, snapshot } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { Dependency, Finding, Insight, Technology, VersionSource } from '../../types/api'
import InsightResult from '../ai/InsightResult'
import ModuleSummaryPanel from '../ai/ModuleSummaryPanel'
import DependencyTable, { versionSourceText } from '../dependencies/DependencyTable'
import FindingsList from '../findings/FindingsList'
import TestsSection from '../tests/TestsSection'
import ReportHeader from './ReportHeader'
import TechStack from './TechStack'

const BASE = `/api/v1/repositories/${REPO_ID}/snapshots/${SNAP_NEW}`
const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })

describe('TechStack', () => {
  const tech: Technology[] = [
    { name: 'Spring Boot', kind: 'FRAMEWORK', version: '3.2.4', confidence: 'DETECTED', sharePercent: null, detectorId: 'x', evidence: [{ path: 'pom.xml', line: 21, signal: 'spring-boot-starter-parent' }, { path: 'README.md', line: null, signal: '<script>alert(1)</script>' }] },
    { name: 'Java', kind: 'LANGUAGE', version: null, confidence: 'DETECTED', sharePercent: 92.4, detectorId: 'x', evidence: [] },
    { name: 'XML', kind: 'LANGUAGE', version: null, confidence: 'LIKELY', sharePercent: 5.1, detectorId: 'x', evidence: null },
  ]
  it('groups by kind, shows a language share bar, and expands evidence as plain text', async () => {
    const { container } = renderPage(<TechStack technologies={tech} />)
    expect(screen.getByRole('img', { name: /Language share: Java 92.4/ })).toBeInTheDocument()
    expect(screen.getByText('Languages')).toBeInTheDocument()
    expect(screen.getByText('Frameworks')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Spring Boot/ }))
    expect(screen.getByText(/pom\.xml:21/)).toBeInTheDocument()
    expect(screen.getByText(/matched rule spring-boot-starter-parent/)).toBeInTheDocument()
    // hostile evidence stays text
    expect(screen.getByText(/<script>alert\(1\)<\/script>/)).toBeInTheDocument()
    expect(container.querySelector('script')).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /XML/ }))
    expect(screen.getByText(/No file evidence was recorded/)).toBeInTheDocument()
  })
})

describe('ReportHeader', () => {
  it('Re-analyze resubmits the repository with a forced rescan and goes to the progress screen', async () => {
    let body: unknown
    server.use(
      http.post('/api/v1/repositories/analyze', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ repositoryId: REPO_ID, snapshotId: SNAP_NEW, analysisRunId: 'run-9', commitSha: SHA_NEW, status: 'QUEUED', reused: false, branch: 'main', warnings: [] }, { status: 202 })
      }),
    )
    renderPage(<ReportHeader repository={repository()} snapshots={[snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]} selected={snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')} onSelectSnapshot={() => {}} durationMs={18400} />)
    expect(screen.getByText('18.4s wall-clock')).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: 'Re-analyze' }))
    await waitFor(() => expect(body).toEqual({ url: 'https://github.com/octo/demo', forceRescan: true }))
    await waitFor(() => expect(screen.getByTestId('location').textContent).toContain('run=run-9'))
  })
})

describe('ModuleSummaryPanel states', () => {
  it('AI off: says so, deterministic facts unaffected, button disabled', async () => {
    renderPage(<ModuleSummaryPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} modules={['.']} />)
    expect(await screen.findByText(/AI is optional and off here/)).toBeInTheDocument()
    await waitFor(() => expect(screen.getByRole('button', { name: 'Summarize module' })).toBeDisabled())
  })
  it('AI on: names the provider and model, and says nothing leaves the machine only for a local provider', async () => {
    server.use(http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)))
    renderPage(<ModuleSummaryPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} modules={['.', 'core']} />)
    expect(await screen.findByText(/using ollama \(llama3\.2\)\. Deterministic facts above are untouched\. Nothing leaves your machine/)).toBeInTheDocument()
    expect(screen.getByText('2 module(s)')).toBeInTheDocument()
  })
})

describe('technical debt priorities', () => {
  it('renders structured priorities as up to three ranked cards, the rest as a list', () => {
    const insight: Insight = { ...insightOk, type: 'TECH_DEBT_SUMMARY', structured: { priorities: ['one', 'two', 'three', 'four'], keyPoints: [] } }
    renderPage(<InsightResult insight={insight} />)
    const section = screen.getByRole('region', { name: 'Priorities' })
    expect(within(section).getAllByRole('listitem')).toHaveLength(4)
    expect(section).toHaveTextContent('#1')
    expect(section).toHaveTextContent('#3')
    expect(section).not.toHaveTextContent('#4')
  })
})

describe('dependencies', () => {
  const dep = (versionSource: VersionSource, id: string): Dependency => ({
    id, analyzerId: 'm', ecosystem: 'MAVEN', manifestPath: 'pom.xml', module: '.', namespace: 'g', name: id, declaredVersion: null, resolvedVersion: null, versionSource, scope: 'compile', direct: true, managementEntry: false, vulnerabilityStatus: 'NOT_CHECKED', vulnerabilityIds: null, note: null,
  })
  it('every version source has its own label, and the remote-BOM label is visible', async () => {
    const sources: VersionSource[] = ['DECLARED', 'PROPERTY', 'MANAGED_LOCAL', 'MANAGED_REMOTE_UNRESOLVED', 'UNRESOLVED']
    server.use(http.get(`${BASE}/dependencies`, () => HttpResponse.json(page(sources.map((s) => dep(s, s.toLowerCase()))))))
    renderPage(<DependencyTable repositoryId={REPO_ID} snapshotId={SNAP_NEW} />)
    const table = await screen.findByRole('table', { name: 'Dependencies' })
    for (const source of sources) expect(within(table).getAllByText(versionSourceText(dep(source, 'x'))).length).toBeGreaterThan(0)
    expect(within(table).getByText('Managed by a remote parent or BOM (not resolved)')).toBeInTheDocument()
  })

  it('sorts by name or scope, filters by status and paginates', async () => {
    const many = Array.from({ length: 60 }, (_, i) => ({ ...dep('DECLARED', `dep-${String(i).padStart(2, '0')}`), scope: i % 2 ? 'test' : 'compile' }))
    server.use(http.get(`${BASE}/dependencies`, () => HttpResponse.json(page(many))))
    renderPage(<DependencyTable repositoryId={REPO_ID} snapshotId={SNAP_NEW} />)
    await screen.findByRole('table', { name: 'Dependencies' })
    expect(screen.getByText(/Showing 1-50 of 60/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /Next/ }))
    expect(screen.getByText(/Showing 51-60 of 60/)).toBeInTheDocument()
    await userEvent.selectOptions(screen.getByLabelText('Vulnerability status'), 'VULNERABLE')
    expect(await screen.findByText('No dependencies')).toBeInTheDocument()
  })
})

describe('coverage present', () => {
  it('shows real percentages per scope with a stale note, never a made-up value', async () => {
    server.use(
      http.get(`${BASE}/tests`, () => HttpResponse.json({ testClasses: 1, testMethods: 2, methodsByFramework: { 'JUnit 5': 2 }, records: [], untestedClasses: [] })),
      http.get(`${BASE}/coverage`, () =>
        HttpResponse.json({ status: 'COMPLETED', message: 'Coverage from a report committed to the repository; it may be stale relative to this commit.', records: [{ reportPath: 'target/jacoco.xml', scope: 'BUNDLE', name: 'demo', lineCovered: 80, lineMissed: 20, branchCovered: null, branchMissed: null }] }),
      ),
    )
    renderPage(<TestsSection repositoryId={REPO_ID} snapshotId={SNAP_NEW} />)
    const coverage = await screen.findByRole('region', { name: 'Coverage' })
    expect(await within(coverage).findByText('80%')).toBeInTheDocument()
    expect(within(coverage).getByText('Not reported')).toBeInTheDocument() // branches: no data, not 0%
    expect(coverage).toHaveTextContent('may be stale')
  })
})

describe('finding drawer focus', () => {
  const finding: Finding = {
    id: 'f1', severity: 'HIGH', confidence: 'LIKELY', category: 'CODE_QUALITY', title: 'Empty catch block', description: null, filePath: 'A.java', startLine: 1, endLine: 1, module: '.', ruleId: 'R', analyzerId: 'a', analysisMethod: 'PARSER', evidence: null, sourceUrl: null, suggestedNextStep: null, fingerprint: 'fp-1',
  }
  it('Esc closes the drawer and returns focus to the row that opened it', async () => {
    server.use(http.get(`${BASE}/findings`, () => HttpResponse.json(page([finding]))))
    renderPage(<FindingsList repositoryId={REPO_ID} snapshotId={SNAP_NEW} category="CODE_QUALITY" modules={['.']} />)
    const row = await screen.findByRole('button', { name: /Empty catch block/ })
    await userEvent.click(row)
    expect(await screen.findByRole('complementary', { name: 'Finding detail' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    await waitFor(() => expect(screen.queryByRole('complementary', { name: 'Finding detail' })).toBeNull())
    expect(row).toHaveFocus()
  })
})
