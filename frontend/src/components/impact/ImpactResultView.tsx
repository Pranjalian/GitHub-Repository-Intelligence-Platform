import { useMemo } from 'react'
import type { ImpactAnalysis, ImpactComponent, ImpactTest } from '../../types/api'
import AIGeneratedBadge from '../ai/AIGeneratedBadge'
import MarkdownText from '../ai/MarkdownText'
import MermaidDiagram from '../architecture/MermaidDiagram'
import SourceLink from '../rag/SourceLink'
import Badge, { type BadgeTone } from '../ui/Badge'
import ConfidenceBadge from '../ui/ConfidenceBadge'

const LEVEL_TONE: Record<'LOW' | 'MEDIUM' | 'HIGH', BadgeTone> = { LOW: 'success', MEDIUM: 'warning', HIGH: 'danger' }

const RELATION_TEXT: Record<ImpactTest['relation'], string> = {
  DIRECT_CALL: 'calls the target',
  NAMING_CONVENTION: 'named for the target class',
  TYPE_REFERENCE: 'mentions the class only',
  AFFECTED_COMPONENT: 'covers an affected component',
}

/** The result of one impact analysis. Every name, path and reason is repository text and is rendered as plain text. */
export default function ImpactResultView({ result }: { result: ImpactAnalysis }) {
  if (result.status === 'NOT_SUPPORTED') {
    return (
      <p role="note" className="rounded bg-amber-50 p-3 text-sm text-amber-900">
        Not supported: {result.reason}
      </p>
    )
  }
  return (
    <div className="space-y-6">
      <header className="space-y-1">
        <h3 className="text-lg font-semibold">
          Impact of changing <span className="font-mono">{result.target.name}</span>
        </h3>
        <p className="text-xs text-slate-600">
          {result.target.kind === 'METHOD' ? 'Method' : 'Type'}
          {result.target.layer ? `, ${result.target.layer.toLowerCase()} layer` : ''} · depth {result.depth} · commit{' '}
          {result.commitSha.slice(0, 7)} ·{' '}
          {result.target.filePath && (
            <SourceLink
              filePath={result.target.filePath}
              startLine={result.target.startLine}
              endLine={result.target.endLine}
              sourceUrl={result.target.sourceUrl}
            />
          )}
        </p>
        <p className="text-xs text-slate-600">
          Computed from the stored syntax-tree graph, not by a model. {result.totalDependents} dependent component(s) found
          {result.truncated ? '; the list is capped' : ''}.
        </p>
      </header>

      {result.risk && <RiskPanel risk={result.risk} />}
      <ComponentsTree result={result} />
      <Endpoints result={result} />
      <RelatedAndModules result={result} />
      <Tests result={result} />
      <RegressionAreas result={result} />
      {result.diagram.chain && <MermaidDiagram title="Dependency chain to the target" source={result.diagram.chain} />}
      {result.diagram.note && <p className="text-xs text-slate-600">{result.diagram.note}</p>}
      <section aria-label="Limitations" className="space-y-1">
        <h4 className="text-base font-semibold">Limitations</h4>
        <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700">
          {result.limitations.map((limitation, index) => (
            <li key={index}>{limitation}</li>
          ))}
        </ul>
      </section>
      {result.aiSummary && <AiSummary summary={result.aiSummary} />}
    </div>
  )
}

function RiskPanel({ risk }: { risk: NonNullable<ImpactAnalysis['risk']> }) {
  return (
    <section aria-label="Risk factors" className="space-y-2 rounded border border-slate-200 bg-white p-3">
      <div className="flex flex-wrap items-center gap-3">
        <h4 className="text-base font-semibold">Heuristic risk score</h4>
        <span className="text-2xl font-bold" data-testid="risk-score">
          {risk.score}
          <span className="text-sm font-normal text-slate-500"> / {risk.maxScore}</span>
        </span>
        <Badge tone={LEVEL_TONE[risk.level]}>{risk.level}</Badge>
      </div>
      <p className="text-xs text-slate-600">{risk.label}</p>
      <table className="w-full text-left text-sm">
        <thead className="text-xs text-slate-500">
          <tr>
            <th className="py-1 pr-2 font-medium">Factor</th>
            <th className="py-1 pr-2 font-medium">Value</th>
            <th className="py-1 pr-2 font-medium">Points</th>
            <th className="py-1 font-medium">Why</th>
          </tr>
        </thead>
        <tbody>
          {risk.factors.map((factor) => (
            <tr key={factor.id} className="border-t border-slate-100 align-top">
              <td className="py-1 pr-2 font-medium">{factor.label}</td>
              <td className="py-1 pr-2">{factor.value}</td>
              <td className="py-1 pr-2 whitespace-nowrap">
                {factor.points} / {factor.maxPoints}
              </td>
              <td className="py-1 text-xs text-slate-600">{factor.explanation}</td>
            </tr>
          ))}
        </tbody>
      </table>
      <p className="text-xs text-slate-500">{risk.note}</p>
    </section>
  )
}

