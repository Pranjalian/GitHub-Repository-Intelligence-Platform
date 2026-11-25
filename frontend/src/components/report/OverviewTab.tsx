import { Link } from 'react-router'
import type { ConsolidatedReport } from '../../types/api'
import ModuleSummaryPanel from '../ai/ModuleSummaryPanel'
import Button from '../ui/Button'
import Icon from '../ui/icons'
import { Card, Notice } from '../ui/Layout'
import CapabilityMatrix from './CapabilityMatrix'
import LimitationsPanel from './LimitationsPanel'
import StructurePanel from './StructurePanel'
import SummaryCards from './SummaryCards'
import TechStack from './TechStack'

/** True when no analyzer beyond the generic ones produced results: the "Not analyzed / Not supported" state. */
export function isMostlyUnsupported(report: ConsolidatedReport): boolean {
  const deep = report.sections.filter((s) => ['DEPENDENCIES', 'CODE_QUALITY', 'TESTS', 'ARCHITECTURE'].includes(s.category))
  return deep.length > 0 && deep.every((s) => s.status !== 'COMPLETED' && s.status !== 'COMPLETED_WITH_WARNINGS')
}

export default function OverviewTab({ report, repositoryId, snapshotId }: { report: ConsolidatedReport; repositoryId: string; snapshotId: string }) {
  const modules = report.structure.modules.map((m) => m.path)
  const quality = report.sections.find((s) => s.category === 'CODE_QUALITY')?.findingCountsBySeverity
  const qualityCount = quality ? Object.values(quality).reduce((a, b) => a + b, 0) : null
  const base = `/repositories/${repositoryId}`
  const unsupported = isMostlyUnsupported(report)
  return (
    <div className="space-y-5">
      <SummaryCards report={report} repositoryId={repositoryId} snapshotId={snapshotId} />
      {unsupported && (
        <Notice>
          <p className="font-medium">Deep analysis is not supported for this technology.</p>
          <p>
            Available here: repository structure, language share, the generic secret scan and code search. The capability matrix below explains each unsupported area; other tabs say what is missing.
          </p>
        </Notice>
      )}
      <div className="grid gap-5 xl:grid-cols-[minmax(0,2fr)_minmax(0,1fr)]">
        <div className="min-w-0 space-y-5">
          <CapabilityMatrix rows={report.capabilityMatrix} sections={report.sections} />
          <TechStack technologies={report.technologies} />
        </div>
        <div className="min-w-0 space-y-5">
          <StructurePanel structure={report.structure} />
          <Card icon="route" title="Resolved Entry Points" meta="Heuristic: from the structure analyzer's entry-point rule">
            {report.structure.entryPoints.length === 0 ? (
              <p className="text-body-md text-fg-2">No entry points were resolved.</p>
            ) : (
              <ul className="space-y-1.5">
                {report.structure.entryPoints.map((path) => (
                  <li key={path} className="rounded border border-line bg-canvas px-2 py-1.5">
                    <span className="block break-all font-mono text-body-sm">{path}</span>
                    <span className="text-body-sm text-muted">Signal: entry-point rule (file name and structure)</span>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card tier={2} icon="search" title="Deep dive">
            <p className="mb-3 text-body-md text-fg-2">Dependencies and findings have their own tabs, with filters and evidence.</p>
            <div className="flex flex-wrap gap-2">
              <Link to={`${base}?tab=dependencies&snapshot=${snapshotId}`}>
                <Button variant="primary" icon="dependencies">Dependencies</Button>
              </Link>
              <Link to={`${base}?tab=code-quality&snapshot=${snapshotId}`}>
                <Button icon="code-quality">Code Quality{qualityCount !== null ? ` (${qualityCount})` : ''}</Button>
              </Link>
            </div>
          </Card>
        </div>
      </div>
      <ModuleSummaryPanel repositoryId={repositoryId} snapshotId={snapshotId} modules={modules} />
      <LimitationsPanel limitations={report.limitations} />
      <p className="flex items-center gap-1 text-body-sm text-muted">
        <Icon name="lock" size={12} /> Findings are computed from this commit only. Analyzer set: {report.run.analyzerSetHash ? report.run.analyzerSetHash.slice(0, 12) : 'not reported'}.
      </p>
    </div>
  )
}
