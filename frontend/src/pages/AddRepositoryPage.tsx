import { useId, useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import { Badge, Button, Checkbox, Icon } from '../components/ui'
import { useAnalyzeRepository } from '../hooks/useAnalyzeRepository'
import { formatDateTime } from '../lib/format'
import { parseGitHubUrl } from '../lib/githubUrl'

const EXAMPLES = ['spring-projects/spring-petclinic', 'pallets/flask', 'expressjs/express']

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
  const helpId = useId()
  const [searchParams] = useSearchParams()
  const example = searchParams.get('example')
  const [url, setUrl] = useState(() => (example && EXAMPLES.includes(example) ? `https://github.com/${example}` : ''))
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
    <div className="mx-auto max-w-2xl space-y-4">
      <form onSubmit={submit} noValidate className="space-y-4 rounded-xl border border-line bg-surface p-6">
        <header className="space-y-1">
          <div className="flex items-center justify-between">
            <p className="font-mono text-label-sm uppercase tracking-wider text-primary-text">Ingestion pipeline</p>
            <Badge tone="success" icon="lock">Read-only</Badge>
          </div>
          <h1 className="text-headline-lg font-semibold tracking-tight">Add Repository</h1>
          <p className="text-body-md text-fg-2">Enter the URL of a public GitHub repository. It is fetched at one exact commit and analyzed statically; its code is never executed.</p>
        </header>

        <div>
          <label htmlFor={inputId} className="mb-1 block text-body-md font-medium">
            Repository URL
          </label>
          <div className="relative">
            <Icon name="link" size={16} className="pointer-events-none absolute left-2.5 top-2.5 text-muted" />
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
              aria-describedby={`${helpId}${message ? ` ${errorId}` : ''}`}
              className={`h-9 w-full rounded border bg-canvas pl-8 pr-3 font-mono text-body-md ${message ? 'border-[color:var(--t-failed)]' : 'border-line-strong'}`}
            />
          </div>
          <p id={helpId} className="mt-1 text-body-sm text-muted">A branch link such as /tree/main is accepted.</p>
          {message && (
            <p id={errorId} role="alert" className="mt-2 flex items-start gap-2 text-body-md text-[color:var(--t-failed-fg)]">
              <Icon name="error" size={16} className="mt-0.5" />
              {message}
            </p>
          )}
        </div>

        <div className="flex flex-wrap items-center gap-2" role="group" aria-label="Examples">
          <span className="text-body-sm text-muted">Try:</span>
          {EXAMPLES.map((slug) => (
            <button
              key={slug}
              type="button"
              onClick={() => {
                setUrl(`https://github.com/${slug}`)
                setClientError(null)
              }}
              className="rounded border border-line-strong px-2 py-1 font-mono text-body-sm text-fg-2 hover:bg-raised hover:text-fg"
            >
              {slug}
            </button>
          ))}
        </div>

        <Checkbox label="Force full rescan" hint="Bypass cached analysis for this commit" checked={forceRescan} onChange={(event) => setForceRescan(event.target.checked)} />

        <Button type="submit" variant="primary" busy={analyze.isPending} className="h-9">
          {analyze.isPending ? 'Analyzing…' : 'Analyze'}
        </Button>
      </form>

      <ul className="grid gap-3 text-body-sm text-fg-2 sm:grid-cols-3">
        <li className="flex gap-2 rounded-lg border border-line bg-surface p-3">
          <Icon name="globe" size={16} className="mt-0.5 shrink-0" />
          Public repositories only.
        </li>
        <li className="flex gap-2 rounded-lg border border-line bg-surface p-3">
          <Icon name="shield" size={16} className="mt-0.5 shrink-0" />
          Read-only static analysis. Code is never executed.
        </li>
        <li className="flex gap-2 rounded-lg border border-line bg-surface p-3">
          <Icon name="key" size={16} className="mt-0.5 shrink-0" />
          <span>
            A GitHub token raises the limit from 60 to 5,000 requests per hour. It is an environment setting: see the README section &quot;GitHub token&quot;

          </span>
        </li>
      </ul>
    </div>
  )
}
