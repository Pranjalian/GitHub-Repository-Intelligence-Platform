import { Link, NavLink, useLocation, useSearchParams } from 'react-router'
import { useRepoContext } from '../../hooks/useRepoContext'
import { useRepositoryStats } from '../../hooks/queries'
import { shortSha } from '../../lib/format'
import { useIsDesktop } from '../../lib/useMediaQuery'
import { Tooltip } from '../ui/Controls'
import { IconButton } from '../ui/Button'
import Icon from '../ui/icons'
import { DIAGNOSTICS, navBadge } from './navModel'

const itemClass = (active: boolean) =>
  `group flex h-8 items-center gap-2.5 rounded px-2.5 text-body-md ${active ? 'bg-raised font-medium text-fg' : 'text-fg-2 hover:bg-raised hover:text-fg'}`

function AiMarker() {
  return <span className="tone tone-ai rounded px-1 font-mono text-label-sm font-semibold">AI</span>
}

/**
 * The navigation: global workspace, the open repository (active context card, the report tabs as
 * "Diagnostics", and the analytical engine). Items for unavailable features stay visible and say why.
 */
export default function Sidebar({ collapsed = false, onToggle, onNavigate }: { collapsed?: boolean; onToggle?: () => void; onNavigate?: () => void }) {
  const ctx = useRepoContext()
  const location = useLocation()
  const [searchParams] = useSearchParams()
  const stats = useRepositoryStats()
  const desktop = useIsDesktop()
  const rail = collapsed && desktop
  const snapshotQuery = ctx.selected ? `snapshot=${ctx.selected.id}` : ''
  const withSnapshot = (path: string, extra = '') => {
    const query = [extra, snapshotQuery].filter(Boolean).join('&')
    return query ? `${path}?${query}` : path
  }
  const base = ctx.repositoryId ? `/repositories/${ctx.repositoryId}` : ''
  const onReport = location.pathname === base
  const activeTab = searchParams.get('tab') ?? 'overview'
  const cap = ctx.report?.sections ?? []
  const analyzedArch = cap.find((s) => s.category === 'ARCHITECTURE')?.status
  const archOk = analyzedArch === 'COMPLETED' || analyzedArch === 'COMPLETED_WITH_WARNINGS'
  const aiOn = ctx.completed
  const label = (text: string) => (rail ? <span className="sr-only">{text}</span> : <span className="truncate">{text}</span>)

  return (
    <nav aria-label="Main" className={`flex h-full flex-col gap-1 overflow-y-auto border-r border-line bg-surface p-2 ${rail ? 'w-14' : 'w-60'}`}>
      <div className="flex items-center justify-between gap-2 px-1.5 py-2">
        <Link to="/" onClick={onNavigate} className="flex min-w-0 items-center gap-2 font-semibold tracking-tight" aria-label="RepoIntel home">
          <Icon name="logo" size={20} className="text-primary-text" />
          {!rail && <span className="truncate text-headline-md">RepoIntel</span>}
        </Link>
        {!rail && <span className="tone tone-neutral rounded px-1.5 font-mono text-label-sm">LOCAL</span>}
      </div>
      {!rail && <p className="px-2.5 pb-2 text-body-sm text-muted">Local single-user · no auth</p>}

      <p className={`px-2.5 pt-2 font-mono text-label-sm uppercase tracking-wider text-muted ${rail ? 'sr-only' : ''}`}>Global workspace</p>
      <NavLink to="/" end onClick={onNavigate} className={({ isActive }) => itemClass(isActive)} aria-label="Dashboard">
        <Icon name="dashboard" size={16} />
        {label('Dashboard')}
      </NavLink>
      <NavLink to="/repositories/new" onClick={onNavigate} className={({ isActive }) => itemClass(isActive)} aria-label="Add Repository">
        <Icon name="add" size={16} />
        {label('Add Repository')}
      </NavLink>
      <NavLink to="/settings/ai" onClick={onNavigate} className={({ isActive }) => itemClass(isActive)} aria-label="AI Settings">
        <Icon name="tune" size={16} />
        {label('AI Settings')}
      </NavLink>

      {ctx.repositoryId && ctx.repository && (
        <>
          <Link
            to={withSnapshot(base)}
            onClick={onNavigate}
            aria-label={`Active context: ${ctx.repository.owner}/${ctx.repository.name}`}
            className="mt-3 block rounded-lg border border-line bg-raised px-2.5 py-2"
          >
            <span className={`flex items-center justify-between font-mono text-label-sm uppercase tracking-wider text-muted ${rail ? 'sr-only' : ''}`}>
              Active context <Icon name="lock" size={12} />
            </span>
            {!rail && (
              <>
                <span className="mt-1 block truncate text-body-md font-semibold">{ctx.repository.name}</span>
                <span className="flex items-center gap-1 font-mono text-body-sm text-fg-2">
                  <Icon name="commit" size={12} />
                  {ctx.selected ? shortSha(ctx.selected.commitSha) : '-------'} · snapshot
                </span>
              </>
            )}
          </Link>

          <p className={`px-2.5 pt-3 font-mono text-label-sm uppercase tracking-wider text-muted ${rail ? 'sr-only' : ''}`}>Diagnostics</p>
          {DIAGNOSTICS.map((item) => {
            const badge = navBadge(item, ctx.report, ctx.coverage)
            const isArch = item.id === 'architecture'
            const active = isArch ? location.pathname === `${base}/arch` || (onReport && activeTab === 'architecture') : onReport && activeTab === item.id
            const to = isArch ? withSnapshot(`${base}/arch`) : withSnapshot(base, item.id === 'overview' ? '' : `tab=${item.id}`)
            return (
              <NavLink
                key={item.id}
                to={to}
                onClick={onNavigate}
                aria-current={active ? 'page' : undefined}
                aria-label={item.label}
                className={() => itemClass(active)}
              >
                <Icon name={item.icon} size={16} />
                {label(item.label)}
                {!rail && badge?.text && <span className="ml-auto rounded bg-overlay px-1.5 font-mono text-label-sm text-fg">{badge.text}</span>}
                {!rail && badge && !badge.text && (
                  <span className="ml-auto" title={badge.reason}>
                    <Tooltip text={badge.reason ?? 'Not analyzed'}>
                      <span tabIndex={0} aria-label={`${item.label}: ${badge.reason ?? 'not analyzed'}`} className="block size-2 rounded-full border border-muted" />
                    </Tooltip>
                  </span>
                )}
              </NavLink>
            )
          })}

          <p className={`px-2.5 pt-3 font-mono text-label-sm uppercase tracking-wider text-muted ${rail ? 'sr-only' : ''}`}>Analytical engine</p>
          {[
            { to: 'search', icon: 'search', text: 'Code Search', ai: false, note: undefined as string | undefined },
            { to: 'qa', icon: 'chat', text: 'Ask (Q&A)', ai: true, note: undefined },
            { to: 'impact', icon: 'impact', text: 'Impact Analysis', ai: false, note: undefined },
            { to: 'investigations', icon: 'investigation', text: 'Investigations', ai: true, note: undefined },
          ].map((item) => (
            <NavLink
              key={item.to}
              to={withSnapshot(`${base}/${item.to}`)}
              onClick={onNavigate}
              aria-label={item.text}
              className={({ isActive }) => itemClass(isActive)}
              title={!aiOn ? 'Available once this commit has a completed analysis' : undefined}
            >
              <Icon name={item.icon} size={16} />
              {label(item.text)}
              {!rail && item.ai && (
                <span className="ml-auto">
                  <AiMarker />
                </span>
              )}
            </NavLink>
          ))}
        </>
      )}
      {ctx.repositoryId && !archOk && ctx.report && !rail && (
        <p className="px-2.5 pt-1 text-body-sm text-muted">Architecture analysis is not available for this commit.</p>
      )}

      <div className="mt-auto space-y-2 pt-3">
        <p className={`flex items-center gap-2 px-2.5 text-body-sm text-muted ${rail ? 'sr-only' : ''}`} aria-live="polite">
          <span className={`size-2 rounded-full ${stats.data && stats.data.activeRuns > 0 ? 'pulse bg-[color:var(--t-running)]' : 'bg-[color:var(--t-neutral)]'}`} />
          {stats.data ? (stats.data.activeRuns > 0 ? `${stats.data.activeRuns} ${stats.data.activeRuns === 1 ? 'analysis' : 'analyses'} running` : 'No analysis running') : 'Status unavailable'}
        </p>
        {onToggle && desktop && (
          <IconButton icon={rail ? 'chevron-right' : 'chevron-left'} label={rail ? 'Expand sidebar' : 'Collapse sidebar'} onClick={onToggle} />
        )}
      </div>
    </nav>
  )
}
