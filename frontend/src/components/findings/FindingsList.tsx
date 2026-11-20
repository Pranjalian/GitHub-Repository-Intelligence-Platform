import { useState } from 'react'
import { useFindings } from '../../hooks/queries'
import type { AnalysisCategory, Finding, FindingFilters, Severity } from '../../types/api'
import SeverityBadge from '../ui/SeverityBadge'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import EmptyState from '../ui/EmptyState'
import ErrorState from '../ui/ErrorState'
import FindingDetailPanel from './FindingDetailPanel'

const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const SORTS: FindingFilters['sort'][] = ['severity', 'file', 'category', 'rule']

/** Findings of one snapshot, filtered, sorted and paged on the server. Same-location duplicates are grouped. */
export default function FindingsList({
  repositoryId,
  snapshotId,
  category,
  modules,
}: {
  repositoryId: string
  snapshotId: string
  category?: AnalysisCategory
  modules: string[]
}) {
  const [severity, setSeverity] = useState<Severity | ''>('')
  const [module, setModule] = useState('')
  const [file, setFile] = useState('')
  const [sort, setSort] = useState<NonNullable<FindingFilters['sort']>>('severity')
  const [page, setPage] = useState(0)
  const [selected, setSelected] = useState<Finding | null>(null)

  const { data, error, isPending } = useFindings(repositoryId, snapshotId, {
    category,
    severity,
    module,
    file,
    sort,
    page,
    size: 20,
  })

  // group findings that share a fingerprint (the same spot reported by different analyzers)
  const groups = new Map<string, Finding[]>()
  for (const finding of data?.items ?? []) {
    groups.set(finding.fingerprint, [...(groups.get(finding.fingerprint) ?? []), finding])
  }

  return (
    <div className="space-y-3">
      <form className="flex flex-wrap items-end gap-3 text-sm" onSubmit={(event) => event.preventDefault()}>
        <label className="flex flex-col gap-1">
          Severity
          <select
            value={severity}
            onChange={(event) => {
              setSeverity(event.target.value as Severity | '')
              setPage(0)
            }}
            className="rounded border border-slate-300 bg-white px-2 py-1"
          >
            <option value="">All severities</option>
            {SEVERITIES.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          Module
          <select
            value={module}
            onChange={(event) => {
              setModule(event.target.value)
              setPage(0)
            }}
            className="rounded border border-slate-300 bg-white px-2 py-1"
          >
            <option value="">All modules</option>
            {modules.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          File or folder
          <input
            value={file}
            onChange={(event) => {
              setFile(event.target.value)
              setPage(0)
            }}
            placeholder="src/main/java"
            className="rounded border border-slate-300 px-2 py-1"
          />
        </label>
        <label className="flex flex-col gap-1">
          Sort by
          <select
            value={sort}
            onChange={(event) => setSort(event.target.value as NonNullable<FindingFilters['sort']>)}
            className="rounded border border-slate-300 bg-white px-2 py-1"
          >
            {SORTS.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
      </form>

      {isPending && <p role="status">Loading findings…</p>}
      {error && <ErrorState title="Could not load findings" message={error.message} />}
      {data && data.items.length === 0 && (
        <EmptyState title="No findings" description="Nothing matches these filters for this snapshot." />
      )}
      {data && data.items.length > 0 && (
        <>
          <ul className="divide-y divide-slate-100 rounded border border-slate-200 bg-white" aria-label="Findings">
            {[...groups.values()].map((group) => {
              const first = group[0]
              return (
                <li key={first.fingerprint} className="p-3">
                  <button type="button" onClick={() => setSelected(first)} className="w-full text-left">
                    <div className="flex flex-wrap items-center gap-2">
                      <SeverityBadge severity={first.severity} />
                      <ConfidenceBadge confidence={first.confidence} />
                      <span className="font-medium">{first.title}</span>
                    </div>
                    <p className="mt-1 font-mono text-xs text-slate-600">
                      {first.filePath ?? 'No file'}
                      {first.startLine != null ? `:${first.startLine}` : ''} · {first.ruleId}
                    </p>
                  </button>
                  {group.length > 1 && (
                    <p className="mt-1 text-xs text-slate-600">
                      Also reported by {group.slice(1).map((f) => f.analyzerId).join(', ')} at the same location.
                    </p>
                  )}
                </li>
              )
            })}
          </ul>
          <div className="flex items-center gap-3 text-sm">
            <button
              type="button"
              disabled={page === 0}
              onClick={() => setPage(page - 1)}
              className="rounded border border-slate-300 px-3 py-1 disabled:opacity-50"
            >
              Previous
            </button>
            <span>
              Page {data.page + 1} of {Math.max(1, data.totalPages)} ({data.totalElements} findings)
            </span>
            <button
              type="button"
              disabled={page + 1 >= data.totalPages}
              onClick={() => setPage(page + 1)}
              className="rounded border border-slate-300 px-3 py-1 disabled:opacity-50"
            >
              Next
            </button>
          </div>
        </>
      )}
      {selected && <FindingDetailPanel finding={selected} onClose={() => setSelected(null)} />}
    </div>
  )
}
