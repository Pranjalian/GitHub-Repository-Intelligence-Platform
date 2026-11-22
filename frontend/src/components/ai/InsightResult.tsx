import type { AIStatus, Insight, InsightEvidence } from '../../types/api'
import { formatDateTime } from '../../lib/format'
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
  ['missingEvidence', 'Missing evidence'],
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
      <a href={evidence.sourceUrl} target="_blank" rel="noreferrer" className="font-mono text-xs text-blue-700 hover:underline">
        {location}
      </a>
    )
  }
  return <span className="font-mono text-xs">{location}</span>
}

/** One insight: the AI answer with its badge, provider and evidence, or why there is none. */
export default function InsightResult({ insight }: { insight: Insight }) {
  if (insight.status !== 'OK' || !insight.aiGenerated) {
    return (
      <div role="status" className="rounded border border-amber-300 bg-amber-50 p-3 text-sm">
        <p className="font-medium">{STATUS_TITLES[insight.status]}</p>
        {insight.reason && <p className="mt-1 text-slate-700">{insight.reason}</p>}
      </div>
    )
  }
  const structured = insight.structured ?? {}
  return (
    <article aria-label="AI insight" className="space-y-3 rounded border border-violet-200 bg-white p-3">
      <header className="flex flex-wrap items-center gap-2 text-xs text-slate-600">
        <AIGeneratedBadge />
        <span>
          {insight.provider} · {insight.model} · prompt {insight.promptVersion}
        </span>
        {insight.fromCache && <span className="rounded bg-slate-100 px-1.5 py-0.5">cached</span>}
        {insight.fallback && <span className="rounded bg-amber-100 px-1.5 py-0.5">fallback provider</span>}
        {insight.generatedAt && <span>{formatDateTime(insight.generatedAt)}</span>}
      </header>
      {insight.explanation && <MarkdownText text={insight.explanation} />}
      {LIST_FIELDS.map(([field, title]) => {
        const items = strings(structured[field])
        return items.length === 0 ? null : (
          <section key={field} aria-label={title}>
            <h4 className="text-sm font-semibold">{title}</h4>
            <ul className="list-disc pl-5 text-sm">
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
            <h4 className="text-sm font-semibold">{title}</h4>
            <ul className="space-y-2 text-sm">
              {items.map((item, index) => (
                <li key={index}>
                  <p className="font-medium">{String(item.title ?? '')}</p>
                  <p className="text-slate-700">{String(item.rationale ?? item.description ?? '')}</p>
                </li>
              ))}
            </ul>
          </section>
        )
      })}
      <section aria-label="Source evidence">
        <h4 className="text-sm font-semibold">View source evidence</h4>
        <p className="text-xs text-slate-500">
          What was sent to the model (secrets withheld{insight.withheldLines > 0 ? `: ${insight.withheldLines} line(s)` : ''}).
        </p>
        <ul className="mt-1 space-y-1">
          {insight.evidence.map((evidence) => (
            <li key={evidence.id}>
              <details>
                <summary className="cursor-pointer text-sm">
                  <span className="mr-2 font-mono text-xs text-slate-500">{evidence.id}</span>
                  <span className="mr-2 text-xs uppercase text-slate-500">{evidence.kind}</span>
                  <EvidenceLink evidence={evidence} />
                  {evidence.truncated && <span className="ml-2 text-xs text-amber-700">truncated</span>}
                </summary>
                <pre className="mt-1 max-h-64 overflow-auto rounded bg-slate-900 p-2 text-xs text-slate-100">
                  <code>{evidence.text}</code>
                </pre>
              </details>
            </li>
          ))}
        </ul>
      </section>
      {(insight.warnings.length > 0 || insight.limitations.length > 0) && (
        <ul aria-label="Insight notes" className="list-disc pl-5 text-xs text-slate-600">
          {[...insight.warnings, ...insight.limitations].map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </article>
  )
}
