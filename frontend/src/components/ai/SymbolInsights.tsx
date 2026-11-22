import { useState } from 'react'
import { useSymbol } from '../../hooks/queries'
import InsightPanel from './InsightPanel'

/**
 * AI actions for one type: explain it, suggest refactorings, and explain or refactor each of its methods
 * (loaded on demand from the symbol endpoint). Hidden behind a toggle so long class lists stay compact.
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
  const [open, setOpen] = useState(false)
  const [showMethods, setShowMethods] = useState(false)
  const symbol = useSymbol(repositoryId, snapshotId, open && showMethods ? symbolId : null)
  const methods = (symbol.data?.members ?? []).filter((m) => m.type === 'METHOD' || m.type === 'CONSTRUCTOR')

  if (!open) {
    return (
      <button
        type="button"
        onClick={() => setOpen(true)}
        aria-label={`AI insights for ${name}`}
        className="rounded border border-violet-200 px-1.5 text-xs text-violet-800 hover:bg-violet-50"
      >
        AI
      </button>
    )
  }
  return (
    <div className="mt-1 w-full space-y-2 rounded border border-violet-100 bg-violet-50/40 p-2" aria-label={`AI insights for ${name}`}>
      <div className="flex items-center justify-between">
        <span className="text-xs font-medium text-slate-700">AI insights for {name}</span>
        <button type="button" onClick={() => setOpen(false)} className="text-xs text-slate-500 hover:underline">
          Close
        </button>
      </div>
      <InsightPanel repositoryId={repositoryId} snapshotId={snapshotId} type="EXPLAIN_CLASS" targetId={symbolId} actionLabel="Explain class" />
      <InsightPanel
        repositoryId={repositoryId}
        snapshotId={snapshotId}
        type="SUGGEST_REFACTORING"
        targetId={symbolId}
        actionLabel="Suggest refactoring"
        compact
      />
      <button type="button" onClick={() => setShowMethods(!showMethods)} className="text-xs text-blue-700 hover:underline">
        {showMethods ? 'Hide methods' : 'Methods'}
      </button>
      {showMethods && symbol.isPending && <p role="status" className="text-xs">Loading methods…</p>}
      {showMethods && symbol.error && <p role="alert" className="text-xs text-red-700">{symbol.error.message}</p>}
      {showMethods && symbol.data && methods.length === 0 && <p className="text-xs text-slate-600">No methods.</p>}
      {showMethods && methods.length > 0 && (
        <ul aria-label={`Methods of ${name}`} className="space-y-1">
          {methods.map((method) => (
            <li key={method.id} className="rounded border border-slate-200 bg-white p-1.5">
              <span className="font-mono text-xs">{method.name}</span>
              {method.startLine != null && <span className="ml-2 text-xs text-slate-500">line {method.startLine}</span>}
              <InsightPanel
                repositoryId={repositoryId}
                snapshotId={snapshotId}
                type="EXPLAIN_METHOD"
                targetId={method.id}
                actionLabel="Explain method"
                compact
              />
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}
