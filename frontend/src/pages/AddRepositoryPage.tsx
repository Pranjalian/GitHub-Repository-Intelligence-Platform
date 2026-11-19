import { useId, useState, type FormEvent } from 'react'
import { ApiError } from '../api/client'
import { useAnalyzeRepository } from '../hooks/useAnalyzeRepository'
import { formatDateTime } from '../lib/format'
import { parseGitHubUrl } from '../lib/githubUrl'

function describeError(error: Error): string {
  if (!(error instanceof ApiError)) {
    return error.message
  }
  if (error.status === 429) {
    const when = error.resetAt
      ? `after ${formatDateTime(error.resetAt)}`
      : error.retryAfterSeconds
        ? `in about ${error.retryAfterSeconds} seconds`
        : 'later'
    return `${error.message} Try again ${when}.`
  }
  return error.message
}

export default function AddRepositoryPage() {
  const inputId = useId()
  const errorId = useId()
  const [url, setUrl] = useState('')
  const [forceRescan, setForceRescan] = useState(false)
  const [clientError, setClientError] = useState<string | null>(null)
  const analyze = useAnalyzeRepository()

  function submit(event: FormEvent) {
    event.preventDefault()
    const parsed = parseGitHubUrl(url)
    if (!parsed.ok) {
      setClientError(parsed.error)
      return
    }
    setClientError(null)
    analyze.mutate({ url: url.trim(), forceRescan })
  }

  const message = clientError ?? (analyze.error ? describeError(analyze.error) : null)

  return (
    <form onSubmit={submit} className="max-w-xl" noValidate>
      <h2 className="text-xl font-semibold">Add Repository</h2>
      <p className="mt-1 text-sm text-slate-600">Enter the URL of a public GitHub repository.</p>
      <div className="mt-4">
        <label htmlFor={inputId} className="block text-sm font-medium">
          Repository URL
        </label>
        <input
          id={inputId}
          type="text"
          value={url}
          onChange={(event) => {
            setUrl(event.target.value)
            setClientError(null)
          }}
          placeholder="https://github.com/owner/repo"
          aria-invalid={message ? true : undefined}
          aria-describedby={message ? errorId : undefined}
          className="mt-1 w-full rounded border border-slate-300 px-3 py-2 text-sm"
        />
      </div>
      <label className="mt-3 flex items-center gap-2 text-sm">
        <input type="checkbox" checked={forceRescan} onChange={(event) => setForceRescan(event.target.checked)} />
        Force full rescan
      </label>
      {message && (
        <p id={errorId} role="alert" className="mt-3 rounded border border-red-200 bg-red-50 p-3 text-sm text-red-900">
          {message}
        </p>
      )}
      <button
        type="submit"
        disabled={analyze.isPending}
        className="mt-4 rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white disabled:opacity-60"
      >
        {analyze.isPending ? 'Analyzing…' : 'Analyze'}
      </button>
    </form>
  )
}
