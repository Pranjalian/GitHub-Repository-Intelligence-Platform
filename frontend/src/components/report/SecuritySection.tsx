import type { ReportSection } from '../../types/api'
import FindingsList from '../findings/FindingsList'
import StatusLabel from '../ui/StatusLabel'

/** Security findings plus what was actually covered: which analyzers ran, which rule files, files scanned and skipped. */
export default function SecuritySection({
  repositoryId,
  snapshotId,
  section,
  modules,
}: {
  repositoryId: string
  snapshotId: string
  section: ReportSection
  modules: string[]
}) {
  return (
    <div className="space-y-4">
      <section aria-label="Security coverage">
        <h3 className="text-base font-semibold">Coverage</h3>
        <ul className="mt-2 space-y-2 text-sm">
          {section.analyzers.map((analyzer) => {
            const coverage = analyzer.coverage
            const languages = Array.isArray(coverage?.languages) ? (coverage?.languages as string[]).join(', ') : null
            const ruleFiles = Array.isArray(coverage?.ruleFiles) ? (coverage?.ruleFiles as string[]).join(', ') : null
            return (
              <li key={analyzer.id} className="rounded border border-slate-200 bg-white p-2">
                <div className="flex items-center gap-2">
                  <span className="font-mono text-xs">{analyzer.id}</span>
                  <StatusLabel status={analyzer.status} />
                </div>
                {coverage && (
                  <p className="mt-1 text-xs text-slate-600">
                    {typeof coverage.filesScanned === 'number' && `${coverage.filesScanned} files scanned`}
                    {typeof coverage.filesSkipped === 'number' && `, ${coverage.filesSkipped} skipped`}
                    {languages && ` · languages: ${languages}`}
                    {ruleFiles && ` · rules: ${ruleFiles}`}
                  </p>
                )}
              </li>
            )
          })}
        </ul>
      </section>
      <section aria-label="Security findings">
        <h3 className="text-base font-semibold">Findings</h3>
        <div className="mt-2">
          <FindingsList repositoryId={repositoryId} snapshotId={snapshotId} category="SECURITY" modules={modules} />
        </div>
      </section>
    </div>
  )
}
