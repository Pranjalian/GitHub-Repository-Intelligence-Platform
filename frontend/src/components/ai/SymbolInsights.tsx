import { useSymbol } from '../../hooks/queries'
import InsightPanel from './InsightPanel'

/**
 * AI actions for one type, shown when its row is expanded: explain the class, suggest refactorings, and
 * explain each method (methods load on demand from the symbol endpoint). Every result opens inline.
 */
export default function SymbolInsights({
  repositoryId,
  snapshotId,
  symbolId,
  name,
}: {
  repositoryId: string
  snapshotId: string
  symbolId: string
  name: string
}) {
  const symbol = useSymbol(repositoryId, snapshotId, symbolId)
  const methods = (symbol.data?.members ?? []).filter((m) => m.type === 'METHOD' || m.type === 'CONSTRUCTOR')

  return (
    <div className="space-y-3" aria-label={`AI insights for ${name}`}>
      <div className="flex flex-wrap items-start gap-2">
        <InsightPanel repositoryId={repositoryId} snapshotId={snapshotId} type="EXPLAIN_CLASS" targetId={symbolId} actionLabel="Explain class" compact />
        <InsightPanel repositoryId={repositoryId} snapshotId={snapshotId} type="SUGGEST_REFACTORING" targetId={symbolId} actionLabel="Suggest refactoring" compact />
      </div>
      <div>
        <h5 className="font-mono text-label-sm uppercase tracking-wider text-muted">Methods ({symbol.data ? methods.length : '…'})</h5>
        {symbol.isPending && <p role="status" className="text-body-sm text-muted">Loading methods…</p>}
        {symbol.error && <p role="alert" className="text-body-sm text-[color:var(--t-failed-fg)]">{symbol.error.message}</p>}
        {symbol.data && methods.length === 0 && <p className="text-body-sm text-muted">No methods.</p>}
        {methods.length > 0 && (
          <ul aria-label={`Methods of ${name}`} className="mt-1 space-y-1">
            {methods.map((method) => (
              <li key={method.id} className="rounded border border-line bg-canvas p-2">
                <span className="font-mono text-code-md">{method.signature ?? method.name}</span>
                {method.startLine != null && <span className="ml-2 text-body-sm text-muted">line {method.startLine}</span>}
                <div className="mt-1">
                  <InsightPanel repositoryId={repositoryId} snapshotId={snapshotId} type="EXPLAIN_METHOD" targetId={method.id} actionLabel="Explain method" compact />
                </div>
              </li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}
