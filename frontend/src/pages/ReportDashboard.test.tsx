import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { REPO_ID, SHA_NEW, SNAP_NEW, matrix, repository, report, snapshot } from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { Dependency, Finding, ReportSection } from '../types/api'
import RepositoryOverviewPage from './RepositoryOverviewPage'

const BASE = `/api/v1/repositories/${REPO_ID}`

function section(category: ReportSection['category'], status: ReportSection['status'], extra: Partial<ReportSection> = {}): ReportSection {
  return { category, status, analyzers: [], findingCountsBySeverity: null, summary: '', ...extra }
}

const javaSections: ReportSection[] = [
  section('TECHNOLOGY', 'COMPLETED'),
  section('STRUCTURE', 'COMPLETED'),
  section('DEPENDENCIES', 'COMPLETED'),
  section('CODE_QUALITY', 'COMPLETED', { analyzers: [{ id: 'java-code-analyzer', version: '1.0.0', status: 'COMPLETED', capabilityLevel: 'DEEP', coverage: null }], findingCountsBySeverity: { CRITICAL: 0, HIGH: 1, MEDIUM: 0, LOW: 1, INFO: 0 } }),
  section('SECURITY', 'COMPLETED_WITH_WARNINGS', {
    findingCountsBySeverity: { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 },
    analyzers: [
      { id: 'generic-secret-scan-analyzer', version: '1.0.0', status: 'COMPLETED', capabilityLevel: 'GENERIC', coverage: { filesScanned: 12 } },
      { id: 'semgrep-security-analyzer', version: '1.0.0', status: 'UNAVAILABLE', capabilityLevel: 'BASIC', coverage: null },
    ],
  }),
  section('TESTS', 'COMPLETED'),
]

const reactSections: ReportSection[] = [
  section('TECHNOLOGY', 'COMPLETED'),
  section('STRUCTURE', 'COMPLETED'),
  section('DEPENDENCIES', 'COMPLETED'),
  section('CODE_QUALITY', 'NOT_SUPPORTED'),
  section('SECURITY', 'COMPLETED'),
  section('TESTS', 'COMPLETED'),
]

function finding(extra: Partial<Finding> = {}): Finding {
  return {
    id: 'f1',
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
    sourceUrl: `https://github.com/octo/demo/blob/${SHA_NEW}/src/main/java/A.java#L10-L12`,
    suggestedNextStep: 'Handle or log the exception.',
    fingerprint: 'fp-1',
    ...extra,
  }
}

function dependency(extra: Partial<Dependency> = {}): Dependency {
  return {
    id: 'd1',
    analyzerId: 'maven-dependency-analyzer',
    ecosystem: 'MAVEN',
    manifestPath: 'pom.xml',
    module: '.',
    namespace: 'org.springframework.boot',
    name: 'spring-boot-starter-web',
    declaredVersion: null,
    resolvedVersion: null,
    versionSource: 'MANAGED_REMOTE_UNRESOLVED',
    scope: 'compile',
    direct: true,
    managementEntry: false,
    vulnerabilityStatus: 'NOT_CHECKED',
    vulnerabilityIds: null,
    note: 'managed by parent org.springframework.boot:spring-boot-starter-parent:3.3.1 (not resolved)',
    ...extra,
  }
}

function page<T>(items: T[]) {
  return { items, page: 0, size: 20, totalElements: items.length, totalPages: 1 }
}