function ComponentsTree({ result }: { result: ImpactAnalysis }) {
  const children = useMemo(() => {
    const byParent = new Map<string, ImpactComponent[]>()
    for (const component of result.components) {
      const parent = component.viaId ?? result.target.id
      byParent.set(parent, [...(byParent.get(parent) ?? []), component])
    }
    return byParent
  }, [result])
  return (
    <section aria-label="Affected components" className="space-y-2">
      <h4 className="text-base font-semibold">Affected components ({result.components.length})</h4>
      {result.components.length === 0 ? (
        <p className="text-sm text-slate-600">Nothing depends on the target within the analysed depth.</p>
      ) : (
        <ul className="space-y-1 text-sm">
          <ComponentNodes parentId={result.target.id} children={children} />
        </ul>
      )}
    </section>
  )
}

function ComponentNodes({ parentId, children }: { parentId: string; children: Map<string, ImpactComponent[]> }) {
  return (
    <>
      {(children.get(parentId) ?? []).map((component) => (
        <li key={component.id}>
          <div className="flex flex-wrap items-center gap-2">
            <span className="font-mono">{component.name}</span>
            <ConfidenceBadge confidence={component.confidence} />
            {component.layer && <Badge>{component.layer.toLowerCase()}</Badge>}
            <span className="text-xs text-slate-500">
              depth {component.depth} · {component.relationships.join(', ').toLowerCase()}
            </span>
            {component.filePath && (
              <SourceLink filePath={component.filePath} startLine={component.line} endLine={null} sourceUrl={component.sourceUrl} />
            )}
          </div>
          <p className="text-xs text-slate-600">{component.reason}</p>
          {(children.get(component.id) ?? []).length > 0 && (
            <ul className="ml-4 mt-1 space-y-1 border-l border-slate-200 pl-3">
              <ComponentNodes parentId={component.id} children={children} />
            </ul>
          )}
        </li>
      ))}
    </>
  )
}

