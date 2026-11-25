import { useEffect, useMemo, useRef, useState } from 'react'
import { useSearchParams } from 'react-router'
import { analyzeRepository } from '../../api/repositories'
import { useNavigate } from 'react-router'
import { useFindings, useRepository } from '../../hooks/queries'
import { middleTruncate } from '../../lib/format'
import { useIsDesktop } from '../../lib/useMediaQuery'
import type { AnalysisCategory, Finding, FindingFilters, ReportSection, Severity } from '../../types/api'
import InsightPanel from '../ai/InsightPanel'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import { FilterChip, Input, SegmentedControl, Select } from '../ui/Controls'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import { DataTable, Pagination } from '../ui/Data'
import EmptyState from '../ui/EmptyState'
import ErrorState from '../ui/ErrorState'
import { Drawer } from '../ui/Flow'
import Icon from '../ui/icons'
import { Card, SkeletonRows, UnavailableState } from '../ui/Layout'
import SeverityBadge from '../ui/SeverityBadge'
import FindingDetailPanel from './FindingDetailPanel'

const SEVERITIES: Severity[] = ['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO']
const SEVERITY_NOTE: Record<Severity, string> = {
  CRITICAL: 'Fix before anything else',
  HIGH: 'Likely defects or exposures',
  MEDIUM: 'Worth scheduling',
  LOW: 'Style and minor issues',
  INFO: 'Informational notes',
}
const PAGE_SIZE = 50

type Group = 'none' | 'severity' | 'rule'
type View = 'list' | 'table'
type Sort = NonNullable<FindingFilters['sort']>

const isSecretAnalyzer = (f: Finding) => /secret/i.test(f.analyzerId)

/**
 * Findings of one snapshot (Code Quality or Security): filter bar, grouped list or table, and an inspector
 * drawer. Filters live in the URL (so a list can be shared and survives a reload). Server side: severity,
 * module, file/folder, analyzer, text search, sort and paging. Client side: grouping and the
 * same-location merge of findings reported by several analyzers.
 */
