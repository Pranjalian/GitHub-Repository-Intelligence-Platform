import { useState } from 'react'
import { useDependencies } from '../../hooks/queries'
import type { Dependency, VersionSource, VulnerabilityStatus } from '../../types/api'
import Badge, { type BadgeTone } from '../ui/Badge'
import EmptyState from '../ui/EmptyState'
import ErrorState from '../ui/ErrorState'

const vulnerability: Record<VulnerabilityStatus, { text: string; tone: BadgeTone }> = {
  NOT_CHECKED: { text: 'Not checked', tone: 'neutral' },
  NOT_CHECKABLE: { text: 'Not checkable', tone: 'neutral' },
  NO_KNOWN_VULNERABILITIES: { text: 'No known vulnerabilities', tone: 'success' },
  VULNERABLE: { text: 'Vulnerable', tone: 'danger' },
}

const sourceText: Record<VersionSource, string> = {
  DECLARED: 'Declared',
  PROPERTY: 'From a property',
  MANAGED_LOCAL: 'Managed by a local parent',
  MANAGED_REMOTE_UNRESOLVED: 'Managed by a remote parent or BOM (not resolved)',
  UNRESOLVED: 'Unresolved',
}

/** The note carries "managed by parent g:a:v (not resolved)"; fall back to generic wording. */
function versionSourceText(dependency: Dependency): string {
  if (dependency.versionSource === 'MANAGED_REMOTE_UNRESOLVED') {
    return dependency.note ?? sourceText.MANAGED_REMOTE_UNRESOLVED
  }
  return sourceText[dependency.versionSource]
}

export default function DependencyTable({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const [ecosystem, setEcosystem] = useState('')
  const [vulnerableOnly, setVulnerableOnly] = useState(false)
  const [page, setPage] = useState(0)
  const { data, error, isPending } = useDependencies(repositoryId, snapshotId, {
    ecosystem: ecosystem || undefined,
    vulnerable: vulnerableOnly ? true : undefined,
    page,
    size: 50,
  })

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-4 text-sm">
        <label className="flex items-center gap-2">
          Ecosystem
          <select
            value={ecosystem}
            onChange={(event) => {
              setEcosystem(event.target.value)
              setPage(0)
            }}
            className="rounded border border-slate-300 bg-white px-2 py-1"
          >
            <option value="">All</option>
            <option value="MAVEN">Maven</option>
            <option value="NPM">npm</option>
          </select>
        </label>
        <label className="flex items-center gap-2">
          <input
            type="checkbox"
            checked={vulnerableOnly}
            onChange={(event) => {
              setVulnerableOnly(event.target.checked)
              setPage(0)
            }}
          />
          Known vulnerable only
        </label>
      </div>
      {isPending && <p role="status">Loading dependencies…</p>}
      {error && <ErrorState title="Could not load dependencies" message={error.message} />}
      {data && data.items.length === 0 && (
        <EmptyState title="No dependencies" description="Nothing matches these filters for this snapshot." />
      )}
      {data && data.items.length > 0 && (
        <>
          <table className="w-full bg-white text-left text-sm">
            <thead className="border-b border-slate-200 text-xs text-slate-500">
              <tr>
                <th className="p-2">Dependency</th>
                <th className="p-2">Ecosystem</th>
                <th className="p-2">Module</th>
                <th className="p-2">Declared</th>
                <th className="p-2">Resolved</th>
                <th className="p-2">Version source</th>
                <th className="p-2">Scope</th>
                <th className="p-2">Vulnerabilities</th>
              </tr>
            </thead>
            <tbody>
              {data.items.map((dependency) => (
                <tr key={dependency.id} className="border-b border-slate-100 align-top">
                  <td className="p-2 font-medium">
                    {dependency.namespace ? `${dependency.namespace}:` : ''}
                    {dependency.name}
                    {dependency.managementEntry && <span className="ml-1 text-xs text-slate-500">(management entry)</span>}
                  </td>
                  <td className="p-2">{dependency.ecosystem === 'MAVEN' ? 'Maven' : 'npm'}</td>
                  <td className="p-2 font-mono text-xs">{dependency.module ?? '—'}</td>
                  <td className="p-2 font-mono text-xs">{dependency.declaredVersion ?? '—'}</td>
                  <td className="p-2 font-mono text-xs">{dependency.resolvedVersion ?? 'Not resolved'}</td>
                  <td className="p-2 text-xs">{versionSourceText(dependency)}</td>
                  <td className="p-2">{dependency.scope ?? '—'}</td>
                  <td className="p-2">
                    <Badge tone={vulnerability[dependency.vulnerabilityStatus].tone}>
                      {vulnerability[dependency.vulnerabilityStatus].text}
                    </Badge>
                    {dependency.vulnerabilityIds?.map((id) => (
                      <span key={id} className="ml-1 font-mono text-xs">
                        <a href={`https://osv.dev/vulnerability/${encodeURIComponent(id)}`} rel="noreferrer" target="_blank" className="text-blue-700 hover:underline">
                          {id}
                        </a>
                      </span>
                    ))}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
          {data.totalPages > 1 && (
            <div className="flex items-center gap-3 text-sm">
              <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)} className="rounded border border-slate-300 px-3 py-1 disabled:opacity-50">
                Previous
              </button>
              <span>
                Page {data.page + 1} of {data.totalPages}
              </span>
              <button type="button" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)} className="rounded border border-slate-300 px-3 py-1 disabled:opacity-50">
                Next
              </button>
            </div>
          )}
        </>
      )}
    </div>
  )
}
