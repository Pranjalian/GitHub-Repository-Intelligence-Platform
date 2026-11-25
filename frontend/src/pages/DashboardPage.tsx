import { useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate } from 'react-router'
import { analyzeRepository } from '../api/repositories'
import { Badge, Button, CopyButton, EmptyState, ErrorState, FilterChip, Icon, Input, ProgressBar, RunStatusLabel, Select, SkeletonRows, StatCard } from '../components/ui'
import { Pagination } from '../components/ui/Data'
import { useRepositories, useRepositoryStats } from '../hooks/queries'
import { formatDateTime, relativeTime, shortSha } from '../lib/format'
import type { Repository, RepositorySummary, RunStatus, Severity } from '../types/api'

const PAGE_SIZE = 20
type StatusFilter = 'all' | 'completed' | 'active' | 'failed'

const statusOf = (repository: Repository): RunStatus | undefined => repository.latestSnapshot?.latestRun?.status
const isActive = (s?: RunStatus) => s === 'QUEUED' || s === 'INGESTING' || s === 'ANALYZING'
const PHASE: Record<string, number> = { QUEUED: 0, INGESTING: 1, ANALYZING: 2 }

/** Findings of the analyzed categories summed per severity; null when neither was analyzed (never zeros). */
export function combinedSeverity(summary: RepositorySummary | null | undefined): Record<Severity, number> | null {
  if (!summary) return null
  const analyzed = Object.values(summary.findingsBySeverity).filter((c): c is Record<Severity, number> => !!c)
  if (analyzed.length === 0) return null
  const total = { CRITICAL: 0, HIGH: 0, MEDIUM: 0, LOW: 0, INFO: 0 } as Record<Severity, number>
  for (const counts of analyzed) for (const key of Object.keys(total) as Severity[]) total[key] += counts[key] ?? 0
  return total
}

function SeverityChips({ repository }: { repository: Repository }) {
  const status = statusOf(repository)
  if (isActive(status)) {
    return (
      <span className="flex flex-wrap gap-1 text-body-sm text-muted" aria-label="Findings: not available while the analysis runs">
        {['Crit', 'High', 'Med', 'Low'].map((n) => (
          <span key={n} className="rounded bg-raised px-1.5 py-0.5">-- {n}</span>
        ))}
      </span>
    )
  }
  const counts = combinedSeverity(repository.summary)
  if (!counts) {
    return <span className="text-body-sm text-muted">Not analyzed</span>
  }
  const chips: [string, Severity, string][] = [
    ['Crit', 'CRITICAL', 'tone-critical'],
    ['High', 'HIGH', 'tone-high'],
    ['Med', 'MEDIUM', 'tone-medium'],
    ['Low', 'LOW', 'tone-low'],
  ]
  return (
    <span className="flex flex-wrap gap-1" aria-label="Findings by severity">
      {chips.map(([short, key, tone]) => (
        <span key={key} className={`rounded px-1.5 py-0.5 text-body-sm ${counts[key] > 0 ? `tone ${tone}` : 'bg-raised text-muted'}`}>
          {counts[key]} {short}
        </span>
      ))}
    </span>
  )
}

function IndexMarker({ repository }: { repository: Repository }) {
  const state = repository.summary?.indexStatus
  if (state === 'COMPLETED' || state === 'COMPLETED_WITH_WARNINGS') {
    return <span className="inline-flex items-center gap-1 rounded bg-raised px-2 py-1 text-body-sm"><Icon name="search-index" size={14} />Indexed for search</span>
  }
  if (state === 'QUEUED' || state === 'INDEXING') {
    return <span className="inline-flex items-center gap-1 rounded bg-raised px-2 py-1 text-body-sm text-fg-2"><Icon name="hourglass" size={14} />Indexing...</span>
  }
  return <span className="inline-flex items-center gap-1 rounded px-2 py-1 text-body-sm text-muted"><Icon name="search-index" size={14} />Not indexed</span>
}

