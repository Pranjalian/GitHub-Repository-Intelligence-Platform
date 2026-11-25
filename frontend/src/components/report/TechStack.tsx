import type { Technology, TechnologyKind } from '../../types/api'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import { SegmentedBar, chartColor } from '../ui/Charts'
import { Accordion } from '../ui/Controls'
import { Card } from '../ui/Layout'

const kindOrder: [TechnologyKind, string][] = [
  ['LANGUAGE', 'Languages'],
  ['FRAMEWORK', 'Frameworks'],
  ['BUILD_TOOL', 'Build tools'],
  ['PACKAGE_MANAGER', 'Package managers'],
  ['DATABASE', 'Databases & persistence'],
  ['TEST_FRAMEWORK', 'Test frameworks'],
  ['DEPLOYMENT', 'Deployment'],
]

/** "pom.xml:21 matched rule spring-boot-starter-parent": path and signal as plain text (they come from repository files). */
function EvidenceList({ technology }: { technology: Technology }) {
  const evidence = technology.evidence ?? []
  if (evidence.length === 0) return <p className="text-body-sm text-muted">No file evidence was recorded for this detection.</p>
  return (
    <ul className="space-y-1">
      {evidence.map((e, i) => (
        <li key={i} className="font-mono text-body-sm">
          <span className="text-fg">
            {e.path}
            {e.line != null ? `:${e.line}` : ''}
          </span>
          {e.signal && <span className="text-fg-2"> matched rule {e.signal}</span>}
        </li>
      ))}
    </ul>
  )
}

export default function TechStack({ technologies }: { technologies: Technology[] }) {
  if (technologies.length === 0) {
    return (
      <Card icon="dependencies" title="Technology Stack & Evidence">
        <div role="region" aria-label="Technology stack">
          <p className="text-body-md text-fg-2">No technologies were detected in this repository.</p>
        </div>
      </Card>
    )
  }
  return (
    <Card icon="dependencies" title="Technology Stack & Evidence" meta="Grouped by kind · deterministic rule matches">
      <div role="region" aria-label="Technology stack" className="space-y-5">
        {kindOrder.map(([kind, title]) => {
          const items = technologies.filter((technology) => technology.kind === kind)
          if (items.length === 0) {
            return null
          }
          const shares = kind === 'LANGUAGE' ? items.filter((t) => t.sharePercent != null) : []
          return (
            <div key={kind} className="space-y-2">
              <h3 className="font-mono text-label-sm uppercase tracking-wider text-muted">{title}</h3>
              {shares.length > 0 && (
                <div className="space-y-1">
                  <SegmentedBar label="Language share" segments={shares.map((t, i) => ({ label: t.name, value: Number(t.sharePercent), color: chartColor(i) }))} />
                  <p className="flex flex-wrap gap-x-3 text-body-sm text-fg-2">
                    {shares.map((t, i) => (
                      <span key={t.name} className="inline-flex items-center gap-1">
                        <span aria-hidden="true" className="size-2 rounded-sm" style={{ background: chartColor(i) }} />
                        {t.name} {Number(t.sharePercent).toFixed(1)}%
                      </span>
                    ))}
                  </p>
                </div>
              )}
              <ul className="space-y-1.5">
                {items.map((technology) => (
                  <li key={`${technology.kind}-${technology.name}`}>
                    <Accordion
                      title={
                        <span className="flex items-baseline gap-2">
                          <span className="font-semibold">{technology.name}</span>
                          {technology.version && <span className="text-fg-2">{technology.version}</span>}
                          {technology.sharePercent != null && <span className="font-mono text-body-sm text-fg-2">{Number(technology.sharePercent).toFixed(1)}%</span>}
                        </span>
                      }
                      right={<ConfidenceBadge confidence={technology.confidence} />}
                    >
                      <EvidenceList technology={technology} />
                    </Accordion>
                  </li>
                ))}
              </ul>
            </div>
          )
        })}
      </div>
    </Card>
  )
}
