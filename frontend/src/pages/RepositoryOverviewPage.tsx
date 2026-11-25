import { useSearchParams } from 'react-router'
import AnalysisProgress from '../components/analysis/AnalysisProgress'
import ArchitectureSection from '../components/architecture/ArchitectureSection'
import DependencyTable from '../components/dependencies/DependencyTable'
import FindingsList from '../components/findings/FindingsList'
import OverviewTab from '../components/report/OverviewTab'
import ReportHeader from '../components/report/ReportHeader'
import SecuritySection from '../components/report/SecuritySection'
import TestsSection from '../components/tests/TestsSection'
import Button from '../components/ui/Button'
import EmptyState from '../components/ui/EmptyState'
import ErrorState from '../components/ui/ErrorState'
import { SkeletonRows, UnavailableState } from '../components/ui/Layout'
import { useReport, useRepository, useSnapshots } from '../hooks/queries'
import { chooseSnapshot } from '../lib/snapshots'
import { isCompleted, type ConsolidatedReport } from '../types/api'
import { useParams } from 'react-router'

const TAB_CATEGORY: Record<string, string> = {
  dependencies: 'DEPENDENCIES',
  'code-quality': 'CODE_QUALITY',
  security: 'SECURITY',
  tests: 'TESTS',
  architecture: 'ARCHITECTURE',
}
const TAB_NAME: Record<string, string> = {
  dependencies: 'Dependencies',
  'code-quality': 'Code Quality',
  security: 'Security',
  tests: 'Tests & Coverage',
  architecture: 'Architecture',
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
    return <SkeletonRows rows={3} label="Loading repository" />
  }
  if (repository.error || snapshots.error) {
    const error = repository.error ?? snapshots.error
    return <ErrorState title="Could not load the repository" message={error?.message ?? 'Unknown error'} action={<Button onClick={() => { repository.refetch(); snapshots.refetch() }}>Retry</Button>} />
  }

  const repo = repository.data
  const items = snapshots.data.items
  if (items.length === 0 || !selected) {
    return (
      <EmptyState title="Nothing analyzed yet" description="This repository has no snapshots. Submit it again to start an analysis." />
    )
  }

  function selectSnapshot(snapshotId: string) {
    // Keep the tab; a snapshot switch drops ?run= (that belongs to one submission).
    const next = new URLSearchParams()
    next.set('snapshot', snapshotId)
    const tab = searchParams.get('tab')
    if (tab) next.set('tab', tab)
    setSearchParams(next)
  }

  const tab = searchParams.get('tab') ?? 'overview'

  return (
    <div className="space-y-5">
      <ReportHeader repository={repo} snapshots={items} selected={selected} onSelectSnapshot={selectSnapshot} durationMs={report.data?.run.durationMs} />

      {runId && !completed && <AnalysisProgress runId={runId} pollIntervalMs={pollIntervalMs} />}
      {!runId && !completed && <EmptyState title="Not analyzed" description="This commit has no analysis run yet." />}

      {completed && report.isPending && <SkeletonRows rows={3} label="Loading report" />}
      {completed && report.error && (
        <ErrorState title="Could not load the report" message={report.error.message} action={<Button onClick={() => report.refetch()}>Retry</Button>} />
      )}
      {completed && report.data && <ReportTab repositoryId={repositoryId} snapshotId={selected.id} report={report.data} tab={tab} />}
    </div>
  )
}

function ReportTab({ repositoryId, snapshotId, report, tab }: { repositoryId: string; snapshotId: string; report: ConsolidatedReport; tab: string }) {
  const modules = report.structure.modules.map((m) => m.path)
  const category = TAB_CATEGORY[tab]
  const section = report.sections.find((s) => s.category === category)
  const analyzed = !!section && (section.status === 'COMPLETED' || section.status === 'COMPLETED_WITH_WARNINGS')

  if (!category || tab === 'overview') {
    return <OverviewTab report={report} repositoryId={repositoryId} snapshotId={snapshotId} />
  }
  if (!analyzed) {
    return (
      <div role="tabpanel" aria-label={TAB_NAME[tab]}>
        <UnavailableState
          title={`${TAB_NAME[tab]}: not analyzed`}
          reason={section?.summary ?? 'No analyzer ran for this category.'}
          action={<span className="text-body-sm text-muted">The Overview capability matrix lists what is supported for each detected technology.</span>}
        />
      </div>
    )
  }
  return (
    <div role="tabpanel" aria-label={TAB_NAME[tab]}>
      {tab === 'dependencies' && <DependencyTable repositoryId={repositoryId} snapshotId={snapshotId} />}
      {tab === 'code-quality' && <FindingsList repositoryId={repositoryId} snapshotId={snapshotId} category="CODE_QUALITY" modules={modules} section={section} commitSha={report.snapshot.commitSha} />}
      {tab === 'security' && section && <SecuritySection repositoryId={repositoryId} snapshotId={snapshotId} section={section} modules={modules} commitSha={report.snapshot.commitSha} />}
      {tab === 'tests' && <TestsSection repositoryId={repositoryId} snapshotId={snapshotId} />}
      {tab === 'architecture' && <ArchitectureSection repositoryId={repositoryId} snapshotId={snapshotId} />}
    </div>
  )
}
