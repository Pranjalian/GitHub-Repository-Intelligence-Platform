import type { SearchResult } from '../../types/api'
import Badge from '../ui/Badge'
import SourceLink from './SourceLink'

/** Search hits with their location (GitHub at the commit) and a scrubbed snippet, rendered as plain text. */
export default function SearchResultsList({ results }: { results: SearchResult[] }) {
  return (
    <ol className="space-y-2">
      {results.map((result) => (
        <li
          key={`${result.kind}:${result.symbolId ?? result.chunkId}:${result.filePath}:${result.startLine}`}
          className="rounded border border-slate-200 bg-white p-3"
        >
          <div className="flex flex-wrap items-center gap-2">
            <span className="text-sm font-medium">{result.name ?? result.section ?? result.filePath}</span>
            <Badge tone="neutral">{result.symbolType ?? result.chunkType ?? result.kind}</Badge>
            {result.redacted && <Badge tone="warning">secrets withheld</Badge>}
            {result.score != null && <span className="text-xs text-slate-500">score {result.score}</span>}
          </div>
          {result.qualifiedName && result.qualifiedName !== result.name && (
            <p className="break-all font-mono text-xs text-slate-600">
              {result.qualifiedName}
              {result.signature ? ` · ${result.signature}` : ''}
            </p>
          )}
          <SourceLink
            filePath={result.filePath}
            startLine={result.startLine}
            endLine={result.endLine}
            sourceUrl={result.sourceUrl}
          />
          {result.snippet && (
            <pre className="mt-2 overflow-x-auto rounded bg-slate-50 p-2 text-xs leading-snug text-slate-800">
              {result.snippet}
            </pre>
          )}
        </li>
      ))}
    </ol>
  )
}
