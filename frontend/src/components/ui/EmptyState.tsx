import type { ReactNode } from 'react'
import Icon from './icons'

export default function EmptyState({
  title,
  description,
  action,
  icon = 'logo',
}: {
  title: string
  description?: string
  action?: ReactNode
  icon?: string
}) {
  return (
    <div className="rounded-lg border border-dashed border-line-strong bg-surface p-10 text-center">
      <div className="mx-auto mb-3 flex size-12 items-center justify-center rounded-lg bg-raised text-fg-2">
        <Icon name={icon} size={24} />
      </div>
      <h2 className="text-headline-md font-semibold">{title}</h2>
      {description && <p className="mx-auto mt-2 max-w-xl text-body-md text-fg-2">{description}</p>}
      {action && <div className="mt-4 flex justify-center">{action}</div>}
    </div>
  )
}
