import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import AIAvailabilityNote from '../components/ai/AIAvailabilityNote'
import ImpactResultView from '../components/impact/ImpactResultView'
import SnapshotPageFrame from '../components/rag/SnapshotPageFrame'
import Badge from '../components/ui/Badge'
import { useAIProviders, useAnalyzeImpact, useImpactSymbols, useSuggestImpactTargets } from '../hooks/queries'
import type { PickerSymbol } from '../types/api'

/** `/repositories/:repositoryId/impact?snapshot=`: what may be affected by changing a class or method. */
export default function ImpactAnalysisPage() {
  return (
    <SnapshotPageFrame title="Impact analysis">
      {({ repositoryId, snapshotId }) => <ImpactView repositoryId={repositoryId} snapshotId={snapshotId} />}
    </SnapshotPageFrame>
  )
}

function ImpactView({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const [searchParams] = useSearchParams()
  const [text, setText] = useState(searchParams.get('symbol') ?? '')
  const [query, setQuery] = useState(searchParams.get('symbol') ?? '')
  const [target, setTarget] = useState<PickerSymbol | null>(null)
  const [depth, setDepth] = useState(3)
  const [description, setDescription] = useState('')
  const symbols = useImpactSymbols(repositoryId, snapshotId, query)
  const analyze = useAnalyzeImpact(repositoryId, snapshotId)
  const suggest = useSuggestImpactTargets(repositoryId, snapshotId)
  const providers = useAIProviders()
  const aiAvailable = providers.data?.available === true

  function search(event: FormEvent) {
    event.preventDefault()
    setQuery(text.trim())
  }

  function run(includeAiSummary: boolean) {
    if (!target) {
      return
    }
    analyze.mutate({
      targetSymbolId: target.id,
      depth,
      includeAiSummary,
      changeDescription: includeAiSummary && description.trim() ? description.trim() : null,
    })
  }

  return (
    <div className="space-y-4">
      <p className="text-sm text-slate-600">
        Pick a class or method and see what may be affected if it changes. The result comes from the dependency graph of the
        parsed code and works without AI; the risk score is a heuristic, not a probability.
      </p>

      <section aria-label="Pick a target" className="space-y-2 rounded border border-slate-200 bg-white p-3">
        <form onSubmit={search} role="search" className="flex flex-wrap gap-2">
          <label className="sr-only" htmlFor="impact-symbol">
            Class or method name
          </label>
          <input
            id="impact-symbol"
            value={text}
            onChange={(event) => setText(event.target.value)}
            maxLength={200}
            placeholder="Class or method, e.g. PaymentService.calculatePayment"
            className="min-w-0 flex-1 rounded border border-slate-300 px-3 py-1.5 text-sm"
          />
          <button type="submit" className="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700">
            Find symbols
          </button>
        </form>
        {symbols.isPending && <p role="status" className="text-sm text-slate-600">Loading symbols…</p>}
        {symbols.error && (
          <p role="alert" className="text-sm text-red-700">
            {symbols.error.message}
          </p>
        )}
        {symbols.data?.status === 'NOT_SUPPORTED' && (
          <p role="note" className="rounded bg-amber-50 p-2 text-sm text-amber-900">
            {symbols.data.reason}
          </p>
        )}
        {symbols.data?.status === 'OK' && (
          <ul aria-label="Matching symbols" className="max-h-64 space-y-1 overflow-y-auto text-sm">
            {symbols.data.symbols.length === 0 && <li className="text-slate-600">No class or method matches “{query}”.</li>}
            {symbols.data.symbols.map((symbol) => (
              <li key={symbol.id}>
                <button
                  type="button"
                  onClick={() => setTarget(symbol)}
                  aria-pressed={target?.id === symbol.id}
                  className={`flex w-full flex-wrap items-center gap-2 rounded px-2 py-1 text-left hover:bg-slate-50 ${
                    target?.id === symbol.id ? 'bg-blue-50 ring-1 ring-blue-300' : ''
                  }`}
                >
                  <span className="font-mono">{symbol.name}</span>
                  <Badge>{symbol.kind.toLowerCase()}</Badge>
                  {symbol.layer && <Badge tone="info">{symbol.layer.toLowerCase()}</Badge>}
                  <span className="break-all text-xs text-slate-500">{symbol.filePath}</span>
                </button>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section aria-label="Describe the change" className="space-y-2 rounded border border-slate-200 bg-white p-3">
        <h3 className="text-sm font-semibold">Describe the change (optional, needs AI)</h3>
        {aiAvailable ? (
          <>
            <label className="sr-only" htmlFor="impact-description">
              Change description
            </label>
            <textarea
              id="impact-description"
              value={description}
              onChange={(event) => setDescription(event.target.value)}
              maxLength={1000}
              rows={2}
              placeholder="e.g. round payment totals to whole cents"
              className="w-full rounded border border-slate-300 px-3 py-1.5 text-sm"
            />
            <div className="flex flex-wrap items-center gap-2">
              <button
                type="button"
                disabled={!description.trim() || suggest.isPending}
                onClick={() => suggest.mutate(description.trim())}
                className="rounded border border-slate-300 px-3 py-1 text-sm hover:bg-slate-50 disabled:opacity-50"
              >
                Suggest symbols
              </button>
              <span className="text-xs text-slate-500">
                A model proposes symbols that exist in this snapshot; you confirm one before anything is analysed. The
                description is only context for the summary.
              </span>
            </div>
            {suggest.data && suggest.data.status !== 'OK' && (
              <p role="note" className="text-sm text-slate-700">
                Suggestions {suggest.data.status.toLowerCase()}: {suggest.data.detail ?? 'no reason given'}. Use the picker above.
              </p>
            )}
            {suggest.data?.status === 'OK' && (
              <ul aria-label="Suggested symbols" className="space-y-1 text-sm">
                {suggest.data.detail && <li className="text-slate-600">{suggest.data.detail}</li>}
                {suggest.data.suggestions.map((suggestion) => (
                  <li key={suggestion.symbolId} className="flex flex-wrap items-center gap-2">
                    <span className="font-mono">{suggestion.name}</span>
                    <span className="text-xs text-slate-600">{suggestion.reason}</span>
                    <button
                      type="button"
                      onClick={() =>
                        setTarget({
                          id: suggestion.symbolId,
                          kind: suggestion.kind,
                          name: suggestion.name,
                          qualifiedName: suggestion.qualifiedName,
                          filePath: suggestion.filePath,
                          line: suggestion.line,
                          layer: null,
                        })
                      }
                      className="rounded border border-blue-300 px-2 py-0.5 text-xs text-blue-800 hover:bg-blue-50"
                    >
                      Use this symbol
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </>
        ) : (
          <AIAvailabilityNote />
        )}
      </section>

      <section aria-label="Run" className="flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-1 text-sm">
          Depth
          <select
            value={depth}
            onChange={(event) => setDepth(Number(event.target.value))}
            className="rounded border border-slate-300 px-2 py-1 text-sm"
          >
            {[1, 2, 3, 4, 5, 6].map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </label>
        <button
          type="button"
          disabled={!target || analyze.isPending}
          onClick={() => run(false)}
          className="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700 disabled:opacity-50"
        >
          Analyze impact
        </button>
        {aiAvailable && (
          <button
            type="button"
            disabled={!target || analyze.isPending}
            onClick={() => run(true)}
            className="rounded border border-violet-300 px-3 py-1.5 text-sm text-violet-900 hover:bg-violet-50 disabled:opacity-50"
          >
            Analyze and summarize with AI
          </button>
        )}
        <span className="text-sm text-slate-600">
          {target ? (
            <>
              Target: <span className="font-mono">{target.name}</span>
            </>
          ) : (
            'Pick a target first.'
          )}
        </span>
      </section>

      {analyze.isPending && <p role="status" className="text-sm text-slate-600">Analyzing…</p>}
      {analyze.error && (
        <p role="alert" className="text-sm text-red-700">
          {analyze.error.message}
        </p>
      )}
      {analyze.data && !analyze.isPending && <ImpactResultView result={analyze.data} />}
    </div>
  )
}
