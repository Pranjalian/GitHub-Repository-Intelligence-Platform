import { useState, type FormEvent } from 'react'
import { useSearchParams } from 'react-router'
import AIAvailabilityNote from '../components/ai/AIAvailabilityNote'
import ImpactResultView, { DEFAULT_FILTERS, type ImpactViewFilters } from '../components/impact/ImpactResultView'
import SnapshotPageFrame from '../components/rag/SnapshotPageFrame'
import { Badge, Button, Card, Checkbox, Input, KindBadge, SegmentedControl } from '../components/ui'
import { Notice, SkeletonRows } from '../components/ui/Layout'
import { useAIProviders, useAnalyzeImpact, useImpactSymbols, useReport, useSuggestImpactTargets } from '../hooks/queries'
import type { ImpactAnalysis, ImpactRequest, PickerSymbol } from '../types/api'

const MAX_DEPTH = 6
const HOPS = [
  { value: '1', label: '1 Hop' },
  { value: '2', label: '2 Hops' },
  { value: '3', label: '3 Hops' },
  { value: String(MAX_DEPTH), label: `Full (${MAX_DEPTH})` },
]

/** Downloads the analysis exactly as the API returned it (client side only; nothing is uploaded). */
export function exportJson(result: ImpactAnalysis) {
  const blob = new Blob([JSON.stringify(result, null, 2)], { type: 'application/json' })
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = `impact-${result.target.name.replace(/[^\w.-]+/g, '_').slice(0, 60)}-${result.commitSha.slice(0, 7)}.json`
  link.click()
  URL.revokeObjectURL(url)
}

