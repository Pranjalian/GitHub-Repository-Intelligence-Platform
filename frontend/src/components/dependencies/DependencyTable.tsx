import { useMemo, useState } from 'react'
import { useDependencies } from '../../hooks/queries'
import type { Dependency, VersionSource, VulnerabilityStatus } from '../../types/api'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import { Checkbox, Input, Select } from '../ui/Controls'
import { DataTable, Pagination } from '../ui/Data'
import EmptyState from '../ui/EmptyState'
import ErrorState from '../ui/ErrorState'
import { Notice, SkeletonRows } from '../ui/Layout'
import { VulnerabilityStatusBadge } from '../ui/MoreBadges'

const SERVER_SIZE = 500
const PAGE = 50

const sourceText: Record<VersionSource, string> = {
  DECLARED: 'Declared',
  PROPERTY: 'From a property',
  MANAGED_LOCAL: 'Managed by a local parent',
  MANAGED_REMOTE_UNRESOLVED: 'Managed by a remote parent or BOM (not resolved)',
  UNRESOLVED: 'Unresolved',
}

/** The note carries "managed by parent g:a:v (not resolved)"; fall back to generic wording. */
export function versionSourceText(dependency: Dependency): string {
  if (dependency.versionSource === 'MANAGED_REMOTE_UNRESOLVED') {
    return dependency.note ?? sourceText.MANAGED_REMOTE_UNRESOLVED
  }
  return sourceText[dependency.versionSource] ?? String(dependency.versionSource)
}

const label = (d: Dependency) => `${d.namespace ? `${d.namespace}:` : ''}${d.name}`

/**
 * Direct dependencies with honest versions and vulnerability status. Ecosystem, module, scope and
 * "known vulnerable" are server filters; search, status and sort apply to the loaded rows (up to 500).
 * Unresolved versions are labelled, never hidden. Transitive dependencies are not included.
 */
