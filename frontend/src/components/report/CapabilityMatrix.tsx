import type { CapabilityLevel, CapabilityMatrixRow } from '../../types/api'
import Badge, { type BadgeTone } from '../ui/Badge'
import StatusLabel from '../ui/StatusLabel'

const categoryText: Record<string, string> = {
  TECHNOLOGY: 'Technology',
  STRUCTURE: 'Structure',
  DEPENDENCIES: 'Dependencies',
  CODE_QUALITY: 'Code quality',
  SECURITY: 'Security',
  TESTS: 'Tests',
}

const levels: Record<CapabilityLevel, { text: string; tone: BadgeTone }> = {
  GENERIC: { text: 'Generic', tone: 'neutral' },
  BASIC: { text: 'Basic', tone: 'info' },
  DEEP: { text: 'Deep', tone: 'success' },
  UNSUPPORTED: { text: 'Unsupported', tone: 'warning' },
  UNAVAILABLE: { text: 'Unavailable', tone: 'warning' },
}

/**
 * What the platform can and cannot do for each detected technology. Unsupported and unavailable rows are
 * shown with their reason: detection is never presented as support.
 */
export default function CapabilityMatrix({ rows }: { rows: CapabilityMatrixRow[] }) {
  return (
    <section aria-label="Capability matrix">
      <h2 className="text-base font-semibold">Capability matrix</h2>
      {rows.length === 0 ? (
        <p className="mt-2 text-sm text-slate-600">No capabilities to report for this snapshot.</p>
      ) : (
        <table className="mt-2 w-full text-left text-sm">
          <thead className="text-xs text-slate-500">
            <tr>
              <th className="py-1 pr-4">Technology</th>
              <th className="py-1 pr-4">Category</th>
              <th className="py-1 pr-4">Analyzer</th>
              <th className="py-1 pr-4">Depth</th>
              <th className="py-1 pr-4">Status</th>
              <th className="py-1">Reason</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, index) => (
              <tr key={`${row.technology}-${row.category}-${row.analyzerId ?? 'none'}-${index}`} className="border-t border-slate-100">
                <td className="py-1.5 pr-4 font-medium">{row.technology}</td>
                <td className="py-1.5 pr-4">{categoryText[row.category] ?? row.category}</td>
                <td className="py-1.5 pr-4 font-mono text-xs">
                  {row.analyzerId ? `${row.analyzerId}${row.analyzerVersion ? ` ${row.analyzerVersion}` : ''}` : 'None'}
                </td>
                <td className="py-1.5 pr-4">
                  <Badge tone={levels[row.capabilityLevel].tone}>{levels[row.capabilityLevel].text}</Badge>
                </td>
                <td className="py-1.5 pr-4">
                  <StatusLabel status={row.executionStatus} />
                </td>
                <td className="py-1.5 text-slate-700">{row.limitationReason ?? ''}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </section>
  )
}