function RepositoryRow({ repository }: { repository: Repository }) {
  const navigate = useNavigate()
  const [retrying, setRetrying] = useState(false)
  const [retryError, setRetryError] = useState<string | null>(null)
  const snapshot = repository.latestSnapshot
  const run = snapshot?.latestRun
  const status = run?.status
  const language = repository.summary?.primaryLanguage
  const href = `/repositories/${repository.id}`

  async function retry() {
    setRetrying(true)
    setRetryError(null)
    try {
      const response = await analyzeRepository(repository.canonicalUrl, true)
      navigate(`/repositories/${response.repositoryId}?snapshot=${response.snapshotId}&run=${response.analysisRunId}`)
    } catch (error) {
      setRetryError(error instanceof Error ? error.message : 'Retry failed')
      setRetrying(false)
    }
  }

  return (
    <li className="relative rounded-lg border border-line bg-surface p-3 hover:bg-raised">
      <div className="grid items-center gap-x-4 gap-y-2 md:grid-cols-[minmax(0,2.2fr)_minmax(0,1.2fr)_minmax(0,1.6fr)_auto]">
        <div className="min-w-0">
          <Link to={href} className="block truncate text-body-lg after:absolute after:inset-0" aria-label={`${repository.owner}/${repository.name}`}>
            <span className="text-fg-2">{repository.owner} / </span>
            <span className="font-semibold">{repository.name}</span>
          </Link>
          <div className="mt-1 flex flex-wrap items-center gap-2 text-body-sm text-muted">
            {language && <Badge tone="neutral">{language}</Badge>}
            {snapshot && (
              <span className="relative inline-flex items-center gap-1 rounded bg-canvas px-1.5 py-0.5 font-mono">
                <Icon name="commit" size={12} />
                {shortSha(snapshot.commitSha)}
                <CopyButton value={snapshot.commitSha} label="Copy SHA" compact />
              </span>
            )}
            {isActive(status) ? (
              <span>Started {relativeTime(run?.startedAt ?? run?.requestedAt)}</span>
            ) : status === 'FAILED' ? (
              <span title={formatDateTime(run?.completedAt)}>Failed {relativeTime(run?.completedAt)}</span>
            ) : (
              run?.completedAt && <span title={formatDateTime(run.completedAt)}>Analyzed {relativeTime(run.completedAt)}</span>
            )}
          </div>
        </div>
        <div className="min-w-0 space-y-1">
          <RunStatusLabel status={status} />
          {isActive(status) && status && (
            <div className="w-32">
              <ProgressBar value={PHASE[status] + 1} max={3} label={`Run phase: ${status.toLowerCase()}`} />
            </div>
          )}
        </div>
        <SeverityChips repository={repository} />
        <div className="relative flex flex-wrap items-center justify-end gap-2">
          <IndexMarker repository={repository} />
        </div>
      </div>
      {status === 'FAILED' && (
        <div className="relative mt-2 flex flex-wrap items-center gap-3 border-t border-line pt-2 text-body-md">
          <span className="min-w-0 flex-1 text-fg-2">{run?.failureReason ?? 'The analysis failed.'}</span>
          <Button variant="secondary" icon="refresh" busy={retrying} onClick={retry}>
            Retry
          </Button>
          {retryError && <span role="alert" className="text-[color:var(--t-failed-fg)]">{retryError}</span>}
        </div>
      )}
    </li>
  )
}

