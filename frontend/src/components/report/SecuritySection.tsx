import { useDependencies } from '../../hooks/queries'
import type { ReportSection, VulnerabilityStatus } from '../../types/api'
import FindingsList from '../findings/FindingsList'
import { Card } from '../ui/Layout'
import { VulnerabilityStatusBadge } from '../ui/MoreBadges'
import StatusLabel from '../ui/StatusLabel'

/** Overall dependency vulnerability state, honest about what was not checked. */
export function overallVulnerability(statuses: VulnerabilityStatus[]): { status: VulnerabilityStatus; detail: string } {
  if (statuses.length === 0) return { status: 'NOT_CHECKABLE', detail: 'No dependencies were analyzed, so there is nothing to check.' }
  const vulnerable = statuses.filter((s) => s === 'VULNERABLE').length
  if (vulnerable > 0) return { status: 'VULNERABLE', detail: `${vulnerable} of ${statuses.length} dependencies have known vulnerabilities.` }
  if (statuses.every((s) => s === 'NOT_CHECKED')) return { status: 'NOT_CHECKED', detail: 'The vulnerability lookup is not configured, so nothing was checked. This is not a clean result.' }
  if (statuses.every((s) => s === 'NO_KNOWN_VULNERABILITIES')) return { status: 'NO_KNOWN_VULNERABILITIES', detail: 'No known vulnerabilities in the checked versions (not a guarantee of safety).' }
  return { status: 'NOT_CHECKED', detail: 'Some dependencies could not be checked.' }
}

/** Security findings plus what was actually covered: scanners that ran, rule files, files scanned and skipped, and dependency vulnerability status. */
export default function SecuritySection({
  repositoryId,
  snapshotId,
  section,
  modules,
  commitSha,
}: {
  repositoryId: string
  snapshotId: string
  section: ReportSection
  modules: string[]
  commitSha?: string
}) {
  const deps = useDependencies(repositoryId, snapshotId, { page: 0, size: 500 })
  const overall = deps.data ? overallVulnerability(deps.data.items.map((d) => d.vulnerabilityStatus)) : null
  return (
    <div className="space-y-5">
      <section aria-label="Security findings">
        <FindingsList repositoryId={repositoryId} snapshotId={snapshotId} category="SECURITY" modules={modules} section={section} commitSha={commitSha} />
      </section>
      <section aria-label="Security coverage">
        <Card icon="shield" title="Security coverage" meta="What was scanned, and what was not">
          <ul className="space-y-2">
            {section.analyzers.map((analyzer) => {
              const coverage = analyzer.coverage
              const languages = Array.isArray(coverage?.languages) ? (coverage?.languages as string[]).join(', ') : null
              const ruleFiles = Array.isArray(coverage?.ruleFiles) ? (coverage?.ruleFiles as string[]).join(', ') : null
              return (
                <li key={analyzer.id} className="rounded-lg border border-line bg-canvas p-3">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-body-sm">{analyzer.id}</span>
                    <span className="text-body-sm text-muted">v{analyzer.version}</span>
                    <StatusLabel status={analyzer.status} />
                  </div>
                  {coverage && (
                    <p className="mt-1 text-body-sm text-fg-2">
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
          <div className="mt-4 space-y-1 border-t border-line pt-3">
            <h3 className="font-mono text-label-sm uppercase tracking-wider text-muted">Dependency vulnerability status</h3>
            {overall ? (
              <p className="flex flex-wrap items-center gap-2 text-body-md">
                <VulnerabilityStatusBadge status={overall.status} />
                <span className="text-fg-2">{overall.detail}</span>
              </p>
            ) : (
              <p className="text-body-md text-muted">Loading dependency status…</p>
            )}
          </div>
        </Card>
      </section>
    </div>
  )
}
