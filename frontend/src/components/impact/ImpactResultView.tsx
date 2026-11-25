import { useMemo, useState } from 'react'
import type { ImpactAnalysis, ImpactComponent, ImpactTest } from '../../types/api'
import AIGeneratedBadge from '../ai/AIGeneratedBadge'
import MarkdownText from '../ai/MarkdownText'
import MermaidDiagram from '../architecture/MermaidDiagram'
import SourceLink from '../rag/SourceLink'
import Badge from '../ui/Badge'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import { Tabs } from '../ui/Controls'
import CopyButton from '../ui/CopyButton'
import { DataTable } from '../ui/Data'
import Icon from '../ui/icons'
import { Card, KeyValueList, Notice } from '../ui/Layout'
import { HttpMethodBadge } from '../ui/MoreBadges'

export interface ImpactViewFilters {
  includeTests: boolean
  crossModule: boolean
  endpoints: boolean
}

export const DEFAULT_FILTERS: ImpactViewFilters = { includeTests: true, crossModule: true, endpoints: true }

const RELATION_TEXT: Record<ImpactTest['relation'], string> = {
  DIRECT_CALL: 'calls the target',
  NAMING_CONVENTION: 'named for the target class',
  TYPE_REFERENCE: 'mentions the class only',
  AFFECTED_COMPONENT: 'covers an affected component',
}

const LEVEL_TONE = { LOW: 'success', MEDIUM: 'warning', HIGH: 'high' } as const

const simpleClass = (symbol: string) => symbol.slice(symbol.lastIndexOf('.') + 1)

/** The suggested command is text for a person to run; RepoIntel never runs it. Maven projects only. */
export function suggestedCommand(tests: ImpactTest[], isMaven: boolean): string | null {
  if (!isMaven || tests.length === 0) return null
  return `./mvnw test -Dtest=${[...new Set(tests.map((t) => simpleClass(t.testSymbol)))].sort().join(',')}`
}

/** The result of one impact analysis. Every name, path and reason is repository text and is rendered as plain text. */
export default function ImpactResultView({
  result,
  filters = DEFAULT_FILTERS,
  isMaven = false,
}: {
  result: ImpactAnalysis
  filters?: ImpactViewFilters
  isMaven?: boolean
}) {
  const [active, setActive] = useState<ImpactComponent | null>(null)
  if (result.status === 'NOT_SUPPORTED') {
    return <UnsupportedImpact reason={result.reason} />
  }
  const components = result.components.filter((c) => filters.crossModule || !result.target.modulePath || !c.modulePath || c.modulePath === result.target.modulePath)
  const visible: ImpactAnalysis = { ...result, components, endpoints: filters.endpoints ? result.endpoints : [], tests: filters.includeTests ? result.tests : [] }
  const command = suggestedCommand(visible.tests, isMaven)
  const empty = result.components.length === 0 && result.endpoints.length === 0 && result.relatedData.length === 0

  return (
    <div className="space-y-5">
      <header className="space-y-1">
        <h3 className="text-headline-md font-semibold">
          Impact of changing <span className="font-mono">{result.target.name}</span>
        </h3>
        <p className="flex flex-wrap items-center gap-x-2 text-body-sm text-muted">
          {result.target.kind === 'METHOD' ? 'Method' : 'Type'}
          {result.target.layer ? `, ${result.target.layer.toLowerCase()} layer` : ''} · depth {result.depth} · commit {result.commitSha.slice(0, 7)} ·{' '}
          {result.target.filePath && <SourceLink filePath={result.target.filePath} startLine={result.target.startLine} endLine={result.target.endLine} sourceUrl={result.target.sourceUrl} />}
        </p>
        <p className="text-body-sm text-muted">
          Computed from the stored syntax-tree graph, not by a model. {result.totalDependents} dependent component(s) found{result.truncated ? '; the list is capped' : ''}.
        </p>
      </header>

      {result.aiSummary && <AiSummary summary={result.aiSummary} command={command} />}
      {result.risk && <RiskPanel risk={result.risk} />}
      {empty && <Notice>Nothing depends on this symbol in this snapshot.</Notice>}
      <ComponentsPanel result={visible} onSelect={setActive} active={active} />
      {result.diagram.chain && <MermaidDiagram title="Dependency chain to the target" source={result.diagram.chain} icon="impact" />}
      {result.diagram.note && <p className="text-body-sm text-muted">{result.diagram.note}</p>}
      {active && <ActiveNode component={active} />}
      <Tests result={visible} command={command} />
      <RegressionAreas result={visible} />
      <section aria-label="Limitations" className="space-y-2">
        <h4 className="text-headline-md font-semibold">Static analysis soundness & known limitations</h4>
        <ul className="grid gap-3 md:grid-cols-2">
          {result.limitations.map((limitation, index) => (
            <li key={index} className="flex gap-2 rounded-lg border border-line bg-canvas p-3 text-body-md text-fg-2">
              <Icon name={/reflect/i.test(limitation) ? 'cycle' : /dispatch/i.test(limitation) ? 'route' : /generated/i.test(limitation) ? 'file' : 'info'} size={16} className="mt-0.5 text-muted" />
              {limitation}
            </li>
          ))}
        </ul>
      </section>
    </div>
  )
}

