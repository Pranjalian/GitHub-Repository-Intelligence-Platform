import type { SearchResult } from '../../types/api'
import Badge from '../ui/Badge'
import CodeBlock from '../ui/CodeBlock'
import { KindBadge } from '../ui/MoreBadges'
import SourceLink from './SourceLink'

/**
 * Search hits as cards: kind badge, qualified name, path:line (GitHub at the commit) and a code context
 * with line numbers, the matched line highlighted and the terms emphasised. Snippets are scrubbed text.
 */
export default function SearchResultsList({ results, terms = [] }: { results: SearchResult[]; terms?: string[] }) {
  return (
    <ol className="space-y-2">
      {results.map((result) => {
        const first = result.startLine ?? 1
        const lines = (result.snippet ?? '').split('\n')
        const matched = lines.map((l, i) => (terms.some((t) => t && l.toLowerCase().includes(t.toLowerCase())) ? first + i : -1)).filter((n) => n > 0)
        return (
          <li key={`${result.kind}:${result.symbolId ?? result.chunkId}:${result.filePath}:${result.startLine}`} className="space-y-2 rounded-lg border border-line bg-surface p-3">
            <div className="flex flex-wrap items-center gap-2">
              <KindBadge kind={result.symbolType ?? result.chunkType ?? result.kind} />
              <span className="font-mono text-body-md font-semibold">{result.name ?? result.section ?? result.filePath}</span>
              {result.redacted && <Badge tone="warning" icon="lock">secrets withheld</Badge>}
              {result.score != null && <span className="text-body-sm text-muted">score {result.score}</span>}
            </div>
            {result.qualifiedName && result.qualifiedName !== result.name && (
              <p className="break-all font-mono text-body-sm text-fg-2">
                {result.qualifiedName}
                {result.signature ? ` · ${result.signature}` : ''}
              </p>
            )}
            <SourceLink filePath={result.filePath} startLine={result.startLine} endLine={result.endLine} sourceUrl={result.sourceUrl} />
            {result.snippet && <CodeBlock code={result.snippet} startLine={first} highlight={matched} emphasize={terms} copy={false} />}
          </li>
        )
      })}
    </ol>
  )
}
