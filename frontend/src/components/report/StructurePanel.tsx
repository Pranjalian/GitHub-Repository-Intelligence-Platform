import { formatBytes } from '../../lib/format'
import type { Structure } from '../../types/api'

function asNumberRecord(value: unknown): Record<string, number> {
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).filter(([, v]) => typeof v === 'number')) as Record<string, number>
  }
  return {}
}

export default function StructurePanel({ structure }: { structure: Structure }) {
  const summary = structure.summary
  const filesByType = asNumberRecord(summary?.filesByType)
  const extensions = asNumberRecord(summary?.extensionCounts)
  const topLevelDirs = Object.entries(asNumberRecord(summary?.directoryTree)).filter(([path]) => !path.includes('/'))
  const totalFiles = typeof summary?.totalFiles === 'number' ? summary.totalFiles : null
  const totalBytes = typeof summary?.totalBytes === 'number' ? summary.totalBytes : null

  return (
    <section aria-label="Structure">
      <h2 className="text-base font-semibold">Structure</h2>
      {!summary && structure.modules.length === 0 ? (
        <p className="mt-2 text-sm text-slate-600">Structure analysis did not produce results for this snapshot.</p>
      ) : (
        <div className="mt-2 space-y-4 text-sm">
          {totalFiles !== null && (
            <p>
              {totalFiles} files{totalBytes !== null && `, ${formatBytes(totalBytes)}`}
            </p>
          )}
          {Object.keys(filesByType).length > 0 && (
            <ul className="flex flex-wrap gap-2" aria-label="Files by type">
              {Object.entries(filesByType).map(([type, count]) => (
                <li key={type} className="rounded bg-slate-100 px-2 py-0.5">
                  {type}: {count}
                </li>
              ))}
            </ul>
          )}
          <div>
            <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Modules</h3>
            {structure.modules.length === 0 ? (
              <p className="mt-1 text-slate-600">No build manifests found, so no module boundaries were detected.</p>
            ) : (
              <table className="mt-1 w-full text-left">
                <thead className="text-xs text-slate-500">
                  <tr>
                    <th className="py-1 pr-4">Module</th>
                    <th className="py-1 pr-4">Path</th>
                    <th className="py-1 pr-4">Build file</th>
                    <th className="py-1">Ecosystem</th>
                  </tr>
                </thead>
                <tbody>
                  {structure.modules.map((module) => (
                    <tr key={module.path} className="border-t border-slate-100">
                      <td className="py-1 pr-4 font-medium">{module.name}</td>
                      <td className="py-1 pr-4 font-mono text-xs">{module.path}</td>
                      <td className="py-1 pr-4 font-mono text-xs">{module.buildFilePath ?? '—'}</td>
                      <td className="py-1">{module.ecosystem ?? '—'}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
          {structure.entryPoints.length > 0 && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
                Likely entry points
              </h3>
              <ul className="mt-1 font-mono text-xs">
                {structure.entryPoints.map((path) => (
                  <li key={path}>{path}</li>
                ))}
              </ul>
            </div>
          )}
          {Object.keys(extensions).length > 0 && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Top file extensions</h3>
              <ul className="mt-1 flex flex-wrap gap-2">
                {Object.entries(extensions)
                  .slice(0, 8)
                  .map(([extension, count]) => (
                    <li key={extension} className="rounded bg-slate-100 px-2 py-0.5 font-mono text-xs">
                      {extension} × {count}
                    </li>
                  ))}
              </ul>
            </div>
          )}
          {topLevelDirs.length > 0 && (
            <div>
              <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Top-level directories</h3>
              <ul className="mt-1 font-mono text-xs">
                {topLevelDirs.map(([path, count]) => (
                  <li key={path}>
                    {path}/ ({count} files)
                  </li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </section>
  )
}
