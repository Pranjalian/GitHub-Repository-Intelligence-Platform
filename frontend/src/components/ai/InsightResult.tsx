import { Link } from 'react-router'
import type { AIStatus, Insight, InsightEvidence } from '../../types/api'
import Button from '../ui/Button'
import CodeBlock from '../ui/CodeBlock'
import CopyButton from '../ui/CopyButton'
import Badge from '../ui/Badge'
import Icon from '../ui/icons'
import AIGeneratedBadge from './AIGeneratedBadge'
import MarkdownText from './MarkdownText'

const STATUS_TITLES: Record<AIStatus, string> = {
  OK: 'Explanation',
  UNAVAILABLE: 'AI insights are unavailable',
  SKIPPED: 'Skipped',
  RATE_LIMITED: 'The AI provider is rate-limited',
  TIMEOUT: 'The AI provider did not answer in time',
  INVALID_OUTPUT: 'The model returned an invalid answer, so it is not shown',
  ERROR: 'The AI provider failed',
}

const LIST_FIELDS: [string, string][] = [
  ['keyPoints', 'Key points'],
  ['responsibilities', 'Responsibilities'],
  ['keyTypes', 'Key types'],
  ['risks', 'Risks'],
  ['priorities', 'Priorities'],
]

const OBJECT_LIST_FIELDS: [string, string][] = [
  ['suggestions', 'Suggestions'],
  ['themes', 'Themes'],
]

function strings(value: unknown): string[] {
  return Array.isArray(value) ? value.filter((item): item is string => typeof item === 'string') : []
}

function objects(value: unknown): Record<string, unknown>[] {
  return Array.isArray(value)
    ? value.filter((item): item is Record<string, unknown> => !!item && typeof item === 'object' && !Array.isArray(item))
    : []
}

/** Only GitHub permalinks built by the backend are linked. */
function EvidenceLink({ evidence }: { evidence: InsightEvidence }) {
  const location =
    (evidence.filePath ?? evidence.kind.toLowerCase()) +
    (evidence.startLine != null ? `:${evidence.startLine}${evidence.endLine != null && evidence.endLine !== evidence.startLine ? `-${evidence.endLine}` : ''}` : '')
  if (evidence.sourceUrl && evidence.sourceUrl.startsWith('https://github.com/')) {
    return (
      <a href={evidence.sourceUrl} target="_blank" rel="noreferrer" className="font-mono text-body-sm text-primary-text hover:underline">
        {location}
      </a>
    )
  }
  return <span className="font-mono text-body-sm">{location}</span>
}

/** What the user can do about a failure: a plain reason and one sensible action. Styling is neutral or amber; red only for a real provider failure. */
export function InsightFailure({
  insight,
  onRetry,
  onUseLocal,
}: {
  insight: Pick<Insight, 'status' | 'reason'>
  onRetry?: () => void
  onUseLocal?: () => void
}) {
  const refused = insight.status === 'ERROR' && /refus|connect|unreachable/i.test(insight.reason ?? '')
  const genuine = insight.status === 'ERROR'
  return (
    <div role="status" className={`tone ${genuine ? 'tone-failed' : 'tone-warning'} space-y-2 rounded-lg p-3 text-body-md`}>
      <p className="flex items-center gap-2 font-medium text-fg">
        <Icon name={genuine ? 'error' : 'warning'} size={14} />
        {STATUS_TITLES[insight.status]}
      </p>
      {insight.status === 'SKIPPED' ? (
        <p className="text-fg">
          Call limit for this analysis reached. The limit is set by <code className="font-mono">AI_MAX_CALLS_PER_ANALYSIS</code>; see{' '}
          <Link to="/settings/ai" className="underline">
            AI Settings
          </Link>
          .
        </p>
      ) : (
        insight.reason && <p className="text-fg">{insight.reason}</p>
      )}
      {refused && (
        <div className="space-y-1">
          <p className="text-fg-2">Start the local model server, then check again:</p>
          <div className="flex items-center gap-2 rounded bg-code px-2 py-1 font-mono text-code-md text-fg">
            ollama serve <CopyButton value="ollama serve" label="Copy" compact />
          </div>
        </div>
      )}
      <div className="flex flex-wrap items-center gap-2">
        {(insight.status === 'TIMEOUT' || insight.status === 'INVALID_OUTPUT') && onRetry && (
          <Button variant="secondary" icon="refresh" onClick={onRetry}>
            Try again
          </Button>
        )}
        {refused && onRetry && (
          <Button variant="secondary" icon="refresh" onClick={onRetry}>
            Check again
          </Button>
        )}
        {insight.status === 'RATE_LIMITED' && onUseLocal && (
          <Button variant="secondary" onClick={onUseLocal}>
            Use local Ollama
          </Button>
        )}
      </div>
    </div>
  )
}

