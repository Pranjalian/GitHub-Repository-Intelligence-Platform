import type { ReactNode } from 'react'
import Icon from './icons'

/** Semantic tones; the first five are the legacy names still used by older callers. */
export type BadgeTone =
  | 'neutral'
  | 'success'
  | 'warning'
  | 'danger'
  | 'info'
  | 'critical'
  | 'high'
  | 'medium'
  | 'low'
  | 'running'
  | 'failed'
  | 'detected'
  | 'likely'
  | 'possible'
  | 'ai'
  | 'primary'

const toneClass: Record<BadgeTone, string> = {
  neutral: 'tone-neutral',
  success: 'tone-success',
  warning: 'tone-warning',
  danger: 'tone-failed',
  info: 'tone-running',
  critical: 'tone-critical',
  high: 'tone-high',
  medium: 'tone-medium',
  low: 'tone-low',
  running: 'tone-running',
  failed: 'tone-failed',
  detected: 'tone-detected',
  likely: 'tone-likely',
  possible: 'tone-possible',
  ai: 'tone-ai',
  primary: 'tone-primary',
}

/**
 * Chip with a semantic tint. Colour is never the only signal: pass an `icon` (and the text is always
 * present). Unknown tones fall back to neutral instead of throwing.
 */
export default function Badge({
  tone = 'neutral',
  icon,
  dashed,
  title,
  children,
}: {
  tone?: BadgeTone
  icon?: string
  dashed?: boolean
  title?: string
  children: ReactNode
}) {
  return (
    <span
      title={title}
      className={`tone ${toneClass[tone] ?? 'tone-neutral'} inline-flex h-5 items-center gap-1 whitespace-nowrap rounded px-1.5 font-mono text-label-sm font-medium uppercase tracking-wider ${dashed ? 'border-dashed' : ''}`}
    >
      {icon && <Icon name={icon} size={11} />}
      {children}
    </span>
  )
}
