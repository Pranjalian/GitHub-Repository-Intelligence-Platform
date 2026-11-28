import { useCoverage, useTests } from '../../hooks/queries'
import { primaryLanguage } from '../../lib/primaryLanguage'
import type { ConsolidatedReport, ReportSection, Severity } from '../../types/api'
import { StatCard } from '../ui/Layout'

const sectionOf = (report: ConsolidatedReport, category: ReportSection['category']) => report.sections.find((s) => s.category === category)
const analyzed = (s?: ReportSection) => !!s && (s.status === 'COMPLETED' || s.status === 'COMPLETED_WITH_WARNINGS')

export interface FindingTotals {
  total: number
  bySeverity: Record<Severity, number>
  parts: { name: string; count: number }[]
}

/** Findings of the analyzed code-quality and security sections; null when neither was analyzed (never zeros). */
export function findingTotals(report: ConsolidatedReport): FindingTotals | null {
  const bySeverity = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 } as Record<Severity, number>
  const parts: { name: string; count: number }[] = []
  let any = false
  for (const [category, name] of [['CODE_QUALITY', 'Code quality'], ['SECURITY', 'Security']] as const) {
    const section = sectionOf(report, category)
    if (!analyzed(section) || !section?.findingCountsBySeverity) continue
    any = true
    let count = 0
    for (const key of Object.keys(bySeverity) as Severity[]) {
      const value = section.findingCountsBySeverity[key] ?? 0
      bySeverity[key] += value
      count += value
    }
    parts.push({ name: `${name} (${section.analyzers.map((a) => a.id).join(', ') || 'no analyzer'})`, count })
  }
  return any ? { total: parts.reduce((a, p) => a + p.count, 0), bySeverity, parts } : null
}

/** The six summary cards. "Not analyzed" and "Unavailable" are used instead of zero for anything that was not measured. */
export default function SummaryCards({ report, repositoryId, snapshotId }: { report: ConsolidatedReport; repositoryId: string; snapshotId: string }) {
  const tests = useTests(repositoryId, snapshotId)
  const coverage = useCoverage(repositoryId, snapshotId)
  const language = primaryLanguage(report.technologies.filter((t) => t.kind === 'LANGUAGE'))
  const framework = report.technologies.find((t) => t.kind === 'FRAMEWORK')
  const build = report.technologies.find((t) => t.kind === 'BUILD_TOOL')
  const summary = report.structure.summary as Record<string, unknown> | null
  const totalFiles = typeof summary?.totalFiles === 'number' ? summary.totalFiles : null
  const byType = summary?.filesByType && typeof summary.filesByType === 'object' ? (summary.filesByType as Record<string, number>) : null
  const sourceFiles = typeof byType?.source === 'number' ? byType.source : null
  const totals = findingTotals(report)
  const depCount = report.dependencyCount
  const coveragePresent = coverage.data?.status === 'COMPLETED'

  return (
    <section aria-label="Summary" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-3 2xl:grid-cols-6">
      <StatCard
        label="Primary stack"
        value={language ? `${language.name}${language.sharePercent != null ? ` ${Math.round(Number(language.sharePercent))}%` : ''}` : 'Not detected'}
        sub={framework ? `${framework.name}${framework.version ? ` ${framework.version}` : ''}` : 'No framework detected'}
        footer={build ? `Build: ${build.name}${build.version ? ` ${build.version}` : ''}` : undefined}
      />
      <StatCard
        label="Code footprint"
        value={totalFiles != null ? `${totalFiles} Files` : 'Not analyzed'}
        sub={sourceFiles != null ? `${sourceFiles} source files · ${report.structure.modules.length} module(s)` : `${report.structure.modules.length} module(s)`}
        footer="Files, not lines of code"
      />
      <StatCard
        label="Dependencies"
        value={depCount != null ? `${depCount} Direct` : 'Not analyzed'}
        sub={depCount != null ? 'Declared in build manifests' : 'No dependency analyzer completed'}
        footer="Transitives omitted"
      />
      <StatCard
        label="Diagnostic findings"
        value={totals ? `${totals.total} Total` : 'Not analyzed'}
        sub={
          totals ? (
            <span className="flex flex-wrap gap-1">
              {(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW'] as Severity[]).map((s, i) => (
                <span key={s} className={`rounded px-1.5 py-0.5 text-body-sm ${totals.bySeverity[s] > 0 ? `tone tone-${['critical', 'high', 'medium', 'low'][i]}` : 'bg-raised text-muted'}`}>
                  {['CRIT', 'HIGH', 'MED', 'LOW'][i]} {totals.bySeverity[s]}
                </span>
              ))}
            </span>
          ) : (
            'No finding category was analyzed'
          )
        }
        footer={totals?.parts.map((p) => `${p.name}: ${p.count}`).join(' · ')}
      />
      <StatCard
        label="Unit & IT suite"
        tag={<span className="font-mono text-label-sm text-muted">STATIC</span>}
        value={tests.data?.testMethods != null ? `${tests.data.testMethods} Tests` : tests.isPending ? '…' : 'Not analyzed'}
        sub={tests.data?.methodsByFramework ? Object.keys(tests.data.methodsByFramework).join(', ') || undefined : undefined}
        footer="Static inventory only; tests are not run"
      />
      <StatCard
        label="Coverage"
        value={coveragePresent ? 'Committed report' : coverage.isPending ? '…' : 'Unavailable'}
        sub={coveragePresent ? 'May be stale relative to this commit' : 'No committed JaCoCo report'}
        footer={coveragePresent ? undefined : 'Tests are not executed'}
      />
    </section>
  )
}