export default function DependencyTable({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const [ecosystem, setEcosystem] = useState('')
  const [module, setModule] = useState('')
  const [scope, setScope] = useState('')
  const [status, setStatus] = useState<VulnerabilityStatus | ''>('')
  const [text, setText] = useState('')
  const [vulnerableOnly, setVulnerableOnly] = useState(false)
  const [sort, setSort] = useState<'name' | 'scope'>('name')
  const [page, setPage] = useState(0)
  const { data, error, isPending, refetch } = useDependencies(repositoryId, snapshotId, {
    ecosystem: ecosystem || undefined,
    module: module || undefined,
    scope: scope || undefined,
    vulnerable: vulnerableOnly ? true : undefined,
    page: 0,
    size: SERVER_SIZE,
  })

  const all = useMemo(() => data?.items ?? [], [data])
  const modules = useMemo(() => [...new Set(all.map((d) => d.module).filter((m): m is string => !!m))].sort(), [all])
  const scopes = useMemo(() => [...new Set(all.map((d) => d.scope).filter((s): s is string => !!s))].sort(), [all])
  const visible = useMemo(() => {
    const needle = text.trim().toLowerCase()
    const rows = all.filter((d) => (!status || d.vulnerabilityStatus === status) && (!needle || label(d).toLowerCase().includes(needle)))
    return rows.sort((a, b) => (sort === 'scope' ? (a.scope ?? '').localeCompare(b.scope ?? '') || label(a).localeCompare(label(b)) : label(a).localeCompare(label(b))))
  }, [all, text, status, sort])
  const pageRows = visible.slice(page * PAGE, page * PAGE + PAGE)
  const reset = (fn: () => void) => {
    fn()
    setPage(0)
  }

  return (
    <div className="space-y-3">
      <h2 className="text-headline-lg font-semibold tracking-tight">Dependencies</h2>
      <div className="flex flex-wrap items-center gap-2">
        <Input icon="search" aria-label="Search dependencies" placeholder="Search dependencies" value={text} onChange={(e) => reset(() => setText(e.target.value))} className="w-60" />
        <Select label="Ecosystem" value={ecosystem} onChange={(e) => reset(() => setEcosystem(e.target.value))}>
          <option value="">All</option>
          <option value="MAVEN">Maven</option>
          <option value="NPM">npm</option>
        </Select>
        <Select label="Module" value={module} onChange={(e) => reset(() => setModule(e.target.value))}>
          <option value="">All modules</option>
          {modules.map((m) => <option key={m} value={m}>{m}</option>)}
        </Select>
        <Select label="Scope" value={scope} onChange={(e) => reset(() => setScope(e.target.value))}>
          <option value="">All scopes</option>
          {scopes.map((s) => <option key={s} value={s}>{s}</option>)}
        </Select>
        <Select label="Vulnerability status" value={status} onChange={(e) => reset(() => setStatus(e.target.value as VulnerabilityStatus | ''))}>
          <option value="">Any status</option>
          <option value="NOT_CHECKED">Not checked</option>
          <option value="NOT_CHECKABLE">Not checkable</option>
          <option value="NO_KNOWN_VULNERABILITIES">No known vulnerabilities</option>
          <option value="VULNERABLE">Vulnerable</option>
        </Select>
        <Select label="Sort by" value={sort} onChange={(e) => setSort(e.target.value as 'name' | 'scope')}>
          <option value="name">Name</option>
          <option value="scope">Scope</option>
        </Select>
        <Checkbox label="Known vulnerable only" checked={vulnerableOnly} onChange={(e) => reset(() => setVulnerableOnly(e.target.checked))} />
      </div>
      <p className="text-body-sm text-muted">Transitive dependencies are not included.</p>
      {isPending && <SkeletonRows rows={4} label="Loading dependencies" />}
      {error && <ErrorState title="Could not load dependencies" message={error.message} action={<Button onClick={() => refetch()}>Retry</Button>} />}
      {data && data.totalPages > 1 && <Notice tone="warning">Showing the first {SERVER_SIZE} of {data.totalElements} dependencies; search, status and sort apply to these rows.</Notice>}
      {data && visible.length === 0 && <EmptyState icon="dependencies" title="No dependencies" description="Nothing matches these filters for this snapshot." />}
      {pageRows.length > 0 && (
        <>
          <DataTable
            caption="Dependencies"
            rows={pageRows}
            rowKey={(d) => d.id}
            columns={[
              {
                key: 'name',
                header: 'Dependency',
                cell: (d) => (
                  <span className="font-mono text-body-sm font-medium">
                    {label(d)}
                    {d.managementEntry && <span className="ml-1 text-muted">(management entry)</span>}
                  </span>
                ),
              },
              { key: 'eco', header: 'Ecosystem', cell: (d) => (d.ecosystem === 'MAVEN' ? 'Maven' : 'npm') },
              { key: 'mod', header: 'Module', cell: (d) => <span className="font-mono text-body-sm">{d.module ?? '—'}</span> },
              { key: 'decl', header: 'Declared', cell: (d) => <span className="font-mono text-body-sm">{d.declaredVersion ?? '—'}</span> },
              { key: 'res', header: 'Resolved', cell: (d) => <span className="font-mono text-body-sm">{d.resolvedVersion ?? 'Not resolved'}</span> },
              { key: 'src', header: 'Version source', cell: (d) => <span className="text-body-sm">{versionSourceText(d)}</span> },
              { key: 'scope', header: 'Scope', cell: (d) => d.scope ?? '—' },
              {
                key: 'vuln',
                header: 'Vulnerabilities',
                cell: (d) => (
                  <span className="flex flex-wrap items-center gap-1">
                    <VulnerabilityStatusBadge status={d.vulnerabilityStatus} />
                    {d.vulnerabilityIds?.map((id) => (
                      <a key={id} href={`https://osv.dev/vulnerability/${encodeURIComponent(id)}`} rel="noreferrer" target="_blank" className="font-mono text-body-sm text-primary-text hover:underline">
                        {id}
                      </a>
                    ))}
                    {d.managementEntry && <Badge tone="neutral">management</Badge>}
                  </span>
                ),
              },
            ]}
          />
          <Pagination page={page} totalPages={Math.ceil(visible.length / PAGE)} totalElements={visible.length} shown={pageRows.length} onPage={setPage} />
        </>
      )}
    </div>
  )
}
