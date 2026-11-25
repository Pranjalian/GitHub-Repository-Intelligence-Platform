import { formatBytes } from '../../lib/format'
import type { Structure } from '../../types/api'
import { BarList, Donut, chartColor } from '../ui/Charts'
import { Card } from '../ui/Layout'

function asNumberRecord(value: unknown): Record<string, number> {
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).filter(([, v]) => typeof v === 'number')) as Record<string, number>
  }
  return {}
}

const TYPE_LABEL: Record<string, string> = { source: 'Source', test: 'Tests', config: 'Configuration', resource: 'Resources', build: 'Build files', docs: 'Documentation', other: 'Other' }

/** Repository anatomy by FILES (lines are not counted): donut, file-type legend, extensions, top-level directories and the modules table. */
export default function StructurePanel({ structure }: { structure: Structure }) {
  const summary = structure.summary
  const filesByType = asNumberRecord(summary?.filesByType)
  const extensions = Object.entries(asNumberRecord(summary?.extensionCounts))
    .sort((a, b) => b[1] - a[1])
    .slice(0, 8)
  const topLevelDirs = Object.entries(asNumberRecord(summary?.directoryTree)).filter(([path]) => !path.includes('/'))
  const totalFiles = typeof summary?.totalFiles === 'number' ? summary.totalFiles : null
  const totalBytes = typeof summary?.totalBytes === 'number' ? summary.totalBytes : null
  const slices = Object.entries(filesByType).map(([type, value]) => ({ label: TYPE_LABEL[type] ?? type, value }))

  return (
    <Card icon="folder" title="Repository Anatomy" meta={totalFiles !== null ? `${totalFiles} files${totalBytes !== null ? ` · ${formatBytes(totalBytes)}` : ''}` : undefined}>
      <div role="region" aria-label="Structure" className="space-y-5">
        {!summary && structure.modules.length === 0 ? (
          <p className="text-body-md text-fg-2">Structure analysis did not produce results for this snapshot.</p>
        ) : (
          <>
            {totalFiles !== null && <p className="sr-only">{totalFiles} files</p>}
            {slices.length > 0 && totalFiles !== null && (
              <div className="grid items-center gap-4 sm:grid-cols-[auto_1fr]">
                <Donut slices={slices} unit="files" total={totalFiles} />
                <ul aria-label="Files by type" className="space-y-1 text-body-md">
                  {slices.map((slice, i) => (
                    <li key={slice.label} className="flex items-center justify-between gap-3">
                      <span className="flex items-center gap-2">
                        <span aria-hidden="true" className="size-2.5 rounded-sm" style={{ background: chartColor(i) }} />
                        {slice.label}
                      </span>
                      <span className="font-mono text-body-sm text-fg-2">
                        {slice.value} files ({totalFiles > 0 ? ((slice.value / totalFiles) * 100).toFixed(1) : '0.0'}%)
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            {extensions.length > 0 && (
              <div>
                <h3 className="mb-1 font-mono text-label-sm uppercase tracking-wider text-muted">Top file extensions</h3>
                <BarList rows={extensions.map(([label, value]) => ({ label, value }))} unit="files" />
              </div>
            )}
            {topLevelDirs.length > 0 && (
              <div>
                <h3 className="mb-1 font-mono text-label-sm uppercase tracking-wider text-muted">Top-level directories</h3>
                <ul className="font-mono text-body-sm text-fg-2">
                  {topLevelDirs.map(([path, count]) => (
                    <li key={path}>
                      {path}/ ({count} files)
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <div>
              <h3 className="mb-1 font-mono text-label-sm uppercase tracking-wider text-muted">Modules</h3>
              {structure.modules.length === 0 ? (
                <p className="text-body-md text-fg-2">No build manifests found, so no module boundaries were detected.</p>
              ) : (
                <div className="overflow-x-auto rounded-lg border border-line">
                  <table className="w-full text-left text-body-md">
                    <thead className="bg-raised">
                      <tr>
                        {['Module', 'Path', 'Build file', 'Ecosystem'].map((h) => (
                          <th key={h} scope="col" className="px-3 py-1.5 font-mono text-label-sm font-medium uppercase tracking-wider text-muted">
                            {h}
                          </th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {structure.modules.map((module) => (
                        <tr key={module.path} className="border-t border-line">
                          <td className="px-3 py-1.5 font-medium">{module.name}</td>
                          <td className="px-3 py-1.5 font-mono text-body-sm">{module.path}</td>
                          <td className="px-3 py-1.5 font-mono text-body-sm">{module.buildFilePath ?? '—'}</td>
                          <td className="px-3 py-1.5">{module.ecosystem ?? '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </div>
          </>
        )}
      </div>
    </Card>
  )
}
