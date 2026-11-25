import type { ReactNode } from 'react'
import Icon from './icons'

/** Card with the design's header pattern: icon, title, meta and actions. */
export function Card({
  icon,
  title,
  meta,
  actions,
  children,
  className,
  tier = 1,
  as: Tag = 'section',
}: {
  icon?: string
  title?: ReactNode
  meta?: ReactNode
  actions?: ReactNode
  children?: ReactNode
  className?: string
  tier?: 1 | 2
  as?: 'section' | 'div' | 'article'
}) {
  return (
    <Tag className={`rounded-lg border border-line ${tier === 1 ? 'bg-surface' : 'bg-raised'} ${className ?? ''}`}>
      {(title || actions) && (
        <header className="flex flex-wrap items-center justify-between gap-2 px-4 pt-3 pb-2">
          <div className="flex min-w-0 items-center gap-2">
            {icon && <Icon name={icon} size={18} className="text-fg-2" />}
            {title && <h2 className="truncate text-headline-md font-semibold tracking-tight">{title}</h2>}
            {meta && <span className="text-body-sm text-muted">{meta}</span>}
          </div>
          {actions && <div className="flex items-center gap-2">{actions}</div>}
        </header>
      )}
      <div className="px-4 pb-4">{children}</div>
    </Tag>
  )
}

export function StatCard({
  label,
  value,
  sub,
  tag,
  footer,
  className,
}: {
  label: string
  value: ReactNode
  sub?: ReactNode
  tag?: ReactNode
  footer?: ReactNode
  className?: string
}) {
  return (
    <div className={`flex min-w-0 flex-col gap-1 rounded-lg border border-line bg-surface p-3 ${className ?? ''}`}>
      <div className="flex items-center justify-between gap-2">
        <span className="font-mono text-label-sm uppercase tracking-wider text-muted">{label}</span>
        {tag}
      </div>
      <div className="text-headline-md font-semibold">{value}</div>
      {sub && <div className="truncate text-body-sm text-fg-2">{sub}</div>}
      {footer && <div className="mt-auto pt-1 text-body-sm text-muted">{footer}</div>}
    </div>
  )
}

export function KeyValueList({ items }: { items: { label: string; value: ReactNode }[] }) {
  return (
    <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-body-md">
      {items.map((item) => (
        <div key={item.label} className="contents">
          <dt className="text-muted">{item.label}</dt>
          <dd className="min-w-0 break-words">{item.value}</dd>
        </div>
      ))}
    </dl>
  )
}

export function Skeleton({ className = 'h-4 w-full' }: { className?: string }) {
  return <div aria-hidden="true" className={`skeleton ${className}`} />
}

export function SkeletonRows({ rows = 3, label = 'Loading' }: { rows?: number; label?: string }) {
  return (
    <div role="status" aria-label={label} className="space-y-2">
      {Array.from({ length: rows }, (_, i) => (
        <Skeleton key={i} className="h-14 w-full" />
      ))}
      <span className="sr-only">{label}...</span>
    </div>
  )
}

export function ProgressBar({ value, max = 100, label, tone = 'primary' }: { value: number; max?: number; label: string; tone?: 'primary' | 'ai' | 'success' }) {
  const pct = max > 0 ? Math.min(100, Math.max(0, (value / max) * 100)) : 0
  const color = tone === 'ai' ? 'bg-ai' : tone === 'success' ? 'bg-[color:var(--t-success)]' : 'bg-primary'
  return (
    <div role="progressbar" aria-label={label} aria-valuemin={0} aria-valuemax={max} aria-valuenow={value} className="h-1.5 w-full overflow-hidden rounded bg-overlay">
      <div className={`h-full ${color}`} style={{ width: `${pct}%` }} />
    </div>
  )
}

/** Quiet, informative state for "Not analyzed / Not supported / AI unavailable": a reason and an optional link, never an error. */
export function UnavailableState({ title, reason, action, icon = 'info' }: { title: string; reason?: ReactNode; action?: ReactNode; icon?: string }) {
  return (
    <div role="status" className="flex items-start gap-3 rounded-lg border border-dashed border-line-strong bg-surface p-4 text-fg-2">
      <Icon name={icon} size={18} className="mt-0.5 text-muted" />
      <div className="min-w-0">
        <p className="text-body-md font-medium text-fg">{title}</p>
        {reason && <p className="mt-0.5 text-body-md text-fg-2">{reason}</p>}
        {action && <div className="mt-2">{action}</div>}
      </div>
    </div>
  )
}

export function Notice({ tone = 'info', children, className }: { tone?: 'info' | 'warning'; children: ReactNode; className?: string }) {
  return (
    <div className={`tone ${tone === 'warning' ? 'tone-warning' : 'tone-info'} flex items-start gap-2 rounded-lg px-3 py-2 text-body-md ${className ?? ''}`}>
      <Icon name={tone === 'warning' ? 'warning' : 'info'} size={14} className="mt-0.5" />
      <div className="min-w-0 text-fg">{children}</div>
    </div>
  )
}
