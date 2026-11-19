import type { ReactNode } from 'react'

export default function EmptyState({
  title,
  description,
  action,
}: {
  title: string
  description?: string
  action?: ReactNode
}) {
  return (
    <div className="rounded-lg border border-dashed border-slate-300 bg-white p-10 text-center">
      <h2 className="text-base font-semibold">{title}</h2>
      {description && <p className="mt-2 text-sm text-slate-600">{description}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  )
}
