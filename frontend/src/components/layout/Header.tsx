import { Link, useLocation } from 'react-router'
import { useAIProviders } from '../../hooks/queries'
import { useRepoContext } from '../../hooks/useRepoContext'
import { formatDateTime, shortSha } from '../../lib/format'
import { useTheme, type ThemeChoice } from '../../lib/theme'
import { IconButton } from '../ui/Button'
import CopyButton from '../ui/CopyButton'
import Icon from '../ui/icons'
import RunStatusLabel from '../ui/RunStatusLabel'
import type { AIProviders } from '../../types/api'

const PAGE_NAMES: Record<string, string> = {
  '/': 'Repositories',
  '/repositories/new': 'Add Repository',
  '/settings/ai': 'AI Settings',
}

/** The AI status pill: off, local, hosted or unavailable, from GET /api/v1/ai/providers. */
export function aiPill(data: AIProviders | undefined, failed: boolean): { text: string; tone: 'off' | 'local' | 'hosted' | 'unavailable' } {
  if (failed || !data) return { text: 'AI unavailable', tone: 'unavailable' }
  if (!data.enabled) return { text: 'AI off', tone: 'off' }
  const provider = data.providers.find((p) => p.id === data.defaultProvider)
  if (!data.available || !provider) return { text: 'AI unavailable', tone: 'unavailable' }
  const name = provider.id.charAt(0).toUpperCase() + provider.id.slice(1)
  const text = `${provider.dataHandling === 'LOCAL' ? 'Local' : 'Hosted'}: ${name}${provider.chatModel ? ` (${provider.chatModel})` : ''}`
  return { text, tone: provider.dataHandling === 'LOCAL' ? 'local' : 'hosted' }
}

const NEXT: Record<ThemeChoice, ThemeChoice> = { dark: 'light', light: 'system', system: 'dark' }
const ICONS: Record<ThemeChoice, string> = { dark: 'moon', light: 'sun', system: 'monitor' }

export default function Header({ onMenu }: { onMenu?: () => void }) {
  const ctx = useRepoContext()
  const location = useLocation()
  const providers = useAIProviders()
  const pill = aiPill(providers.data, providers.isError)
  const { choice, setChoice } = useTheme()
  const run = ctx.selected?.latestRun
  const pageName = PAGE_NAMES[location.pathname] ?? (location.pathname.startsWith('/investigations') ? 'Investigations' : '')
  const dot = { off: 'bg-[color:var(--t-neutral)]', local: 'bg-[color:var(--t-success)]', hosted: 'bg-[color:var(--t-warning)]', unavailable: 'bg-[color:var(--t-neutral)]' }[pill.tone]

  return (
    <header className="flex min-h-12 flex-wrap items-center justify-between gap-x-4 gap-y-1 border-b border-line bg-surface px-4 py-1.5">
      <div className="flex min-w-0 items-center gap-2">
        {onMenu && <IconButton icon="menu" label="Open navigation" onClick={onMenu} />}
        {ctx.repository ? (
          <nav aria-label="Breadcrumb" className="flex min-w-0 flex-wrap items-center gap-x-2 text-body-md">
            <Link to="/" className="text-muted hover:text-fg">
              {ctx.repository.owner}
            </Link>
            <span className="text-muted">/</span>
            <Link to={`/repositories/${ctx.repository.id}${ctx.selected ? `?snapshot=${ctx.selected.id}` : ''}`} className="font-semibold">
              {ctx.repository.name}
            </Link>
            {ctx.selected && (
              <>
                <span className="text-muted">:</span>
                <span className="inline-flex items-center gap-1 font-mono text-body-sm">
                  {shortSha(ctx.selected.commitSha)}
                  <CopyButton value={ctx.selected.commitSha} label="Copy SHA" compact />
                </span>
                {run?.completedAt && <span className="text-body-sm text-muted">· {formatDateTime(run.completedAt)}</span>}
                {run && <RunStatusLabel status={run.status} />}
              </>
            )}
          </nav>
        ) : (
          <h1 className="text-body-lg font-semibold">{pageName || 'RepoIntel'}</h1>
        )}
      </div>
      <div className="flex items-center gap-2">
        <Link to="/settings/ai" aria-label={`AI status: ${pill.text}`} className="tone tone-ai inline-flex h-7 items-center gap-2 rounded-full px-3 text-body-sm">
          <span className={`size-2 rounded-full ${dot}`} />
          {pill.text}
        </Link>
        <span className="hidden items-center gap-1 text-body-sm text-muted md:flex">
          <Icon name="shield" size={14} />
          Local · read-only analysis
        </span>
        <IconButton icon={ICONS[choice]} label={`Theme: ${choice}. Switch to ${NEXT[choice]}`} onClick={() => setChoice(NEXT[choice])} />
      </div>
    </header>
  )
}
