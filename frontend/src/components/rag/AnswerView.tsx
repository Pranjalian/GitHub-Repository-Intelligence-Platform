import { useRef, useState } from 'react'
import { Link } from 'react-router'
import type { QuestionAnswer } from '../../types/api'
import AIGeneratedBadge from '../ai/AIGeneratedBadge'
import MarkdownText from '../ai/MarkdownText'
import Badge from '../ui/Badge'
import Button from '../ui/Button'
import CodeBlock from '../ui/CodeBlock'
import Icon from '../ui/icons'
import { Notice } from '../ui/Layout'
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
 * One answer: the AI-generated text on the AI surface (Markdown without raw HTML, links or images, with
 * clickable [n] citations) and its validated sources; or an explicit "insufficient evidence" with what was
 * searched; or why there is no answer, with deterministic search results when no model is available.
 * Links come only from the backend.
 */
export default function AnswerView({ answer, searchLink, commitSha }: { answer: QuestionAnswer; searchLink?: string; commitSha?: string }) {
  const [active, setActive] = useState<string | null>(null)
  const [open, setOpen] = useState(false)
  const refs = useRef<Record<string, HTMLLIElement | null>>({})
  const files = new Set(answer.sources.map((s) => s.filePath)).size
  const sha = (answer.commitSha ?? commitSha ?? '').slice(0, 7)
  const insufficient = answer.status === 'INSUFFICIENT_EVIDENCE'
  const degraded = answer.status === 'UNAVAILABLE' || (!answer.aiGenerated && !insufficient && answer.status !== 'OK' && answer.searchResults.length > 0)
  const embeddingOff = answer.index?.embeddingStatus === 'NOT_CONFIGURED'

  function cite(ref: string) {
    setOpen(true)
    setActive(ref)
    // after the panel opens, bring the source into view
    setTimeout(() => refs.current[ref]?.scrollIntoView?.({ block: 'center' }), 0)
  }

  return (
    <div className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        {answer.aiGenerated && <AIGeneratedBadge />}
        {insufficient && <Badge tone="neutral" icon="info">Insufficient evidence</Badge>}
        {answer.status === 'UNAVAILABLE' && <Badge tone="neutral" icon="info">AI unavailable</Badge>}
        {FAILURES[answer.status] && <Badge tone="failed" icon="error">{FAILURES[answer.status]}</Badge>}
        {answer.aiGenerated && answer.provider && (
          <span className="font-mono text-body-sm text-fg-2">
            {answer.provider}/{answer.model}
            {answer.fromCache ? ' · cached' : ''}
            {answer.fallback ? ' · fallback provider' : ''}
          </span>
        )}
        {answer.aiGenerated && files > 0 && <span className="text-body-sm text-muted">Grounded in {files} {files === 1 ? 'file' : 'files'}</span>}
      </div>

      {answer.answer && (answer.aiGenerated ? <MarkdownText text={answer.answer} onCite={cite} /> : <p className="text-body-lg">{answer.answer}</p>)}
      {!answer.answer && answer.reason && <p className="text-body-md text-fg-2">{answer.reason}</p>}

      {insufficient && answer.searched && (
        <div className="rounded-lg border border-line bg-canvas p-3 text-body-md">
          <h4 className="font-mono text-label-sm uppercase tracking-wider text-muted">Searched</h4>
          <p>{answer.searched}</p>
          {answer.retrieved.length > 0 && <p className="text-body-sm text-muted">{answer.retrieved.length} chunk(s) retrieved, none supporting an answer.</p>}
        </div>
      )}
      {answer.clarifyingQuestion && (
        <Notice>
          <span className="font-medium">Clarifying question: </span>
          {answer.clarifyingQuestion}
        </Notice>
      )}
      {answer.missingEvidence.length > 0 && (
        <div className="text-body-md text-fg-2">
          <p className="font-medium text-fg">Not found in the evidence:</p>
          <ul className="list-disc pl-5">
            {answer.missingEvidence.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        </div>
      )}

      {answer.sources.length > 0 && (
        <div className="rounded-lg border border-line bg-canvas">
          <h4>
            <button type="button" aria-expanded={open} onClick={() => setOpen((v) => !v)} className="flex w-full items-center gap-2 px-3 py-2 text-left text-body-md font-semibold">
              <Icon name={open ? 'chevron-down' : 'chevron-right'} size={14} />
              Sources ({answer.sources.length})
            </button>
          </h4>
          {open && (
            <ul className="space-y-2 border-t border-line p-3">
              {answer.sources.map((source) => (
                <li
                  key={source.chunkId}
                  ref={(el) => {
                    if (source.ref) refs.current[source.ref] = el
                  }}
                  data-active={active === source.ref || undefined}
                  className={`space-y-1 rounded-lg p-2 ${active === source.ref ? 'border border-primary' : ''}`}
                >
                  <p className="flex flex-wrap items-center gap-2">
                    <span className="font-mono text-body-sm text-muted">[{source.ref}]</span>
                    {source.label && <span className="text-body-md">{source.label}</span>}
                    <SourceLink filePath={source.filePath} startLine={source.startLine} endLine={source.endLine} sourceUrl={source.sourceUrl} />
                  </p>
                  {source.sourceUrl && sha && (
                    <a href={source.sourceUrl} target="_blank" rel="noreferrer noopener" className="text-body-sm text-primary-text hover:underline">
                      Open on GitHub ({sha})
                    </a>
                  )}
                  {source.snippet && (
                    <details>
                      <summary className="cursor-pointer text-body-sm text-fg-2">Show the excerpt that was sent</summary>
                      <CodeBlock code={source.snippet} startLine={source.startLine} copy={false} />
                    </details>
                  )}
                </li>
              ))}
            </ul>
          )}
        </div>
      )}

      {degraded && (
        <div className="space-y-2 rounded-lg border border-dashed border-line-strong bg-surface p-3">
          <p className="flex items-center gap-2 text-body-md font-medium">
            <Icon name="info" size={14} />
            {embeddingOff ? 'No embedding provider configured: vector index disabled, falling back to keyword search.' : 'AI is unavailable, so there is no written answer. These are the deterministic search results for your question.'}
          </p>
          <div className="flex flex-wrap gap-2">
            {searchLink && (
              <Link to={searchLink}>
                <Button variant="secondary" icon="search">Open in Code Search</Button>
              </Link>
            )}
            <Link to="/settings/ai">
              <Button variant="secondary" icon="tune">Configure AI Settings</Button>
            </Link>
          </div>
        </div>
      )}
      {answer.searchResults.length > 0 && (
        <div>
          <h4 className="mb-1 font-mono text-label-sm uppercase tracking-wider text-muted">Deterministic search results for the question&apos;s words</h4>
          <SearchResultsList results={answer.searchResults} />
        </div>
      )}

      {answer.retrieved.length > 0 && answer.status !== 'OK' && !insufficient && (
        <details className="text-body-sm">
          <summary className="cursor-pointer text-fg-2">Retrieved chunks ({answer.retrieved.length})</summary>
          <ul className="mt-1 space-y-1">
            {answer.retrieved.map((source) => (
              <li key={source.chunkId}>
                <SourceLink filePath={source.filePath} startLine={source.startLine} endLine={source.endLine} sourceUrl={source.sourceUrl} />
              </li>
            ))}
          </ul>
        </details>
      )}

      {answer.searched && !insufficient && <p className="text-body-sm text-muted">Searched: {answer.searched}</p>}
      {insufficient && !answer.searched && null}
      {[...answer.warnings, ...answer.limitations].length > 0 && (
        <ul className="list-disc pl-5 text-body-sm text-muted">
          {[...answer.warnings, ...answer.limitations].map((note) => (
            <li key={note}>{note}</li>
          ))}
        </ul>
      )}
    </div>
  )
}