function stub(sections: ReportSection[], findings: Finding[] = [finding()]) {
  const findingRequests: URL[] = []
  server.use(
    http.get(`${BASE}`, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]))),
    http.get(`${BASE}/reports/${SNAP_NEW}`, () => HttpResponse.json({ ...report(SNAP_NEW, SHA_NEW), sections, capabilityMatrix: matrix })),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/findings`, ({ request }) => {
      findingRequests.push(new URL(request.url))
      return HttpResponse.json(page(findings))
    }),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/dependencies`, () => HttpResponse.json(page([dependency()]))),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/tests`, () =>
      HttpResponse.json({ testClasses: 1, testMethods: 2, methodsByFramework: { 'JUnit 5': 2 }, records: [{ testFilePath: 'src/test/java/OrderServiceTests.java', testSymbol: 'demo.OrderServiceTests', testMethodCount: 2, framework: 'JUnit 5', productionSymbol: 'demo.OrderService', mappingMethod: 'NAMING_CONVENTION', confidence: 'LIKELY' }], untestedClasses: ['demo.Untested'] }),
    ),
    http.get(`${BASE}/snapshots/${SNAP_NEW}/coverage`, () =>
      HttpResponse.json({ status: 'NOT_ANALYZED', message: 'Coverage unavailable: no JaCoCo report found in the repository. Tests are not executed in MVP 1.', records: [] }),
    ),
  )
  return findingRequests
}

function renderDashboard(search = '') {
  return renderPage(<RepositoryOverviewPage pollIntervalMs={25} />, {
    path: '/repositories/:repositoryId',
    initialEntry: `/repositories/${REPO_ID}${search}`,
  })
}

describe('report dashboard', () => {
  it('Overview: header, summary cards, seven-column matrix, stack and limitations of the latest completed snapshot', async () => {
    stub(javaSections)
    renderDashboard()

    expect(await screen.findByRole('heading', { name: 'octo/demo' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Re-analyze' })).toBeInTheDocument()
    const cards = await screen.findByRole('region', { name: 'Summary' })
    expect(within(cards).getByText('Primary stack')).toBeInTheDocument()
    expect(within(cards).getByText('12 Files')).toBeInTheDocument()
    expect(await within(cards).findByText('2 Tests')).toBeInTheDocument()
    // coverage is Unavailable, never 0
    expect(within(cards).getByText('Unavailable')).toBeInTheDocument()
    expect(cards.textContent).not.toMatch(/\b0%/)
    const matrix = screen.getByRole('region', { name: 'Capability matrix' })
    for (const column of ['Technology', 'Structure', 'Dependencies', 'Code quality', 'Security', 'Tests', 'Architecture']) {
      expect(within(matrix).getAllByRole('columnheader', { name: column }).length).toBeGreaterThan(0)
    }
    expect(screen.getByRole('region', { name: 'Limitations' })).toHaveTextContent('semgrep is not installed')
  })

  it('a null count shows "Not analyzed", not 0', async () => {
    stub(reactSections)
    renderDashboard()
    const cards = await screen.findByRole('region', { name: 'Summary' })
    expect(within(cards).getByText('Diagnostic findings')).toBeInTheDocument()
    expect(cards.textContent).toContain('Not analyzed')
    expect(cards.textContent).not.toContain('0 Total')
  })

  it('a category that was not analyzed shows a neutral unavailable state, with its reason', async () => {
    stub(reactSections.map((s) => (s.category === 'CODE_QUALITY' ? { ...s, summary: 'No analyzer exists for code quality in this repository.' } : s)))
    renderDashboard('?tab=code-quality')
    const panel = await screen.findByRole('tabpanel', { name: 'Code Quality' })
    expect(panel).toHaveTextContent('No analyzer exists for code quality in this repository.')
    expect(panel.querySelector('[role="alert"]')).toBeNull()
  })

  it('Java report: Dependencies tab', async () => {
    stub(javaSections)
    renderDashboard('?tab=dependencies')
    expect(await screen.findByText('org.springframework.boot:spring-boot-starter-web')).toBeInTheDocument()
  })

  it('finding drawer shows severity, confidence, path and lines, rule, method label and a GitHub link with the short SHA, open by default', async () => {
    stub(javaSections)
    renderDashboard('?tab=code-quality')

    const detail = await screen.findByRole('complementary', { name: 'Finding detail' })
    expect(within(detail).getByText('High')).toBeInTheDocument()
    expect(within(detail).getByText('Likely')).toBeInTheDocument()
    expect(within(detail).getByText('src/main/java/A.java:10')).toBeInTheDocument()
    expect(within(detail).getByText(/Lines 10-12/)).toBeInTheDocument()
    expect(within(detail).getByText('EmptyCatchBlock')).toBeInTheDocument()
    expect(within(detail).getByText('Deterministic scanner')).toBeInTheDocument()
    expect(within(detail).getByRole('link', { name: new RegExp(`View on GitHub @ ${SHA_NEW.slice(0, 7)}`) })).toHaveAttribute(
      'href',
      expect.stringContaining(`/blob/${SHA_NEW}/src/main/java/A.java#L10-L12`),
    )
    expect(within(detail).getByText('Handle or log the exception.')).toBeInTheDocument()
    // the AI part is a separate section below the deterministic evidence
    expect(within(detail).getByRole('region', { name: 'AI explanation' })).toBeInTheDocument()
    // the matched line is highlighted with the >>> marker
    expect(detail.querySelector('[data-highlighted]')).not.toBeNull()
  })

  it('filters are server-side query parameters and live in the URL', async () => {
    const requests = stub(javaSections)
    renderDashboard('?tab=code-quality')
    await screen.findByRole('button', { name: /Empty catch block/ })

    await userEvent.click(within(screen.getByRole('group', { name: 'Severity filter' })).getByRole('button', { name: /^High/ }))
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'file')
    await userEvent.type(screen.getByLabelText('File or folder'), 'src/main')
    await userEvent.type(screen.getByLabelText('Search findings'), 'catch')

    await waitFor(() => {
      const last = requests[requests.length - 1]
      expect(last.searchParams.get('category')).toBe('CODE_QUALITY')
      expect(last.searchParams.get('severity')).toBe('HIGH')
      expect(last.searchParams.get('sort')).toBe('file')
      expect(last.searchParams.get('file')).toBe('src/main')
      expect(last.searchParams.get('q')).toBe('catch')
    })
    const location = screen.getByTestId('location').textContent ?? ''
    expect(location).toContain('sev=HIGH')
    expect(location).toContain('sort=file')
    expect(location).toContain('tab=code-quality')
  })

  it('severity chips carry the section counts and All equals their sum; Reset clears the filters', async () => {
    stub(javaSections)
    renderDashboard('?tab=code-quality&sev=HIGH&snapshot=' + SNAP_NEW)
    const chips = await screen.findByRole('group', { name: 'Severity filter' })
    expect(within(chips).getByRole('button', { name: /^All/ })).toHaveTextContent('2')
    expect(within(chips).getByRole('button', { name: /^High/ })).toHaveTextContent('1')
    expect(within(chips).getByRole('button', { name: /^Critical/ })).toHaveTextContent('0')
    await userEvent.click(screen.getByRole('button', { name: 'Reset filters' }))
    expect(screen.getByTestId('location').textContent).not.toContain('sev=')
    expect(screen.getByTestId('location').textContent).toContain('tab=code-quality')
  })

  it('list and table layouts show the same rows; the Low group is collapsed by default', async () => {
    stub(javaSections, [finding({ id: 'h', title: 'High one' }), finding({ id: 'l', severity: 'LOW', fingerprint: 'fp-l', title: 'Low one' })])
    renderDashboard('?tab=code-quality')

    expect(await screen.findByRole('button', { name: /High one/ })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: /Low one/ })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: /LOW SEVERITY/ }))
    expect(screen.getByRole('button', { name: /Low one/ })).toBeInTheDocument()

    await userEvent.click(screen.getByRole('radio', { name: 'Table' }))
    const table = await screen.findByRole('table', { name: 'Findings' })
    expect(within(table).getByText('High one')).toBeInTheDocument()
    expect(within(table).getByText('Low one')).toBeInTheDocument()
  })

  it('a redacted secret never shows a raw value', async () => {
    stub(javaSections, [finding({ id: 's', category: 'SECURITY', analyzerId: 'generic-secret-scan-analyzer', ruleId: 'jwt-secret', title: 'Hard-coded secret', evidence: { excerpt: 'jwt_secret = "eyJhbGci...****"' } })])
    renderDashboard('?tab=security')
    const row = await screen.findByRole('list', { name: /HIGH SEVERITY findings/ })
    expect(within(row).getByText('Redacted')).toBeInTheDocument()
    expect(document.body.textContent).not.toContain('FAKE-RAW-SECRET')
    expect(within(row).getByText('jwt_secret = "eyJhbGci...****"')).toBeInTheDocument()
  })

  it('drawer: previous / next, and Esc closes it', async () => {
    stub(javaSections, [finding({ id: 'a', title: 'First' }), finding({ id: 'b', fingerprint: 'fp-b', title: 'Second' })])
    renderDashboard('?tab=code-quality')
    const detail = await screen.findByRole('complementary', { name: 'Finding detail' })
    expect(within(detail).getByRole('heading', { name: 'First' })).toBeInTheDocument()
    await userEvent.click(within(detail).getByRole('button', { name: 'Next finding' }))
    expect(within(detail).getByRole('heading', { name: 'Second' })).toBeInTheDocument()
    await userEvent.click(within(detail).getByRole('button', { name: 'Previous finding' }))
    expect(within(detail).getByRole('heading', { name: 'First' })).toBeInTheDocument()
    await userEvent.keyboard('{Escape}')
    expect(screen.queryByRole('complementary', { name: 'Finding detail' })).toBeNull()
  })

  it('evidence containing <script> renders as text, not markup', async () => {
    stub(javaSections, [finding({ evidence: { excerpt: '<script>alert(1)</script>' }, title: 'Weird' })])
    renderDashboard('?tab=code-quality')

    const detail = await screen.findByRole('complementary', { name: 'Finding detail' })
    expect(within(detail).getByText('<script>alert(1)</script>')).toBeInTheDocument()
    expect(detail.querySelector('script')).toBeNull()
  })

  it('"No findings" and "no findings match" are different states', async () => {
    stub(javaSections, [])
    renderDashboard('?tab=code-quality')
    expect(await screen.findByText('No findings')).toBeInTheDocument()
    expect(screen.getByText(/the scanners returned no findings/)).toBeInTheDocument()
  })

  it('Tests tab shows "Coverage unavailable" instead of 0%', async () => {
    stub(javaSections)
    renderDashboard('?tab=tests')

    const coverage = await screen.findByRole('region', { name: 'Coverage' })
    expect(await within(coverage).findByText(/Coverage unavailable: no committed JaCoCo report/)).toBeInTheDocument()
    expect(coverage).not.toHaveTextContent('0%')
    expect(screen.getByText('demo.Untested')).toBeInTheDocument()
    expect(screen.getByText(/may still be tested under another name/)).toBeInTheDocument()
  })

  it('Dependencies: "Not checked" and the remote-managed wording when the vulnerability lookup is disabled', async () => {
    stub(javaSections)
    renderDashboard('?tab=dependencies')

    expect(await screen.findByText(/managed by parent org.springframework.boot:spring-boot-starter-parent:3.3.1 \(not resolved\)/)).toBeInTheDocument()
    expect(screen.getAllByText('Not checked').length).toBeGreaterThan(1) // the status badge and the filter option
    expect(screen.getByText('Not resolved')).toBeInTheDocument()
    expect(within(screen.getByRole('table', { name: 'Dependencies' })).queryByText('No known vulnerabilities')).toBeNull()
    expect(screen.getByText('Transitive dependencies are not included.')).toBeInTheDocument()
  })

  it('Security: coverage lists the scanners that ran, and "Not checked" is never "0 vulnerabilities"', async () => {
    stub(javaSections)
    renderDashboard('?tab=security')

    const coverage = await screen.findByRole('region', { name: 'Security coverage' })
    expect(coverage).toHaveTextContent('generic-secret-scan-analyzer')
    expect(coverage).toHaveTextContent('12 files scanned')
    expect(within(coverage).getByText('Unavailable')).toBeInTheDocument()
    expect(await within(coverage).findByText('Not checked')).toBeInTheDocument()
    expect(coverage.textContent).not.toMatch(/0 vulnerabilit/i)
    expect(screen.getByText(/not proof of exploitability/)).toBeInTheDocument()
  })

  it('groups findings reported by different analyzers at the same location', async () => {
    stub(javaSections, [
      finding({ id: 'a', analyzerId: 'generic-secret-scan-analyzer', fingerprint: 'same', title: 'Likely hard-coded secret' }),
      finding({ id: 'b', analyzerId: 'semgrep-security-analyzer', fingerprint: 'same', title: 'Semgrep secret' }),
    ])
    renderDashboard('?tab=security')

    const list = await screen.findByRole('list', { name: /HIGH SEVERITY findings/ })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    expect(list).toHaveTextContent('Also reported by semgrep-security-analyzer')
  })
})