function Endpoints({ result }: { result: ImpactAnalysis }) {
  return (
    <section aria-label="Endpoints" className="space-y-1">
      <h4 className="text-base font-semibold">Endpoints that reach the target ({result.endpoints.length})</h4>
      {result.endpoints.length === 0 ? (
        <p className="text-sm text-slate-600">No REST endpoint handler reaches the target in the resolved graph.</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {result.endpoints.map((endpoint) => (
            <li key={`${endpoint.httpMethod} ${endpoint.route} ${endpoint.handler}`} className="flex flex-wrap items-center gap-2">
              <span className="font-mono font-semibold">{endpoint.httpMethod}</span>
              <span className="font-mono">{endpoint.route}</span>
              <ConfidenceBadge confidence={endpoint.confidence} />
              <span className="text-xs text-slate-500">{endpoint.handler}</span>
              {endpoint.filePath && (
                <SourceLink filePath={endpoint.filePath} startLine={endpoint.line} endLine={null} sourceUrl={endpoint.sourceUrl} />
              )}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function RelatedAndModules({ result }: { result: ImpactAnalysis }) {
  return (
    <div className="grid gap-4 md:grid-cols-2">
      <section aria-label="Entities and repositories" className="space-y-1">
        <h4 className="text-base font-semibold">Related entities and repositories</h4>
        {result.relatedData.length === 0 ? (
          <p className="text-sm text-slate-600">None found.</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {result.relatedData.map((item) => (
              <li key={item.id}>
                <Badge>{item.kind.toLowerCase()}</Badge> <span className="font-mono">{item.qualifiedName}</span>{' '}
                <ConfidenceBadge confidence={item.confidence} />
                <p className="text-xs text-slate-600">{item.relationship}</p>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-label="Modules" className="space-y-1">
        <h4 className="text-base font-semibold">Modules</h4>
        {result.modules.length === 0 ? (
          <p className="text-sm text-slate-600">No module information (single-module build or no Maven modules).</p>
        ) : (
          <ul className="space-y-1 text-sm">
            {result.modules.map((module) => (
              <li key={`${module.modulePath}-${module.reason}`}>
                <span className="font-mono">{module.modulePath}</span> <ConfidenceBadge confidence={module.confidence} />
                <p className="text-xs text-slate-600">
                  {module.reason === 'CONTAINS_AFFECTED'
                    ? `contains ${module.affectedSymbols} affected component(s)`
                    : `declares a dependency on the target's module (${module.depth} hop${module.depth === 1 ? '' : 's'})`}
                </p>
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  )
}

function Tests({ result }: { result: ImpactAnalysis }) {
  return (
    <section aria-label="Tests" className="space-y-1">
      <h4 className="text-base font-semibold">Tests ({result.tests.length})</h4>
      {result.tests.length === 0 ? (
        <p className="text-sm text-slate-600">No test was linked to the target or to an affected component.</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {result.tests.map((test) => (
            <li key={test.testSymbol}>
              <div className="flex flex-wrap items-center gap-2">
                <span className="font-mono">{test.testSymbol}</span>
                <ConfidenceBadge confidence={test.confidence} />
                <span className="text-xs text-slate-500">
                  {RELATION_TEXT[test.relation]}
                  {test.via ? ` (${test.via})` : ''}
                </span>
                <SourceLink filePath={test.filePath} startLine={test.line} endLine={null} sourceUrl={test.sourceUrl} />
              </div>
              {test.methods.length > 0 && <p className="text-xs text-slate-600">in {test.methods.join(', ')}</p>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function RegressionAreas({ result }: { result: ImpactAnalysis }) {
  return (
    <section aria-label="Suggested regression areas" className="space-y-1">
      <h4 className="text-base font-semibold">Suggested regression areas</h4>
      <ul className="space-y-1 text-sm">
        {result.regressionAreas.map((area, index) => (
          <li key={index}>
            <Badge tone={area.kind === 'GAP' ? 'warning' : 'neutral'}>{area.kind.toLowerCase()}</Badge> {area.area}{' '}
            <span className="text-xs text-slate-600">{area.reason}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function AiSummary({ summary }: { summary: NonNullable<ImpactAnalysis['aiSummary']> }) {
  if (!summary.aiGenerated || !summary.summary) {
    return (
      <p role="note" className="rounded bg-slate-50 p-3 text-sm text-slate-700">
        AI summary {summary.status === 'UNAVAILABLE' ? 'unavailable' : summary.status.toLowerCase()}: {summary.detail ?? 'no reason given'}. The
        analysis above does not depend on it.
      </p>
    )
  }
  return (
    <section aria-label="AI summary" className="space-y-2 rounded border border-violet-200 bg-violet-50/40 p-3">
      <div className="flex flex-wrap items-center gap-2">
        <h4 className="text-base font-semibold">Summary</h4>
        <AIGeneratedBadge />
        <span className="text-xs text-slate-500">
          {summary.provider} / {summary.model} · prompt {summary.promptVersion}
          {summary.fromCache ? ' · from cache' : ''}
        </span>
      </div>
      <MarkdownText text={summary.summary} />
      {summary.keyPoints.length > 0 && (
        <ul className="list-disc space-y-1 pl-5 text-sm">
          {summary.keyPoints.map((point, index) => (
            <li key={index}>{point}</li>
          ))}
        </ul>
      )}
      {summary.regressionFocus.length > 0 && (
        <div>
          <p className="text-sm font-medium">Regression focus</p>
          <ul className="list-disc space-y-1 pl-5 text-sm">
            {summary.regressionFocus.map((point, index) => (
              <li key={index}>{point}</li>
            ))}
          </ul>
        </div>
      )}
      <p className="text-xs text-slate-600">
        The model only restates the analysis above; it cannot add components, tests or numbers. Check the evidence.
      </p>
    </section>
  )
}
