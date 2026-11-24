import { Link, useParams, useSearchParams } from 'react-router'
import AnalysisProgress from '../components/analysis/AnalysisProgress'
import InsightPanel from '../components/ai/InsightPanel'
import ModuleSummaryPanel from '../components/ai/ModuleSummaryPanel'
import ArchitectureSection from '../components/architecture/ArchitectureSection'
import DependencyTable from '../components/dependencies/DependencyTable'
import FindingsList from '../components/findings/FindingsList'
import SecuritySection from '../components/report/SecuritySection'
import TestsSection from '../components/tests/TestsSection'
import CapabilityMatrix from '../components/report/CapabilityMatrix'
import LimitationsPanel from '../components/report/LimitationsPanel'
import SnapshotSelector from '../components/report/SnapshotSelector'
import StructurePanel from '../components/report/StructurePanel'
import TechStack from '../components/report/TechStack'
import CopyButton from '../components/ui/CopyButton'
import EmptyState from '../components/ui/EmptyState'
import ErrorState from '../components/ui/ErrorState'
import RunStatusLabel from '../components/ui/RunStatusLabel'
import { useReport, useRepository, useSnapshots } from '../hooks/queries'
import { formatDateTime } from '../lib/format'
import { isCompleted, type AnalysisCategory, type ReportSection, type Snapshot } from '../types/api'

const TABS: { id: string; label: string; category: AnalysisCategory | null }[] = [
  { id: 'overview', label: 'Overview', category: null },
  { id: 'dependencies', label: 'Dependencies', category: 'DEPENDENCIES' },
  { id: 'code-quality', label: 'Code Quality', category: 'CODE_QUALITY' },
  { id: 'security', label: 'Security', category: 'SECURITY' },
  { id: 'tests', label: 'Tests & Coverage', category: 'TESTS' },
  { id: 'architecture', label: 'Architecture', category: 'ARCHITECTURE' },
]

/** A category gets a tab only when it was analyzed or has a meaningful status; "not supported" lives in the capability matrix. */
function visibleTabs(sections: ReportSection[]) {
  return TABS.filter((tab) => {
    if (!tab.category) {
      return true
    }
    const section = sections.find((s) => s.category === tab.category)
    return !!section && section.status !== 'NOT_SUPPORTED' && section.status !== 'NOT_ANALYZED'
  })
}

/** The snapshot to show: the one in the URL, else the latest completed, else the newest. */
function chooseSnapshot(snapshots: Snapshot[], requestedId: string | null): Snapshot | undefined {
  return (
    snapshots.find((snapshot) => snapshot.id === requestedId) ??
    snapshots.find((snapshot) => isCompleted(snapshot.latestRun?.status)) ??
    snapshots[0]
  )
}

export default function RepositoryOverviewPage({ pollIntervalMs }: { pollIntervalMs?: number }) {
  const { repositoryId = '' } = useParams()
  const [searchParams, setSearchParams] = useSearchParams()
  const repository = useRepository(repositoryId)
  const snapshots = useSnapshots(repositoryId)

  const selected = snapshots.data ? chooseSnapshot(snapshots.data.items, searchParams.get('snapshot')) : undefined
  const latestRun = selected?.latestRun ?? null
  // The run in the URL (just submitted) takes precedence for the snapshot it belongs to.
  const runId = searchParams.get('run') && searchParams.get('snapshot') === selected?.id ? searchParams.get('run') : latestRun?.id
  const completed = isCompleted(latestRun?.status)
  const report = useReport(repositoryId, selected?.id, completed)

  if (repository.isPending || snapshots.isPending) {
    return <p role="status">Loading repository…</p>
  }
  if (repository.error || snapshots.error) {
    const error = repository.error ?? snapshots.error
    return <ErrorState title="Could not load the repository" message={error?.message ?? 'Unknown error'} />
  }

  const repo = repository.data
  const items = snapshots.data.items
  if (items.length === 0 || !selected) {
    return (
      <EmptyState
        title="Nothing analyzed yet"
        description="This repository has no snapshots. Submit it again to start an analysis."
      />
    )
  }

  function selectSnapshot(snapshotId: string) {
    setSearchParams({ snapshot: snapshotId })
  }

  return (
    <div className="space-y-6">
      <header className="space-y-2">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="text-xl font-semibold">
            {repo.owner}/{repo.name}
          </h2>
          <div className="flex flex-wrap items-center gap-3">
            <SnapshotSelector snapshots={items} selectedId={selected.id} onSelect={selectSnapshot} />
            {completed && (
              <nav aria-label="Snapshot views" className="flex gap-3 text-sm">
                <Link to={`/repositories/${repositoryId}/search?snapshot=${selected.id}`} className="text-blue-700 hover:underline">
                  Search code
                </Link>
                <Link to={`/repositories/${repositoryId}/qa?snapshot=${selected.id}`} className="text-blue-700 hover:underline">
                  Ask a question
                </Link>
                <Link to={`/repositories/${repositoryId}/impact?snapshot=${selected.id}`} className="text-blue-700 hover:underline">
                  Impact analysis
                </Link>
              </nav>
            )}
          </div>
        </div>
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
          <dt className="text-slate-500">URL</dt>
          <dd>
            <a href={repo.canonicalUrl} className="text-blue-700 hover:underline" rel="noreferrer">
              {repo.canonicalUrl}
            </a>
          </dd>
          <dt className="text-slate-500">Branch</dt>
          <dd>{selected.branch ?? repo.defaultBranch ?? 'Unknown'}</dd>
          <dt className="text-slate-500">Commit</dt>
          <dd className="flex items-center gap-2">
            <span className="font-mono text-xs">{selected.commitSha}</span>
            <CopyButton value={selected.commitSha} label="Copy SHA" />
          </dd>
          <dt className="text-slate-500">Analyzed</dt>
          <dd>{formatDateTime(latestRun?.completedAt)}</dd>
          <dt className="text-slate-500">Run status</dt>
          <dd>
            <RunStatusLabel status={latestRun?.status} />
          </dd>
        </dl>
      </header>

      {runId && !completed && <AnalysisProgress runId={runId} pollIntervalMs={pollIntervalMs} />}
      {!runId && !completed && (
        <EmptyState title="Not analyzed" description="This commit has no analysis run yet." />
      )}

      {completed && report.isPending && <p role="status">Loading report…</p>}
      {completed && report.error && <ErrorState title="Could not load the report" message={report.error.message} />}
      {completed && report.data && (
        <ReportTabs
          repositoryId={repositoryId}
          snapshotId={selected.id}
          report={report.data}
          tab={searchParams.get('tab') ?? 'overview'}
          onTab={(tab) => {
            const next = new URLSearchParams(searchParams)
            next.set('tab', tab)
            setSearchParams(next)
          }}
        />
      )}
    </div>
  )
}

