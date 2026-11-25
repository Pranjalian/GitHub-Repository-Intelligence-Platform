import type { AnalysisCategory, ConsolidatedReport, CoverageReport, ReportSection } from '../../types/api'

export interface DiagnosticItem {
  id: string
  label: string
  icon: string
  category: AnalysisCategory | null
}

/** The report tabs, as navigation. `tab` is the value of `?tab=`; the ids are the existing, public ones. */
export const DIAGNOSTICS: DiagnosticItem[] = [
  { id: 'overview', label: 'Overview', icon: 'overview', category: null },
  { id: 'dependencies', label: 'Dependencies', icon: 'dependencies', category: 'DEPENDENCIES' },
  { id: 'code-quality', label: 'Code Quality', icon: 'code-quality', category: 'CODE_QUALITY' },
  { id: 'security', label: 'Security', icon: 'shield', category: 'SECURITY' },
  { id: 'tests', label: 'Tests & Coverage', icon: 'tests', category: 'TESTS' },
  { id: 'architecture', label: 'Architecture', icon: 'architecture', category: 'ARCHITECTURE' },
]

const ANALYZED = new Set(['COMPLETED', 'COMPLETED_WITH_WARNINGS'])

export interface NavBadge {
  /** Text to show; undefined means "no number" (the caller shows the not-analyzed dot instead). */
  text?: string
  /** Why there is no number. */
  reason?: string
}

const sum = (counts: Record<string, number>) => Object.values(counts).reduce((a, b) => a + b, 0)

/** Real values only: a category that was not analyzed has no badge number (never 0). */
export function navBadge(item: DiagnosticItem, report: ConsolidatedReport | undefined, coverage: CoverageReport | undefined): NavBadge | null {
  if (!report || !item.category) return null
  const section: ReportSection | undefined = report.sections.find((s) => s.category === item.category)
  const analyzed = !!section && ANALYZED.has(section.status)
  if (item.id === 'dependencies') {
    return analyzed && report.dependencyCount != null ? { text: String(report.dependencyCount) } : { reason: section?.summary ?? 'Not analyzed' }
  }
  if (item.id === 'code-quality' || item.id === 'security') {
    return analyzed && section?.findingCountsBySeverity ? { text: String(sum(section.findingCountsBySeverity)) } : { reason: section?.summary ?? 'Not analyzed' }
  }
  if (item.id === 'tests') {
    if (coverage?.status === 'COMPLETED') {
      const bundle = coverage.records.filter((r) => r.scope === 'BUNDLE' && r.lineCovered != null && r.lineMissed != null)
      const covered = bundle.reduce((a, r) => a + (r.lineCovered ?? 0), 0)
      const total = covered + bundle.reduce((a, r) => a + (r.lineMissed ?? 0), 0)
      if (total > 0) return { text: `${Math.round((covered / total) * 1000) / 10}%` }
    }
    return null // no badge at all when there is no committed coverage report
  }
  if (item.id === 'architecture') {
    return analyzed ? null : { reason: section?.summary ?? 'Not supported for this technology' }
  }
  return null
}