/** `/repositories/:repositoryId/impact?snapshot=`: what may be affected by changing a class or method. */
export default function ImpactAnalysisPage() {
  return (
    <SnapshotPageFrame title="Change Impact Analysis" subtitle="AST static analysis + call graph resolution · deterministic graph">
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
  const [filters, setFilters] = useState<ImpactViewFilters>(DEFAULT_FILTERS)
  const symbols = useImpactSymbols(repositoryId, snapshotId, query)
  const analyze = useAnalyzeImpact(repositoryId, snapshotId)
  const suggest = useSuggestImpactTargets(repositoryId, snapshotId)
  const providers = useAIProviders()
  const report = useReport(repositoryId, snapshotId, true)
  const aiAvailable = providers.data?.available === true
  const isMaven = !!report.data?.technologies.some((t) => t.kind === 'BUILD_TOOL' && /maven/i.test(t.name))
  const [lastRun, setLastRun] = useState<ImpactRequest | null>(null)

  function search(event: FormEvent) {
    event.preventDefault()
    setQuery(text.trim())
  }

  function run(includeAiSummary: boolean) {
    if (!target) {
      return
    }
    const request: ImpactRequest = {
      targetSymbolId: target.id,
      depth,
      includeAiSummary,
      changeDescription: includeAiSummary && description.trim() ? description.trim() : null,
    }
    setLastRun(request)
    analyze.mutate(request)
  }

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="max-w-3xl text-body-md text-fg-2">
          Pick a class or method and see what may be affected if it changes. The result comes from the dependency graph of the parsed code and works without AI; the risk score is a heuristic, not a probability.
        </p>
        <div className="flex items-center gap-2">
          <Button variant="secondary" icon="download" disabled={!analyze.data} onClick={() => analyze.data && exportJson(analyze.data)}>
            Export JSON
          </Button>
          <Button variant="secondary" icon="refresh" disabled={!lastRun || analyze.isPending} onClick={() => lastRun && analyze.mutate(lastRun)}>
            Re-run analysis
          </Button>
        </div>
      </div>

      <Card icon="impact" title="Scope">
        <div className="space-y-4">
          <section aria-label="Pick a target" className="space-y-2">
            <h3 className="font-mono text-label-sm uppercase tracking-wider text-muted">Target symbol</h3>
            <form onSubmit={search} role="search" className="flex flex-wrap gap-2">
              <label className="sr-only" htmlFor="impact-symbol">
                Class or method name
              </label>
              <Input id="impact-symbol" icon="search" hint="⌘K" value={text} onChange={(event) => setText(event.target.value)} maxLength={200} placeholder="Class or method, e.g. PaymentService.calculatePayment" className="min-w-0 flex-1" />
              <Button type="submit" variant="secondary">
                Find symbols
              </Button>
            </form>
            {symbols.isPending && <SkeletonRows rows={1} label="Loading symbols" />}
            {symbols.error && (
              <p role="alert" className="text-body-md text-[color:var(--t-failed-fg)]">
                {symbols.error.message}
              </p>
            )}
            {symbols.data?.status === 'NOT_SUPPORTED' && (
              <Notice>
                <span role="note">{symbols.data.reason}</span>
              </Notice>
            )}
            {symbols.data?.status === 'OK' && (
              <ul aria-label="Matching symbols" className="max-h-64 space-y-1 overflow-y-auto text-body-md">
                {symbols.data.symbols.length === 0 && <li className="text-fg-2">No class or method matches “{query}”.</li>}
                {symbols.data.symbols.map((symbol) => (
                  <li key={symbol.id}>
                    <button
                      type="button"
                      onClick={() => setTarget(symbol)}
                      aria-pressed={target?.id === symbol.id}
                      className={`flex w-full flex-wrap items-center gap-2 rounded px-2 py-1 text-left hover:bg-raised ${target?.id === symbol.id ? 'bg-raised ring-1 ring-primary' : ''}`}
                    >
                      <KindBadge kind={symbol.kind} />
                      <span className="font-mono">{symbol.name}</span>
                      {symbol.layer && <Badge tone="info">{symbol.layer.toLowerCase()}</Badge>}
                      <span className="break-all font-mono text-body-sm text-muted">{symbol.filePath}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <div className="flex flex-wrap items-center gap-x-6 gap-y-2">
            <div className="space-y-1">
              <h3 className="font-mono text-label-sm uppercase tracking-wider text-muted">Propagation traversal</h3>
              <SegmentedControl label="Depth" value={String(depth)} onChange={(v) => setDepth(Number(v))} options={HOPS} />
            </div>
            <fieldset className="space-y-1">
              <legend className="font-mono text-label-sm uppercase tracking-wider text-muted">View filters (applied to the result)</legend>
              <div className="flex flex-wrap gap-4">
                <Checkbox label="Include test callers" checked={filters.includeTests} onChange={(e) => setFilters({ ...filters, includeTests: e.target.checked })} />
                <Checkbox label="Cross-module traversal" checked={filters.crossModule} onChange={(e) => setFilters({ ...filters, crossModule: e.target.checked })} />
                <Checkbox label="HTTP endpoint ingress" checked={filters.endpoints} onChange={(e) => setFilters({ ...filters, endpoints: e.target.checked })} />
              </div>
            </fieldset>
          </div>

          <section aria-label="Describe the change" className="ai-surface space-y-2 p-3">
            <h3 className="font-mono text-label-sm uppercase tracking-wider text-ai-text">Optional change intent (AI context layer)</h3>
            {aiAvailable ? (
              <>
                <label className="sr-only" htmlFor="impact-description">
                  Change description
                </label>
                <textarea id="impact-description" value={description} onChange={(event) => setDescription(event.target.value)} maxLength={1000} rows={2} placeholder="e.g. round payment totals to whole cents" className="w-full rounded border border-line-strong bg-canvas px-3 py-1.5 text-body-lg" />
                <div className="flex flex-wrap items-center gap-2">
                  <Button variant="ai" icon="sparkle" disabled={!description.trim() || suggest.isPending} onClick={() => suggest.mutate(description.trim())}>
                    Suggest symbols
                  </Button>
                  <span className="text-body-sm text-fg-2">A model proposes symbols that exist in this snapshot; you confirm one before anything is analysed. The description is only context for the summary.</span>
                </div>
                {suggest.data && suggest.data.status !== 'OK' && (
                  <p role="note" className="text-body-md text-fg-2">
                    Suggestions {suggest.data.status.toLowerCase()}: {suggest.data.detail ?? 'no reason given'}. Use the picker above.
                  </p>
                )}
                {suggest.data?.status === 'OK' && (
                  <ul aria-label="Suggested symbols" className="space-y-1 text-body-md">
                    {suggest.data.detail && <li className="text-fg-2">{suggest.data.detail}</li>}
                    {suggest.data.suggestions.map((suggestion) => (
                      <li key={suggestion.symbolId} className="flex flex-wrap items-center gap-2">
                        <span className="font-mono">{suggestion.name}</span>
                        <span className="text-body-sm text-fg-2">{suggestion.reason}</span>
                        <Button
                          variant="secondary"
                          onClick={() =>
                            setTarget({ id: suggestion.symbolId, kind: suggestion.kind, name: suggestion.name, qualifiedName: suggestion.qualifiedName, filePath: suggestion.filePath, line: suggestion.line, layer: null })
                          }
                        >
                          Use this symbol
                        </Button>
                      </li>
                    ))}
                  </ul>
                )}
              </>
            ) : (
              <>
                <p className="text-body-md text-fg-2">Change description needs AI, which is unavailable. Pick a symbol instead.</p>
                <AIAvailabilityNote />
              </>
            )}
          </section>

          <section aria-label="Run" className="flex flex-wrap items-center gap-3">
            <Button variant="secondary" disabled={!target} onClick={() => setTarget(null)}>
              Clear selection
            </Button>
            <Button variant="primary" icon="impact" disabled={!target} busy={analyze.isPending} onClick={() => run(false)}>
              Analyze impact
            </Button>
            {aiAvailable && (
              <Button variant="ai" icon="sparkle" disabled={!target || analyze.isPending} onClick={() => run(true)}>
                Analyze and summarize with AI
              </Button>
            )}
            <span className="text-body-md text-fg-2">
              {target ? (
                <>
                  Target: <span className="font-mono">{target.name}</span>
                </>
              ) : (
                'Pick a target first.'
              )}
            </span>
          </section>
        </div>
      </Card>

      {analyze.isPending && <SkeletonRows rows={3} label="Analyzing" />}
      {analyze.error && (
        <p role="alert" className="text-body-md text-[color:var(--t-failed-fg)]">
          {analyze.error.message}
        </p>
      )}
      {analyze.data && !analyze.isPending && <ImpactResultView result={analyze.data} filters={filters} isMaven={isMaven} />}
    </div>
  )
}
