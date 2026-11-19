export default function ErrorState({
  title = 'Something went wrong',
  message,
  requestId,
}: {
  title?: string
  message: string
  requestId?: string
}) {
  return (
    <div role="alert" className="rounded-lg border border-red-200 bg-red-50 p-4 text-red-900">
      <h2 className="text-sm font-semibold">{title}</h2>
      <p className="mt-1 text-sm">{message}</p>
      {requestId && <p className="mt-2 text-xs text-red-700">Request id: {requestId}</p>}
    </div>
  )
}