export default function FindingsList({
  repositoryId,
  snapshotId,
  category,
  modules,
  section,
  commitSha,
  title,
}: {
  repositoryId: string
  snapshotId: string
  category?: AnalysisCategory
  modules: string[]
  /** The report section of this category: severity counts and the analyzers that ran. */
  section?: ReportSection
  commitSha?: string
  title?: string
}) {
  const [params, setParams] = useSearchParams()
  const severity = (params.get('sev') ?? '') as Severity | ''
  const analyzer = params.get('analyzer') ?? ''
  const module = params.get('module') ?? ''
  const file = params.get('file') ?? ''
  const q = params.get('q') ?? ''
  const group = (params.get('group') ?? 'severity') as Group
  const sort = (params.get('sort') ?? 'severity') as Sort
  const view = (params.get('view') ?? 'list') as View
  const page = Number(params.get('page') ?? '0') || 0

  const [selectedId, setSelectedId] = useState<string | null>(null)
  const [drawerClosed, setDrawerClosed] = useState(false)
  const [collapsed, setCollapsed] = useState<Record<string, boolean>>({ LOW: true, INFO: true })
  const desktop = useIsDesktop()
  const searchRef = useRef<HTMLInputElement>(null)
  const [returnTo, setReturnTo] = useState<HTMLElement | null>(null)
  const repository = useRepository(repositoryId)
  const navigate = useNavigate()
  const [rescanning, setRescanning] = useState(false)

  function set(next: Record<string, string | null>) {
    const copy = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value === null || value === '' || (key === 'group' && value === 'severity') || (key === 'sort' && value === 'severity') || (key === 'view' && value === 'list') || (key === 'page' && value === '0')) copy.delete(key)
      else copy.set(key, value)
    }
    if (!('page' in next)) copy.delete('page')
    setParams(copy, { replace: true })
  }

  const { data, error, isPending, refetch } = useFindings(repositoryId, snapshotId, { category, severity, module, file, analyzer, q, sort, page, size: PAGE_SIZE })

  // findings that share a fingerprint (the same spot reported by different analyzers) are one row
  const rows = useMemo(() => {
    const groups = new Map<string, Finding[]>()
    for (const finding of data?.items ?? []) groups.set(finding.fingerprint, [...(groups.get(finding.fingerprint) ?? []), finding])
    return [...groups.values()]
  }, [data])
  const flat = useMemo(() => rows.map((g) => g[0]), [rows])
  const selected = flat.find((f) => f.id === selectedId) ?? (desktop && !drawerClosed ? flat[0] : undefined)
  const selectedIndex = selected ? flat.findIndex((f) => f.id === selected.id) : -1

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement
      const typing = ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)
      if ((event.key === '/' || ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'f')) && !typing) {
        event.preventDefault()
        searchRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  function move(delta: number) {
    const next = flat[selectedIndex + delta]
    if (next) setSelectedId(next.id)
  }

  async function rescan() {
    const url = repository.data?.canonicalUrl
    if (!url) return
    setRescanning(true)
    try {
      const r = await analyzeRepository(url, true)
      navigate(`/repositories/${r.repositoryId}?snapshot=${r.snapshotId}&run=${r.analysisRunId}`)
    } finally {
      setRescanning(false)
    }
  }

  const counts = section?.findingCountsBySeverity ?? null
  const totalAll = counts ? Object.values(counts).reduce((a, b) => a + b, 0) : null
  const analyzers = section?.analyzers.map((a) => a.id) ?? []
  const filtered = !!(severity || analyzer || module || file || q)
  const heading = title ?? (category === 'SECURITY' ? 'Security Findings' : 'Code Quality Findings')

  const grouped = useMemo(() => {
    if (group === 'none') return [{ key: 'all', label: '', note: '', items: rows }]
    const map = new Map<string, Finding[][]>()
    for (const row of rows) {
      const key = group === 'severity' ? row[0].severity : row[0].ruleId
      map.set(key, [...(map.get(key) ?? []), row])
    }
    const keys = group === 'severity' ? SEVERITIES.filter((s) => map.has(s)) : [...map.keys()].sort()
    return keys.map((key) => ({ key, label: group === 'severity' ? `${key} SEVERITY` : key, note: group === 'severity' ? SEVERITY_NOTE[key as Severity] : '', items: map.get(key)! }))
  }, [rows, group])

  const select = (finding: Finding, element: HTMLElement | null) => {
    setReturnTo(element)
    setSelectedId(finding.id)
    setDrawerClosed(false)
  }

  return (
    <div className="space-y-4">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div className="space-y-1">
          <h2 className="flex items-center gap-3 text-headline-lg font-semibold tracking-tight">
            {heading}
            {data && <Badge tone="neutral">{data.totalElements} issues</Badge>}
          </h2>
          <p className="flex flex-wrap items-center gap-2 text-body-sm text-muted">
            <Badge tone="info" icon="code-quality">{analyzers.length > 0 ? analyzers.join(' · ') : 'No analyzer'}</Badge>
            {commitSha && <span className="font-mono">@ {commitSha.slice(0, 7)}</span>}
            <span>{analyzers.length} active analyzer(s)</span>
          </p>
        </div>
        <Button variant="secondary" icon="refresh" busy={rescanning} disabled={!repository.data} onClick={rescan}>
          Re-analyze snapshot
        </Button>
      </header>

      {category === 'CODE_QUALITY' && (
        <section aria-label="Technical debt summary" className="ai-surface space-y-2 p-4">
          <div className="flex flex-wrap items-center gap-2">
            <Icon name="sparkle" size={18} className="text-ai-text" />
            <h3 className="text-headline-md font-semibold">Technical debt summary</h3>
            <span className="tone tone-ai rounded px-1.5 font-mono text-label-sm uppercase">AI-generated · optional</span>
          </div>
          <p className="text-body-sm text-fg-2">An optional AI summary of the deduplicated findings of every analyzer (no source code is sent).</p>
          <InsightPanel repositoryId={repositoryId} snapshotId={snapshotId} type="TECH_DEBT_SUMMARY" actionLabel="Summarize technical debt" />
        </section>
      )}

      {category === 'SECURITY' && (
        <p className="flex items-center gap-2 text-body-md text-fg-2">
          <Icon name="info" size={14} />
          Findings are indications that need review, not proof of exploitability.
        </p>
      )}

      <Card className="space-y-3">
        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Severity filter">
          <FilterChip selected={severity === ''} onClick={() => set({ sev: null })} count={totalAll ?? undefined}>
            All
          </FilterChip>
          {SEVERITIES.map((s) => (
            <FilterChip key={s} selected={severity === s} onClick={() => set({ sev: s })} count={counts ? counts[s] : undefined}>
              {s.charAt(0) + s.slice(1).toLowerCase()}
            </FilterChip>
          ))}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          <Input inputRef={searchRef} icon="search" hint="/" aria-label="Search findings" placeholder="Search rule, message, path" value={q} onChange={(e) => set({ q: e.target.value })} className="w-64" />
          <Select label="Analyzer" value={analyzer} onChange={(e) => set({ analyzer: e.target.value })}>
            <option value="">All analyzers</option>
            {analyzers.map((a) => (
              <option key={a} value={a}>{a}</option>
            ))}
          </Select>
          <Select label="Module" value={module} onChange={(e) => set({ module: e.target.value })}>
            <option value="">All modules</option>
            {modules.map((m) => (
              <option key={m} value={m}>{m}</option>
            ))}
          </Select>
          <Input aria-label="File or folder" placeholder="File or folder" value={file} onChange={(e) => set({ file: e.target.value })} className="w-44" />
          <Select label="Group" value={group} onChange={(e) => set({ group: e.target.value })}>
            <option value="severity">Severity</option>
            <option value="rule">Rule</option>
            <option value="none">None</option>
          </Select>
          <Select label="Sort by" value={sort} onChange={(e) => set({ sort: e.target.value })}>
            <option value="severity">Severity</option>
            <option value="file">File</option>
            <option value="rule">Rule</option>
            <option value="category">Category</option>
          </Select>
          <SegmentedControl<View> label="Layout" value={view} onChange={(v) => set({ view: v })} options={[{ value: 'list', label: 'List' }, { value: 'table', label: 'Table' }]} />
          {filtered && (
            <Button variant="ghost" onClick={() => setParams(new URLSearchParams(Array.from(params).filter(([k]) => k === 'tab' || k === 'snapshot')), { replace: true })}>
              Reset filters
            </Button>
          )}
        </div>
      </Card>

      <div className="flex items-start gap-4">
        <div className="min-w-0 flex-1 space-y-3">
          {isPending && <SkeletonRows rows={4} label="Loading findings" />}
          {error && <ErrorState title="Could not load findings" message={error.message} action={<Button onClick={() => refetch()}>Retry</Button>} />}
          {data && data.items.length === 0 &&
            (filtered ? (
              <EmptyState icon="filter" title="No findings match these filters" action={<Button onClick={() => setParams(new URLSearchParams(Array.from(params).filter(([k]) => k === 'tab' || k === 'snapshot')), { replace: true })}>Reset filters</Button>} />
            ) : (
              <UnavailableState
                icon="check-circle"
                title="No findings"
                reason={`All files analyzed; the scanners returned no findings${commitSha ? ` for ${commitSha.slice(0, 7)}` : ''}.`}
              />
            ))}
          {data && data.items.length > 0 && view === 'table' && (
            <DataTable
              caption="Findings"
              rows={flat}
              rowKey={(f) => f.id}
              selectedKey={selected?.id}
              onRowClick={(f) => select(f, null)}
              columns={[
                { key: 'sev', header: 'Severity', cell: (f) => <SeverityBadge severity={f.severity} /> },
                {
                  key: 'target',
                  header: 'Finding & target',
                  cell: (f) => (
                    <button type="button" className="text-left" onClick={(e) => select(f, e.currentTarget)}>
                      <span className="block font-medium">{f.title}</span>
                      <span className="block font-mono text-body-sm text-muted" title={f.filePath ?? undefined}>
                        {middleTruncate(f.filePath ?? 'No file')}
                        {f.startLine != null ? `:${f.startLine}` : ''} · {f.ruleId}
                      </span>
                    </button>
                  ),
                },
                { key: 'an', header: 'Analyzer', cell: (f) => <span className="font-mono text-body-sm">{f.analyzerId}</span> },
                { key: 'conf', header: 'Confidence', cell: (f) => <ConfidenceBadge confidence={f.confidence} /> },
              ]}
            />
          )}
          {data && data.items.length > 0 && view === 'list' && (
            <div
              aria-label="Findings"
              role="group"
              className="space-y-3"
              onKeyDown={(e) => {
                const target = e.target as HTMLElement
                if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
                if (e.key === 'j' || e.key === 'J') move(1)
                if (e.key === 'k' || e.key === 'K') move(-1)
              }}
            >
              {grouped.map((g) => {
                const isCollapsed = group === 'none' ? false : !!collapsed[g.key]
                return (
                  <section key={g.key} aria-label={g.label || 'Findings'} className="space-y-1.5">
                    {g.label && (
                      <h3>
                        <button
                          type="button"
                          aria-expanded={!isCollapsed}
                          onClick={() => setCollapsed((c) => ({ ...c, [g.key]: !isCollapsed }))}
                          className="flex w-full items-center gap-2 font-mono text-label-md uppercase tracking-wider text-fg-2"
                        >
                          <Icon name={isCollapsed ? 'chevron-right' : 'chevron-down'} size={14} />
                          {g.label} · {g.items.length} {g.items.length === 1 ? 'finding' : 'findings'}
                          {g.note && <span className="normal-case tracking-normal text-muted">{g.note}</span>}
                        </button>
                      </h3>
                    )}
                    {!isCollapsed && (
                      <ul aria-label={g.label ? `${g.label} findings` : 'Findings list'} className="space-y-1.5">
                        {g.items.map((row) => {
                          const first = row[0]
                          const active = selected?.id === first.id
                          return (
                            <li key={first.fingerprint} className={`rounded-lg border bg-surface p-3 ${active ? 'border-primary' : 'border-line'}`}>
                              <button type="button" onClick={(e) => select(first, e.currentTarget)} aria-current={active || undefined} className="w-full text-left">
                                <div className="flex flex-wrap items-center gap-2">
                                  <SeverityBadge severity={first.severity} />
                                  <span className="font-medium">{first.title}</span>
                                  <span className="rounded bg-raised px-1.5 py-0.5 font-mono text-body-sm">{first.ruleId}</span>
                                  <span className="font-mono text-body-sm text-muted">{first.analyzerId}</span>
                                  <ConfidenceBadge confidence={first.confidence} />
                                </div>
                                <p className="mt-1 font-mono text-body-sm text-fg-2 [overflow-wrap:anywhere]" title={first.filePath ?? undefined}>
                                  {middleTruncate(first.filePath ?? 'No file')}
                                  {first.startLine != null ? `:${first.startLine}` : ''}
                                </p>
                                {isSecretAnalyzer(first) && typeof first.evidence?.excerpt === 'string' && (
                                  <p className="mt-1 flex items-center gap-2 font-mono text-body-sm">
                                    <code className="rounded bg-code px-1.5 py-0.5">{first.evidence.excerpt}</code>
                                    <Badge tone="neutral" icon="lock">Redacted</Badge>
                                  </p>
                                )}
                              </button>
                              {row.length > 1 && <p className="mt-1 text-body-sm text-muted">Also reported by {row.slice(1).map((f) => f.analyzerId).join(', ')} at the same location.</p>}
                            </li>
                          )
                        })}
                      </ul>
                    )}
                  </section>
                )
              })}
            </div>
          )}
          {data && (
            <>
              {(q || analyzer) && <p className="text-body-sm text-muted">Search and analyzer filters are applied by the server across all pages.</p>}
              <Pagination page={data.page} totalPages={data.totalPages} totalElements={data.totalElements} shown={data.items.length} onPage={(p) => set({ page: String(p) })} />
            </>
          )}
        </div>
        {selected && (
          <Drawer
            open
            overlay={!desktop}
            ariaLabel="Finding detail"
            title={`#${selected.fingerprint.slice(0, 8)}`}
            returnFocusTo={returnTo}
            onClose={() => {
              setSelectedId(null)
              setDrawerClosed(true)
            }}
          >
            <div
              onKeyDown={(e) => {
                const target = e.target as HTMLElement
                if (['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
                if (e.key === 'j' || e.key === 'J') move(1)
                if (e.key === 'k' || e.key === 'K') move(-1)
              }}
            >
              <FindingDetailPanel
                finding={selected}
                repositoryId={repositoryId}
                snapshotId={snapshotId}
                commitSha={commitSha}
                position={{ index: selectedIndex, total: flat.length }}
                onPrev={() => move(-1)}
                onNext={() => move(1)}
              />
            </div>
          </Drawer>
        )}
      </div>
    </div>
  )
}