export default function DashboardPage() {
  const [page, setPage] = useState(0)
  const { data, error, isPending, refetch } = useRepositories(page, PAGE_SIZE)
  const stats = useRepositoryStats()
  const [text, setText] = useState('')
  const [status, setStatus] = useState<StatusFilter>('all')
  const [language, setLanguage] = useState('')
  const filterRef = useRef<HTMLInputElement>(null)

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement
      if (event.key === '/' && !['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
        event.preventDefault()
        filterRef.current?.focus()
      }
    }
    document.addEventListener('keydown', onKey)
    return () => document.removeEventListener('keydown', onKey)
  }, [])

  const languages = useMemo(
    () => [...new Set((data?.items ?? []).map((r) => r.summary?.primaryLanguage).filter((l): l is string => !!l))].sort(),
    [data],
  )
  const visible = useMemo(() => {
    const needle = text.trim().toLowerCase()
    return (data?.items ?? []).filter((r) => {
      const s = statusOf(r)
      if (status === 'completed' && !(s === 'COMPLETED' || s === 'COMPLETED_WITH_WARNINGS')) return false
      if (status === 'active' && !isActive(s)) return false
      if (status === 'failed' && s !== 'FAILED') return false
      if (language && r.summary?.primaryLanguage !== language) return false
      if (!needle) return true
      return `${r.owner}/${r.name}`.toLowerCase().includes(needle) || (r.latestSnapshot?.commitSha ?? '').toLowerCase().startsWith(needle)
    })
  }, [data, text, status, language])

  if (isPending) {
    return <SkeletonRows rows={4} label="Loading repositories" />
  }
  if (error) {
    return <ErrorState title="Could not load repositories" message={error.message} action={<Button onClick={() => refetch()}>Retry</Button>} />
  }
  if (data.totalElements === 0) {
    return (
      <EmptyState
        title="No repositories analyzed yet"
        description="Submit a public GitHub repository to see its technology stack, structure and findings."
        action={
          <div className="flex flex-col items-center gap-3">
            <Link to="/repositories/new">
              <Button variant="primary" icon="add">Add Repository</Button>
            </Link>
            <Link to="/repositories/new?example=spring-projects/spring-petclinic" className="font-mono text-body-sm text-primary-text hover:underline">
              Try spring-projects/spring-petclinic
            </Link>
          </div>
        }
      />
    )
  }

  const s = stats.data
  const filtered = text !== '' || status !== 'all' || language !== ''

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <p className="font-mono text-label-sm uppercase tracking-wider text-primary-text">Workspace / {data.totalElements} {data.totalElements === 1 ? 'repository' : 'repositories'}</p>
          <h1 className="text-headline-xl font-semibold tracking-tight">Repositories</h1>
        </div>
        <div className="flex items-center gap-2">
          <Input icon="search" hint="/" aria-label="Filter repositories" placeholder="Filter repositories..." value={text} onChange={(e) => setText(e.target.value)} className="w-64" inputRef={filterRef} />
          <Link to="/repositories/new">
            <Button variant="primary" icon="add">Add Repository</Button>
          </Link>
        </div>
      </header>

      <section aria-label="Summary" className="grid gap-3 sm:grid-cols-2 xl:grid-cols-4">
        <StatCard label="Total analyzed" value={s ? s.repositoriesAnalyzed : 'Unavailable'} sub={s ? `of ${s.repositoriesTotal} submitted` : undefined} />
        <StatCard label="Indexed for search" value={s ? <>{s.indexedForSearch}<span className="text-body-md text-muted"> / {s.repositoriesAnalyzed}</span></> : 'Unavailable'} />
        <StatCard label="Active findings" value={s ? (s.openFindings == null ? 'Unavailable' : s.openFindings) : 'Unavailable'} sub={s && s.openFindings == null ? 'No analyzed finding category yet' : 'Code quality and security, latest analysis'} />
        <StatCard label="Engine status" value={s ? (s.activeRuns > 0 ? `${s.activeRuns} Running` : 'Idle') : 'Unavailable'} />
      </section>

      <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Filters">
        {([['all', 'All'], ['completed', 'Completed'], ['active', 'Active'], ['failed', 'Failed']] as [StatusFilter, string][]).map(([id, label]) => (
          <FilterChip key={id} selected={status === id} onClick={() => setStatus(id)}>{label}</FilterChip>
        ))}
        <Select label="Language" value={language} onChange={(e) => setLanguage(e.target.value)}>
          <option value="">All languages</option>
          {languages.map((l) => <option key={l} value={l}>{l}</option>)}
        </Select>
      </div>

      {visible.length === 0 ? (
        filtered && (
          <EmptyState
            icon="filter"
            title="No repositories match these filters"
            action={<Button onClick={() => { setText(''); setStatus('all'); setLanguage('') }}>Clear filters</Button>}
          />
        )
      ) : (
        <ul aria-label="Repositories" className="relative space-y-2">
          {visible.map((r) => (
            <RepositoryRow key={r.id} repository={r} />
          ))}
        </ul>
      )}
      <Pagination page={page} totalPages={data.totalPages} totalElements={data.totalElements} shown={data.items.length} onPage={setPage} />
    </div>
  )
}
