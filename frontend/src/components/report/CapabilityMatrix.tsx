import type { AnalysisCategory, CapabilityLevel, CapabilityMatrixRow, ReportSection } from '../../types/api'
import { Tooltip } from '../ui/Controls'
import { Card } from '../ui/Layout'
import { CapabilityBadge } from '../ui/MoreBadges'

/** The seven category columns of the matrix, in the order of the design. */
export const MATRIX_COLUMNS: { category: AnalysisCategory; label: string }[] = [
  { category: 'TECHNOLOGY', label: 'Technology' },
  { category: 'STRUCTURE', label: 'Structure' },
  { category: 'DEPENDENCIES', label: 'Dependencies' },
  { category: 'CODE_QUALITY', label: 'Code quality' },
  { category: 'SECURITY', label: 'Security' },
  { category: 'TESTS', label: 'Tests' },
  { category: 'ARCHITECTURE', label: 'Architecture' },
]

const LEGEND: CapabilityLevel[] = ['DEEP', 'BASIC', 'GENERIC', 'UNSUPPORTED', 'UNAVAILABLE']

const neutral = (level: CapabilityLevel) => level === 'UNSUPPORTED' || level === 'UNAVAILABLE'

const tint: Record<CapabilityLevel, string> = {
  DEEP: 'tone tone-primary',
  BASIC: 'tone tone-likely',
  GENERIC: 'tone tone-info',
  UNSUPPORTED: 'tone tone-neutral border-dashed',
  UNAVAILABLE: 'tone tone-neutral border-dashed',
}

function statusText(row: CapabilityMatrixRow, section?: ReportSection): string {
  switch (row.executionStatus) {
    case 'COMPLETED': {
      const counts = section?.findingCountsBySeverity
      // "Clean 0" only when the scanner really ran and found nothing
      if ((row.category === 'SECURITY' || row.category === 'CODE_QUALITY') && counts && Object.values(counts).every((v) => v === 0)) return 'Clean 0'
      return 'Complete'
    }
    case 'COMPLETED_WITH_WARNINGS':
      return 'Warnings'
    case 'NOT_CONFIGURED':
      return 'Not configured'
    case 'FAILED':
    case 'TIMED_OUT':
      return 'Failed'
    case 'UNAVAILABLE':
    case 'NOT_SUPPORTED':
    case 'SKIPPED':
    case 'NOT_ANALYZED':
    case 'CANCELLED':
      return 'N/A'
  }
}

/**
 * What the platform can and cannot do for each detected technology (rows) in every analysis category
 * (columns). Unsupported and unavailable cells are neutral and informative, not red, and give their
 * reason on hover and keyboard focus. Detection is never presented as support.
 */
export default function CapabilityMatrix({ rows, sections = [] }: { rows: CapabilityMatrixRow[]; sections?: ReportSection[] }) {
  const technologies = [...new Set(rows.map((r) => r.technology))].sort()
  return (
    <Card
      icon="overview"
      title="Capability Matrix"
      meta="Read-only static analysis. Hover or focus a cell for the analyzer and the reason."
      actions={
        <ul className="hidden flex-wrap gap-1 md:flex" aria-label="Legend">
          {LEGEND.map((level) => (
            <li key={level}>
              <CapabilityBadge level={level} />
            </li>
          ))}
        </ul>
      }
      className="min-w-0"
    >
      <div aria-label="Capability matrix" role="region">
        {rows.length === 0 ? (
          <p className="text-body-md text-fg-2">No capabilities to report for this snapshot.</p>
        ) : (
          <div className="relative overflow-x-auto rounded-lg border border-line" tabIndex={0} aria-label="Capability matrix table, scrolls horizontally">
            <table className="w-full min-w-[760px] border-collapse text-left text-body-sm">
              <thead>
                <tr className="bg-raised">
                  <th scope="col" className="sticky left-0 z-10 bg-raised px-3 py-2 font-mono text-label-sm uppercase tracking-wider text-muted">
                    Technology
                  </th>
                  {MATRIX_COLUMNS.map((c) => (
                    <th key={c.category} scope="col" className="px-2 py-2 font-mono text-label-sm uppercase tracking-wider text-muted">
                      {c.label}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {technologies.map((technology) => (
                  <tr key={technology} className="border-t border-line">
                    <th scope="row" className="sticky left-0 z-10 bg-surface px-3 py-2 text-body-md font-medium">
                      {technology}
                    </th>
                    {MATRIX_COLUMNS.map((column) => {
                      const row = rows.find((r) => r.technology === technology && r.category === column.category)
                      if (!row) {
                        return (
                          <td key={column.category} className="px-2 py-1.5">
                            <span className="block rounded border border-dashed border-line px-2 py-1 text-center text-muted">N/A</span>
                          </td>
                        )
                      }
                      const section = sections.find((s) => s.category === column.category)
                      const analyzer = row.analyzerId ? `${row.analyzerId}${row.analyzerVersion ? ` ${row.analyzerVersion}` : ''}` : 'No analyzer'
                      const reason = row.limitationReason ?? `${analyzer}: ${statusText(row, section)}`
                      const label = `${technology}, ${column.label}: ${row.capabilityLevel.toLowerCase()}, ${statusText(row, section)}. ${reason}`
                      return (
                        <td key={column.category} className="px-2 py-1.5">
                          <Tooltip text={`${analyzer}. ${row.limitationReason ?? statusText(row, section)}`}>
                            <span tabIndex={0} aria-label={label} className={`block w-full rounded px-2 py-1 text-center ${tint[row.capabilityLevel]} ${neutral(row.capabilityLevel) ? 'opacity-90' : ''}`}>
                              <span className="block font-mono text-label-sm font-semibold uppercase">{row.capabilityLevel}</span>
                              <span className="block text-body-sm">{statusText(row, section)}</span>
                            </span>
                          </Tooltip>
                          {row.limitationReason && <span className="sr-only">{row.limitationReason}</span>}
                        </td>
                      )
                    })}
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </Card>
  )
}