export function UnsupportedImpact({ reason }: { reason: string | null }) {
  return (
    <Notice>
      <span role="note">Not supported: {reason}</span>
    </Notice>
  )
}

function RiskPanel({ risk }: { risk: NonNullable<ImpactAnalysis['risk']> }) {
  const marker = Math.min(100, Math.max(0, (risk.score / risk.maxScore) * 100))
  return (
    <section aria-label="Risk factors" className="space-y-3 rounded-lg border border-line bg-surface p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h4 className="text-headline-md font-semibold">Heuristic risk score</h4>
        <span className="text-headline-xl font-semibold" data-testid="risk-score">
          {risk.score}
          <span className="text-body-md font-normal text-muted"> / {risk.maxScore}</span>
        </span>
        <Badge tone={LEVEL_TONE[risk.level]} icon="warning">{risk.level}</Badge>
      </div>
      <div className="relative" aria-label="Risk bands: low 0-29, medium 30-59, high 60 and above">
        <div className="flex h-2 overflow-hidden rounded">
          <div className="bg-[color:var(--t-success)]" style={{ width: '30%' }} />
          <div className="bg-[color:var(--t-warning)]" style={{ width: '30%' }} />
          <div className="bg-[color:var(--t-high)]" style={{ width: '40%' }} />
        </div>
        <div className="absolute -top-1 h-4 w-0.5 bg-fg" style={{ left: `${marker}%` }} aria-hidden="true" />
        <div className="mt-1 flex justify-between font-mono text-label-sm text-muted">
          <span>0-29 LOW</span>
          <span>30-59 MEDIUM</span>
          <span>60+ HIGH</span>
        </div>
      </div>
      <p className="text-body-md font-medium">Heuristic risk score, not a probability.</p>
      <p className="text-body-sm text-muted">{risk.label}</p>
      <ul className="grid gap-2 sm:grid-cols-2 lg:grid-cols-3">
        {risk.factors.map((factor) => {
          const reduces = factor.points < 0
          return (
            <li key={factor.id} className={`rounded-lg border p-2 ${reduces ? 'tone tone-success' : 'border-line bg-canvas'}`}>
              <p className="text-body-md font-medium text-fg">{factor.label}</p>
              <p className="font-mono text-body-sm text-fg-2">
                {factor.value} · {factor.points} / {factor.maxPoints}
                {reduces && <span className="ml-1">reduces risk</span>}
              </p>
              <details>
                <summary className="cursor-pointer text-body-sm text-fg-2">Why</summary>
                <p className="text-body-sm text-fg-2">{factor.explanation}</p>
              </details>
            </li>
          )
        })}
      </ul>
      <p className="text-body-sm text-muted">{risk.note}</p>
    </section>
  )
}

type Tab = 'all' | 'routes' | 'callers' | 'repos'