function ReportTabs({
  repositoryId,
  snapshotId,
  report,
  tab,
  onTab,
}: {
  repositoryId: string
  snapshotId: string
  report: import('../types/api').ConsolidatedReport
  tab: string
  onTab: (tab: string) => void
}) {
  const tabs = visibleTabs(report.sections)
  const active = tabs.find((t) => t.id === tab) ?? tabs[0]
  const modules = report.structure.modules.map((m) => m.path)
  const section = report.sections.find((s) => s.category === active.category)

  return (
    <div>
      <div role="tablist" aria-label="Report sections" className="flex flex-wrap gap-1 border-b border-slate-200">
        {tabs.map((t) => (
          <button
            key={t.id}
            type="button"
            role="tab"
            aria-selected={t.id === active.id}
            onClick={() => onTab(t.id)}
            className={`px-3 py-2 text-sm font-medium ${t.id === active.id ? 'border-b-2 border-slate-900' : 'text-slate-600'}`}
          >
            {t.label}
          </button>
        ))}
      </div>
      <div role="tabpanel" className="pt-4">
        {active.id === 'overview' && (
          <div className="space-y-8">
            <TechStack technologies={report.technologies} />
            <StructurePanel structure={report.structure} />
            <ModuleSummaryPanel repositoryId={repositoryId} snapshotId={snapshotId} modules={modules} />
            <CapabilityMatrix rows={report.capabilityMatrix} />
            <LimitationsPanel limitations={report.limitations} />
          </div>
        )}
        {active.id === 'dependencies' && <DependencyTable repositoryId={repositoryId} snapshotId={snapshotId} />}
        {active.id === 'code-quality' && (
          <div className="space-y-4">
            <section aria-label="Technical debt summary" className="rounded border border-slate-200 bg-white p-3">
              <h3 className="text-sm font-semibold">Technical debt summary</h3>
              <p className="mb-2 text-xs text-slate-600">
                An optional AI summary of the deduplicated findings of every analyzer (no source code is sent).
              </p>
              <InsightPanel
                repositoryId={repositoryId}
                snapshotId={snapshotId}
                type="TECH_DEBT_SUMMARY"
                actionLabel="Summarize technical debt"
              />
            </section>
            <FindingsList repositoryId={repositoryId} snapshotId={snapshotId} category="CODE_QUALITY" modules={modules} />
          </div>
        )}
        {active.id === 'security' && section && (
          <SecuritySection repositoryId={repositoryId} snapshotId={snapshotId} section={section} modules={modules} />
        )}
        {active.id === 'tests' && <TestsSection repositoryId={repositoryId} snapshotId={snapshotId} />}
        {active.id === 'architecture' && (
          <div className="space-y-3">
            <Link
              to={`/repositories/${repositoryId}/arch?snapshot=${snapshotId}`}
              className="text-sm text-blue-700 hover:underline"
            >
              Open the architecture page
            </Link>
            <ArchitectureSection repositoryId={repositoryId} snapshotId={snapshotId} />
          </div>
        )}
      </div>
    </div>
  )
}
