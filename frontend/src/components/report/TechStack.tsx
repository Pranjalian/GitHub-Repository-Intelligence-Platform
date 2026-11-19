import type { Technology, TechnologyKind } from '../../types/api'
import ConfidenceBadge from '../ui/ConfidenceBadge'

const kindOrder: [TechnologyKind, string][] = [
  ['LANGUAGE', 'Languages'],
  ['FRAMEWORK', 'Frameworks'],
  ['BUILD_TOOL', 'Build tools'],
  ['PACKAGE_MANAGER', 'Package managers'],
  ['DATABASE', 'Databases'],
  ['TEST_FRAMEWORK', 'Test frameworks'],
  ['DEPLOYMENT', 'Deployment'],
]

export default function TechStack({ technologies }: { technologies: Technology[] }) {
  if (technologies.length === 0) {
    return (
      <section aria-label="Technology stack">
        <h2 className="text-base font-semibold">Technology stack</h2>
        <p className="mt-2 text-sm text-slate-600">No technologies were detected in this repository.</p>
      </section>
    )
  }
  return (
    <section aria-label="Technology stack">
      <h2 className="text-base font-semibold">Technology stack</h2>
      <div className="mt-2 space-y-3">
        {kindOrder.map(([kind, title]) => {
          const items = technologies.filter((technology) => technology.kind === kind)
          if (items.length === 0) {
            return null
          }
          return (
            <div key={kind}>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</h3>
              <ul className="mt-1 flex flex-wrap gap-2">
                {items.map((technology) => (
                  <li
                    key={`${technology.kind}-${technology.name}`}
                    className="flex items-center gap-2 rounded border border-slate-200 bg-white px-2 py-1 text-sm"
                    title={technology.evidence?.map((e) => e.path).join(', ')}
                  >
                    <span className="font-medium">{technology.name}</span>
                    {technology.version && <span className="text-slate-500">{technology.version}</span>}
                    {technology.sharePercent != null && (
                      <span className="text-slate-500">{Number(technology.sharePercent).toFixed(1)}%</span>
                    )}
                    <ConfidenceBadge confidence={technology.confidence} />
                  </li>
                ))}
              </ul>
            </div>
          )
        })}
      </div>
    </section>
  )
}