function ComponentsPanel({ result, onSelect, active }: { result: ImpactAnalysis; onSelect: (c: ImpactComponent) => void; active: ImpactComponent | null }) {
  const [tab, setTab] = useState<Tab>('all')
  const children = useMemo(() => {
    const byParent = new Map<string, ImpactComponent[]>()
    for (const component of result.components) {
      const parent = component.viaId ?? result.target.id
      byParent.set(parent, [...(byParent.get(parent) ?? []), component])
    }
    return byParent
  }, [result])
  const show = (t: Tab) => tab === 'all' || tab === t
  return (
    <section aria-label="Affected components" className="space-y-3">
      <h4 className="text-headline-md font-semibold">Affected components ({result.components.length})</h4>
      <Tabs
        label="Affected component groups"
        idPrefix="impact"
        active={tab}
        onChange={(id) => setTab(id as Tab)}
        tabs={[
          { id: 'all', label: 'All', count: result.components.length + result.endpoints.length + result.relatedData.length },
          { id: 'routes', label: 'Routes', count: result.endpoints.length },
          { id: 'callers', label: 'Callers', count: result.components.length },
          { id: 'repos', label: 'Repos', count: result.relatedData.length },
        ]}
      />
      {show('routes') && <Endpoints result={result} />}
      {show('callers') &&
        (result.components.length === 0 ? (
          <p className="text-body-md text-fg-2">Nothing depends on the target within the analysed depth.</p>
        ) : (
          <div className="space-y-1">
            <h5 className="font-mono text-label-sm uppercase tracking-wider text-muted">[2] Direct callers and indirect dependents</h5>
            <ul className="space-y-1 text-body-md">
              <ComponentNodes parentId={result.target.id} children={children} onSelect={onSelect} activeId={active?.id} />
            </ul>
          </div>
        ))}
      {show('repos') && <RelatedAndModules result={result} />}
    </section>
  )
}

function ComponentNodes({ parentId, children, onSelect, activeId }: { parentId: string; children: Map<string, ImpactComponent[]>; onSelect: (c: ImpactComponent) => void; activeId?: string }) {
  return (
    <>
      {(children.get(parentId) ?? []).map((component) => (
        <li key={component.id}>
          <div className={`flex flex-wrap items-center gap-2 rounded px-1 ${activeId === component.id ? 'bg-raised' : ''}`}>
            <Icon name={component.kind === 'METHOD' ? 'method' : 'class'} size={14} className="text-fg-2" />
            <button type="button" onClick={() => onSelect(component)} className="font-mono text-body-md hover:underline" aria-label={`Inspect ${component.name}`}>
              {component.name}
            </button>
            <ConfidenceBadge confidence={component.confidence} />
            {component.layer && <Badge tone="neutral">{component.layer.toLowerCase()}</Badge>}
            <span className="text-body-sm text-muted">
              {component.depth === 1 ? 'direct' : 'indirect'} · depth {component.depth} · {component.relationships.join(', ').toLowerCase()}
            </span>
            {component.filePath && <SourceLink filePath={component.filePath} startLine={component.line} endLine={null} sourceUrl={component.sourceUrl} />}
          </div>
          <p className="ml-6 text-body-sm text-fg-2">{component.reason}</p>
          {(children.get(component.id) ?? []).length > 0 && (
            <ul className="ml-4 mt-1 space-y-1 border-l border-line pl-3">
              <ComponentNodes parentId={component.id} children={children} onSelect={onSelect} activeId={activeId} />
            </ul>
          )}
        </li>
      ))}
    </>
  )
}

function ActiveNode({ component }: { component: ImpactComponent }) {
  return (
    <Card tier={2} icon="eye" title="Active node" meta="Selected from the list (diagram click handlers stay disabled in strict mode)">
      <KeyValueList
        items={[
          { label: 'Symbol', value: <span className="font-mono">{component.qualifiedName}</span> },
          { label: 'Type', value: component.kind === 'METHOD' ? 'Method' : 'Type' },
          { label: 'Layer', value: component.layer?.toLowerCase() ?? '—' },
          { label: 'File', value: component.filePath ? <SourceLink filePath={component.filePath} startLine={component.line} endLine={null} sourceUrl={component.sourceUrl} /> : '—' },
          { label: 'Reason', value: component.reason },
        ]}
      />
    </Card>
  )
}

