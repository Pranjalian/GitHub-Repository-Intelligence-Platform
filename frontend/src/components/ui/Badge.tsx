import type { ReactNode } from 'react'

export type BadgeTone = 'neutral' | 'success' | 'warning' | 'danger' | 'info'

const tones: Record<BadgeTone, string> = {
  neutral: 'bg-slate-100 text-slate-700',
  success: 'bg-green-100 text-green-800',
  warning: 'bg-amber-100 text-amber-800',
  danger: 'bg-red-100 text-red-800',
  info: 'bg-blue-100 text-blue-800',
}

export default function Badge({ tone = 'neutral', children }: { tone?: BadgeTone; children: ReactNode }) {
  return (
    <span className={`inline-flex items-center rounded-full px-2 py-0.5 text-xs font-medium ${tones[tone]}`}>
      {children}
    </span>
  )
}
