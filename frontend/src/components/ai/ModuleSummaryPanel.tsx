import { useState } from 'react'
import InsightPanel from './InsightPanel'

/** Optional AI summary of one module's responsibility, from parser facts and findings (no source code). */
export default function ModuleSummaryPanel({
  repositoryId,
  snapshotId,
  modules,
}: {
  repositoryId: string
  snapshotId: string
  modules: string[]
}) {
  const [module, setModule] = useState(modules[0] ?? '')
  if (modules.length === 0) {
    return null
  }
  return (
    <section aria-label="Module summary" className="space-y-2 rounded border border-slate-200 bg-white p-3">
      <h3 className="text-sm font-semibold">Module summary</h3>
      <label className="flex items-center gap-2 text-sm">
        Module
        <select
          value={module}
          onChange={(event) => setModule(event.target.value)}
          className="rounded border border-slate-300 bg-white px-2 py-1"
        >
          {modules.map((path) => (
            <option key={path} value={path}>
              {path}
            </option>
          ))}
        </select>
      </label>
      <InsightPanel
        key={module}
        repositoryId={repositoryId}
        snapshotId={snapshotId}
        type="SUMMARIZE_MODULE"
        targetId={module}
        actionLabel="Summarize module"
      />
    </section>
  )
}
