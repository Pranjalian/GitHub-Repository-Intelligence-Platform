import type { QuestionAnswer } from '../../types/api'
import AIGeneratedBadge from '../ai/AIGeneratedBadge'
import MarkdownText from '../ai/MarkdownText'
import Badge from '../ui/Badge'
import SearchResultsList from './SearchResultsList'
import SourceLink from './SourceLink'

const FAILURES: Partial<Record<QuestionAnswer['status'], string>> = {
  SKIPPED: 'Not answered',
  RATE_LIMITED: 'Provider rate-limited',
  TIMEOUT: 'Provider timed out',
  INVALID_OUTPUT: 'Answer rejected',
  ERROR: 'Provider error',
}

/**
 * One answer: the AI-generated text (Markdown without raw HTML, links or images) with its validated sources,
 * or an explicit "not enough evidence" with what was searched, or why there is no answer (with deterministic
 * search results when no model is available). Links come only from the backend.
 */
export default function AnswerView({ answer }: { answer: QuestionAnswer }) {
  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        {answer.aiGenerated && <AIGeneratedBadge />}
        {answer.status === 'INSUFFICIENT_EVIDENCE' && <Badge tone="warning">Not enough evidence</Badge>}
        {answer.status === 'UNAVAILABLE' && <Badge tone="neutral">AI unavailable</Badge>}
        {FAILURES[answer.status] && <Badge tone="danger">{FAILURES[answer.status]}</Badge>}
        {answer.aiGenerated && answer.provider && (
          <span className="text-xs text-slate-500">
            {answer.provider}/{answer.model}
            {answer.fromCache ? ' · cached' : ''}
            {answer.fallback ? ' · fallback provider' : ''}
          </span>
        )}
      </div>

      {answer.answer && (answer.aiGenerated ? <MarkdownText text={answer.answer} /> : <p className="text-sm">{answer.answer}</p>)}
      {!answer.answer && answer.reason && <p className="text-sm text-slate-700">{answer.reason}</p>}

      {answer.clarifyingQuestion && (
        <p className="rounded bg-amber-50 p-2 text-sm text-amber-900">
          <span className="font-medium">Clarifying question: </span>
          {answer.clarifyingQuestion}
        </p>
      )}
      {answer.missingEvidence.length > 0 && (
        <div className="text-xs text-slate-700">
          <p className="font-medium">Not found in the evidence:</p>
          <ul className="list-disc pl-5">
            {answer.missingEvidence.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      )}

      {answer.sources.length > 0 && (
        <div>
          <h4 className="text-xs font-semibold uppercase tracking-wide text-slate-500">Sources</h4>
          <ul className="mt-1 space-y-1">
            {answer.sources.map((source) => (
              <li key={source.chunkId} className="text-sm">
                <span className="mr-2 font-mono text-xs text-slate-500">[{source.ref}]</span>
                {source.label && <span className="mr-2">{source.label}</span>}
                <SourceLink
                  filePath={source.filePath}
                  startLine={source.startLine}
                  endLine={source.endLine}
                  sourceUrl={source.sourceUrl}
                />
                {source.snippet && (
                  <details className="mt-1">
                    <summary className="cursor-pointer text-xs text-slate-600">Show the excerpt that was sent</summary>
                    <pre className="mt-1 overflow-x-auto rounded bg-slate-50 p-2 text-xs leading-snug">{source.snippet}</pre>
                  </details>
                )}
              </li>
            ))}
          </ul>
        </div>
      )}

      {answer.searchResults.length > 0 && (
        <div>
          <h4 className="mb-1 text-xs font-semibold uppercase tracking-wide text-slate-500">
            Deterministic search results for the question's words
          </h4>
          <SearchResultsList results={answer.searchResults} />
        </div>
      )}

      {answer.retrieved.length > 0 && answer.status !== 'OK' && (
        <details className="text-xs">
          <summary className="cursor-pointer text-slate-600">Retrieved chunks ({answer.retrieved.length})</summary>
          <ul className="mt-1 space-y-1">
            {answer.retrieved.map((source) => (
              <li key={source.chunkId}>
                <SourceLink
                  filePath={source.filePath}
                  startLine={source.startLine}
                  endLine={source.endLine}
                  sourceUrl={source.sourceUrl}
                />
              </li>
            ))}
          </ul>
        </details>
      )}

      {answer.searched && <p className="text-xs text-slate-500">Searched: {answer.searched}</p>}
      {[...answer.warnings, ...answer.limitations].length > 0 && (
        <ul className="list-disc pl-5 text-xs text-slate-600">
          {[...answer.warnings, ...answer.limitations].map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </div>
  )
}
