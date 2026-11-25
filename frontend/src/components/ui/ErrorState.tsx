import type { ReactNode } from 'react'
import Icon from './icons'

export default function ErrorState({
  title = 'Something went wrong',
  message,
  requestId,
  action,
}: {
  title?: string
  message: string
  requestId?: string
  action?: ReactNode
}) {
  return (
    <div role="alert" className="tone tone-failed flex items-start gap-3 rounded-lg p-4">
      <Icon name="error" size={18} className="mt-0.5" />
      <div className="min-w-0">
        <h2 className="text-body-md font-semibold">{title}</h2>
        <p className="mt-1 text-body-md text-fg">{message}</p>
        {requestId && <p className="mt-2 font-mono text-body-sm text-fg-2">Request id: {requestId}</p>}
        {action && <div className="mt-2">{action}</div>}
      </div>
    </div>
  )
}