/** One insight: the AI answer on the AI surface with its badge, provider and evidence, or why there is none. */
export default function InsightResult({
  insight,
  onRetry,
  onUseLocal,
  onRegenerate,
}: {
  insight: Insight
  onRetry?: () => void
  onUseLocal?: () => void
  /** Asks the model again, bypassing the server cache. Shown on an answer only; the call counts against the budget. */
  onRegenerate?: () => void
}) {
  if (insight.status !== 'OK' || !insight.aiGenerated) {
    return <InsightFailure insight={insight} onRetry={onRetry} onUseLocal={onUseLocal} />
  }
  const structured = insight.structured ?? {}
  const missing = strings(structured.missingEvidence)
  const truncated = insight.evidence.filter((e) => e.truncated).length
  const notes = [...insight.warnings, ...insight.limitations]
  let section = 0
  const heading = (title: string) => `${String(++section).padStart(2, '0')} ${title}`
  return (
    <article aria-label="AI insight" className="ai-surface space-y-3 p-4">
      <header className="flex flex-wrap items-center gap-2 text-body-sm text-fg-2">
        <AIGeneratedBadge />
        <span className="font-mono">
          {insight.provider} · {insight.model} · prompt {insight.promptVersion}
        </span>
        {insight.fromCache && <Badge tone="neutral" icon="database">cached</Badge>}
        {insight.fallback && <Badge tone="warning" icon="refresh">fallback provider</Badge>}
        {truncated > 0 && <Badge tone="warning" icon="warning">truncated</Badge>}
        {onRegenerate && (
          <Button
            variant="ghost"
            icon="refresh"
            onClick={onRegenerate}
            className="ml-auto"
            title="Ask the model again and skip the cached answer. This is a new model call and counts against the call limit."
          >
            Regenerate
          </Button>
        )}
      </header>
      {insight.fromCache && <p className="text-body-sm text-muted">Served from cache: no model call was made.</p>}
      {insight.fallback && (
        <p className="text-body-sm text-muted">The primary provider did not answer; this was answered by {insight.provider}. Excerpts were scrubbed of secrets before sending.</p>
      )}
      {truncated > 0 && <p className="text-body-sm text-muted">The evidence exceeded the model&apos;s limit; {truncated} part(s) were omitted, so the answer may be incomplete.</p>}

      {insight.explanation && (
        <section aria-label="Explanation">
          <h4 className="mb-1 font-mono text-label-md uppercase tracking-wider text-ai-text">{heading('Explanation')}</h4>
          <MarkdownText text={insight.explanation} />
        </section>
      )}
      {LIST_FIELDS.map(([field, title]) => {
        const items = strings(structured[field])
        if (field === 'priorities' && insight.type === 'TECH_DEBT_SUMMARY' && items.length > 0) {
          return (
            <section key={field} aria-label={title}>
              <h4 className="mb-1 font-mono text-label-md uppercase tracking-wider text-ai-text">{heading(title)}</h4>
              <ol className="grid gap-2 md:grid-cols-3">
                {items.slice(0, 3).map((item, index) => (
                  <li key={index} className="rounded-lg border border-line bg-canvas p-3 text-body-md">
                    <span className="font-mono text-label-md text-ai-text">#{index + 1}</span>
                    <p className="mt-1">{item}</p>
                  </li>
                ))}
              </ol>
              {items.length > 3 && (
                <ul className="mt-2 list-disc pl-5 text-body-md">
                  {items.slice(3).map((item, index) => (
                    <li key={index}>{item}</li>
                  ))}
                </ul>
              )}
            </section>
          )
        }
        return items.length === 0 ? null : (
          <section key={field} aria-label={title}>
            <h4 className="mb-1 font-mono text-label-md uppercase tracking-wider text-ai-text">{heading(title)}</h4>
            <ul className="list-disc pl-5 text-body-lg">
              {items.map((item, index) => (
                <li key={index}>{item}</li>
              ))}
            </ul>
          </section>
        )
      })}
      {OBJECT_LIST_FIELDS.map(([field, title]) => {
        const items = objects(structured[field])
        return items.length === 0 ? null : (
          <section key={field} aria-label={title}>
            <h4 className="mb-1 font-mono text-label-md uppercase tracking-wider text-ai-text">{heading(title)}</h4>
            <ul className="space-y-2 text-body-lg">
              {items.map((item, index) => (
                <li key={index}>
                  <p className="font-medium">{String(item.title ?? '')}</p>
                  <p className="text-fg-2">{String(item.rationale ?? item.description ?? '')}</p>
                </li>
              ))}
            </ul>
          </section>
        )
      })}
      {missing.length > 0 && (
        <section aria-label="Missing evidence" className="tone tone-warning rounded-lg p-3 text-body-md">
          <h4 className="font-medium text-fg">Missing evidence</h4>
          <p className="text-body-sm text-fg-2">What the model said it could not see:</p>
          <ul className="list-disc pl-5 text-fg">
            {missing.map((item, index) => (
              <li key={index}>{item}</li>
            ))}
          </ul>
        </section>
      )}

      <section aria-label="Source evidence">
        <h4 className="text-body-md font-semibold">View source evidence</h4>
        <p className="text-body-sm text-muted">
          {insight.evidence.length} fragment(s) were sent to the model (secrets withheld{insight.withheldLines > 0 ? `: ${insight.withheldLines} line(s)` : ''}).
        </p>
        <ul className="mt-1 space-y-1">
          {insight.evidence.map((evidence) => (
            <li key={evidence.id}>
              <details>
                <summary className="cursor-pointer text-body-md">
                  <span className="mr-2 font-mono text-body-sm text-muted">{evidence.id}</span>
                  <span className="mr-2 text-body-sm uppercase text-muted">{evidence.kind}</span>
                  <EvidenceLink evidence={evidence} />
                  {evidence.truncated && <span className="ml-2 text-body-sm text-[color:var(--t-warning-fg)]">truncated</span>}
                </summary>
                <div className="mt-1 max-h-64 overflow-auto">
                  <CodeBlock code={evidence.text} lineNumbers={false} />
                </div>
              </details>
            </li>
          ))}
        </ul>
      </section>
      {notes.length > 0 && (
        <ul aria-label="Insight notes" className="list-disc pl-5 text-body-sm text-muted">
          {notes.map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
      <footer className="flex items-center gap-2 border-t border-line pt-2">
        <CopyButton value={[insight.explanation, ...strings(structured.keyPoints)].filter(Boolean).join('\n')} label="Copy answer" labelOnly />
      </footer>
    </article>
  )
}