function Endpoints({ result }: { result: ImpactAnalysis }) {
  return (
    <section aria-label="Endpoints" className="space-y-1">
      <h5 className="font-mono text-label-sm uppercase tracking-wider text-muted">[1] HTTP endpoint ingress ({result.endpoints.length})</h5>
      {result.endpoints.length === 0 ? (
        <p className="text-body-md text-fg-2">No REST endpoint handler reaches the target in the resolved graph.</p>
      ) : (
        <ul className="space-y-1 text-body-md">
          {result.endpoints.map((endpoint) => (
            <li key={`${endpoint.httpMethod} ${endpoint.route} ${endpoint.handler}`} className="flex flex-wrap items-center gap-2">
              <HttpMethodBadge method={endpoint.httpMethod} />
              <span className="font-mono">{endpoint.route}</span>
              <ConfidenceBadge confidence={endpoint.confidence} />
              <span className="text-body-sm text-muted">{endpoint.handler}</span>
              {endpoint.filePath && <SourceLink filePath={endpoint.filePath} startLine={endpoint.line} endLine={null} sourceUrl={endpoint.sourceUrl} />}
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
        <h5 className="font-mono text-label-sm uppercase tracking-wider text-muted">[3] Downstream repositories & entities</h5>
        {result.relatedData.length === 0 ? (
          <p className="text-body-md text-fg-2">None found.</p>
        ) : (
          <ul className="space-y-1 text-body-md">
            {result.relatedData.map((item) => (
              <li key={item.id}>
                <Badge tone="neutral" icon={item.kind === 'ENTITY' ? 'entity' : 'database'}>{item.kind.toLowerCase()}</Badge> <span className="font-mono">{item.qualifiedName}</span> <ConfidenceBadge confidence={item.confidence} />
                <p className="text-body-sm text-fg-2">{item.relationship}</p>
              </li>
            ))}
          </ul>
        )}
      </section>
      <section aria-label="Modules" className="space-y-1">
        <h5 className="font-mono text-label-sm uppercase tracking-wider text-muted">[4] Impacted modules & subsystems</h5>
        {result.modules.length === 0 ? (
          <p className="text-body-md text-fg-2">No module information (single-module build or no Maven modules).</p>
        ) : (
          <ul className="space-y-1 text-body-md">
            {result.modules.map((module) => (
              <li key={`${module.modulePath}-${module.reason}`}>
                <span className="font-mono">{module.modulePath}</span> <ConfidenceBadge confidence={module.confidence} />
                <p className="text-body-sm text-fg-2">
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

function Tests({ result, command }: { result: ImpactAnalysis; command: string | null }) {
  const detected = result.tests.filter((t) => t.confidence === 'DETECTED').length
  const likely = result.tests.filter((t) => t.confidence !== 'DETECTED').length
  const untested = result.regressionAreas.filter((a) => a.kind === 'GAP').length
  return (
    <section aria-label="Tests" className="space-y-2">
      <h4 className="flex flex-wrap items-center gap-2 text-headline-md font-semibold">
        Affected tests & suggested regression scope ({result.tests.length})
        <Badge tone="detected">{detected} detected</Badge>
        <Badge tone="likely">{likely} likely</Badge>
        <Badge tone="neutral">{untested} untested codepaths</Badge>
      </h4>
      {result.tests.length === 0 ? (
        <p className="text-body-md text-fg-2">No test was linked to the target or to an affected component.</p>
      ) : (
        <DataTable
          caption="Affected tests"
          rows={result.tests}
          rowKey={(t) => t.testSymbol}
          columns={[
            {
              key: 't',
              header: 'Test',
              cell: (t) => (
                <span>
                  <span className="font-mono text-body-sm">{t.testSymbol}</span>
                  {t.methods.length > 0 && <span className="block text-body-sm text-muted">in {t.methods.join(', ')}</span>}
                  <SourceLink filePath={t.filePath} startLine={t.line} endLine={null} sourceUrl={t.sourceUrl} />
                </span>
              ),
            },
            { key: 'c', header: 'Classification', cell: (t) => <ConfidenceBadge confidence={t.confidence} /> },
            { key: 'r', header: 'Detection rationale', cell: (t) => <span className="text-body-sm">{RELATION_TEXT[t.relation]}{t.via ? ` (${t.via})` : ''}</span> },
            { key: 'a', header: 'Action', cell: (t) => <CopyButton value={`./mvnw test -Dtest=${simpleClass(t.testSymbol)}`} label="Copy command" labelOnly compact /> },
          ]}
        />
      )}
      {command && (
        <div className="rounded-lg border border-line bg-canvas p-3">
          <p className="font-mono text-label-sm uppercase tracking-wider text-muted">Suggested command (not run by RepoIntel)</p>
          <p className="mt-1 flex items-center gap-2 font-mono text-code-md">
            <code className="break-all">{command}</code>
            <CopyButton value={command} label="Copy suggested command" labelOnly compact />
          </p>
        </div>
      )}
    </section>
  )
}

function RegressionAreas({ result }: { result: ImpactAnalysis }) {
  return (
    <section aria-label="Suggested regression areas" className="space-y-1">
      <h4 className="text-headline-md font-semibold">Suggested regression areas</h4>
      <ul className="space-y-1 text-body-md">
        {result.regressionAreas.map((area, index) => (
          <li key={index} className="flex flex-wrap items-center gap-2">
            <Badge tone={area.kind === 'GAP' ? 'warning' : 'neutral'}>{area.kind === 'GAP' ? 'GAP' : area.kind.toLowerCase()}</Badge> {area.area} <span className="text-body-sm text-fg-2">{area.reason}</span>
          </li>
        ))}
      </ul>
    </section>
  )
}

function AiSummary({ summary, command }: { summary: NonNullable<ImpactAnalysis['aiSummary']>; command: string | null }) {
  if (!summary.aiGenerated || !summary.summary) {
    return (
      <Notice>
        <span role="note">
          AI summary {summary.status === 'UNAVAILABLE' ? 'unavailable' : summary.status.toLowerCase()}: {summary.detail ?? 'no reason given'}. The analysis below does not depend on it.
        </span>
      </Notice>
    )
  }
  return (
    <section aria-label="AI summary" className="ai-surface space-y-2 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Icon name="sparkle" size={18} className="text-ai-text" />
        <h4 className="text-headline-md font-semibold">AI-generated impact digest</h4>
        <AIGeneratedBadge />
        <span className="font-mono text-body-sm text-fg-2">
          {summary.provider} / {summary.model} · prompt {summary.promptVersion}
        </span>
        {summary.fromCache && <Badge tone="neutral" icon="database">cached</Badge>}
      </div>
      <MarkdownText text={summary.summary} />
      {summary.keyPoints.length > 0 && (
        <ul className="list-disc space-y-1 pl-5 text-body-lg">
          {summary.keyPoints.map((point, index) => (
            <li key={index}>{point}</li>
          ))}
        </ul>
      )}
      {summary.regressionFocus.length > 0 && (
        <div>
          <p className="text-body-md font-medium">Regression focus</p>
          <ul className="list-disc space-y-1 pl-5 text-body-lg">
            {summary.regressionFocus.map((point, index) => (
              <li key={index}>{point}</li>
            ))}
          </ul>
        </div>
      )}
      {command && (
        <p className="flex items-center gap-2 text-body-sm text-fg-2">
          Suggested next step (not run by RepoIntel): <code className="font-mono">{command}</code> <CopyButton value={command} label="Copy next step" labelOnly compact />
        </p>
      )}
      <p className="text-body-sm text-muted">The model only restates the analysis; it cannot add components, tests or numbers. Check the evidence.</p>
    </section>
  )
}
