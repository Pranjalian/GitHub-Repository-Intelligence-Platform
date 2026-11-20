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
  section('CODE_QUALITY', 'COMPLETED'),
  section('SECURITY', 'COMPLETED_WITH_WARNINGS', {
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
  it('shows tabs only for categories that were analyzed or have a meaningful status', async () => {
    stub(reactSections)
    renderDashboard()

    const tabs = await screen.findByRole('tablist', { name: 'Report sections' })
    expect(within(tabs).getByRole('tab', { name: 'Overview' })).toBeInTheDocument()
    expect(within(tabs).getByRole('tab', { name: 'Dependencies' })).toBeInTheDocument()
    expect(within(tabs).getByRole('tab', { name: 'Security' })).toBeInTheDocument()
    expect(within(tabs).queryByRole('tab', { name: 'Code Quality' })).not.toBeInTheDocument()
    // the unsupported category is explained by the capability matrix instead of an empty panel
    expect(screen.getByRole('region', { name: 'Capability matrix' })).toHaveTextContent('no analyzer pack for Rust')
  })

  it('Java report: every section is reachable', async () => {
    stub(javaSections)
    renderDashboard()
    const tabs = await screen.findByRole('tablist', { name: 'Report sections' })

    for (const name of ['Overview', 'Dependencies', 'Code Quality', 'Security', 'Tests & Coverage']) {
      expect(within(tabs).getByRole('tab', { name })).toBeInTheDocument()
    }
    await userEvent.click(within(tabs).getByRole('tab', { name: 'Dependencies' }))
    expect(await screen.findByText('org.springframework.boot:spring-boot-starter-web')).toBeInTheDocument()
  })

  it('finding detail shows severity, confidence, path and lines, rule, method label and a GitHub link with the SHA', async () => {
    stub(javaSections)
    renderDashboard()
    await userEvent.click(await screen.findByRole('tab', { name: 'Code Quality' }))

    await userEvent.click(await screen.findByRole('button', { name: /Empty catch block/ }))

    const detail = await screen.findByRole('complementary', { name: 'Finding detail' })
    expect(within(detail).getByText('High')).toBeInTheDocument()
    expect(within(detail).getByText('Likely')).toBeInTheDocument()
    expect(within(detail).getByText(/src\/main\/java\/A.java \(lines 10–12\)/)).toBeInTheDocument()
    expect(within(detail).getByText('EmptyCatchBlock')).toBeInTheDocument()
    expect(within(detail).getByText('Deterministic scanner')).toBeInTheDocument()
    expect(within(detail).getByRole('link', { name: 'View on GitHub at this commit' })).toHaveAttribute(
      'href',
      expect.stringContaining(`/blob/${SHA_NEW}/src/main/java/A.java#L10-L12`),
    )
    expect(within(detail).getByText('Handle or log the exception.')).toBeInTheDocument()
  })

  it('filters send the right query parameters and update the list', async () => {
    const requests = stub(javaSections)
    renderDashboard()
    await userEvent.click(await screen.findByRole('tab', { name: 'Code Quality' }))
    await screen.findByRole('button', { name: /Empty catch block/ })

    await userEvent.selectOptions(screen.getByLabelText('Severity'), 'HIGH')
    await userEvent.selectOptions(screen.getByLabelText('Sort by'), 'file')
    await userEvent.type(screen.getByLabelText('File or folder'), 'src/main')

    await waitFor(() => {
      const last = requests[requests.length - 1]
      expect(last.searchParams.get('category')).toBe('CODE_QUALITY')
      expect(last.searchParams.get('severity')).toBe('HIGH')
      expect(last.searchParams.get('sort')).toBe('file')
      expect(last.searchParams.get('file')).toBe('src/main')
    })
  })

  it('evidence containing <script> renders as text, not markup', async () => {
    stub(javaSections, [finding({ evidence: { excerpt: '<script>alert(1)</script>' }, title: 'Weird' })])
    renderDashboard()
    await userEvent.click(await screen.findByRole('tab', { name: 'Code Quality' }))
    await userEvent.click(await screen.findByRole('button', { name: /Weird/ }))

    const detail = await screen.findByRole('complementary', { name: 'Finding detail' })
    expect(within(detail).getByText('<script>alert(1)</script>')).toBeInTheDocument()
    expect(detail.querySelector('script')).toBeNull()
  })

  it('shows "Coverage unavailable" instead of 0%', async () => {
    stub(javaSections)
    renderDashboard()
    await userEvent.click(await screen.findByRole('tab', { name: 'Tests & Coverage' }))

    const coverage = await screen.findByRole('region', { name: 'Coverage' })
    expect(await within(coverage).findByText(/Coverage unavailable: no JaCoCo report found/)).toBeInTheDocument()
    expect(coverage).not.toHaveTextContent('0%')
    expect(screen.getByText('demo.Untested')).toBeInTheDocument()
  })

  it('shows "Not checked" and the remote-managed wording when vulnerability lookup is disabled', async () => {
    stub(javaSections)
    renderDashboard('?tab=dependencies')

    expect(await screen.findByText('Not checked')).toBeInTheDocument()
    expect(screen.getByText(/managed by parent org.springframework.boot:spring-boot-starter-parent:3.3.1 \(not resolved\)/)).toBeInTheDocument()
    expect(screen.getByText('Not resolved')).toBeInTheDocument()
    expect(screen.queryByText('No known vulnerabilities')).not.toBeInTheDocument()
  })

  it('security section shows which analyzers ran, with honest statuses and scanned-file coverage', async () => {
    stub(javaSections)
    renderDashboard('?tab=security')

    const coverage = await screen.findByRole('region', { name: 'Security coverage' })
    expect(coverage).toHaveTextContent('generic-secret-scan-analyzer')
    expect(coverage).toHaveTextContent('12 files scanned')
    expect(within(coverage).getByText('Unavailable')).toBeInTheDocument()
  })

  it('groups findings reported by different analyzers at the same location', async () => {
    stub(javaSections, [
      finding({ id: 'a', analyzerId: 'generic-secret-scan-analyzer', fingerprint: 'same', title: 'Likely hard-coded secret' }),
      finding({ id: 'b', analyzerId: 'semgrep-security-analyzer', fingerprint: 'same', title: 'Semgrep secret' }),
    ])
    renderDashboard('?tab=security')

    const list = await screen.findByRole('list', { name: 'Findings' })
    expect(within(list).getAllByRole('listitem')).toHaveLength(1)
    expect(list).toHaveTextContent('Also reported by semgrep-security-analyzer')
  })
})
