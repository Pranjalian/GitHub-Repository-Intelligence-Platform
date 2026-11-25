import { useMemo, useState, type ReactNode } from 'react'
import { Link } from 'react-router'
import type {
  ArchitectureCycle,
  ArchitectureEndpoint,
  ArchitectureEntity,
  ArchitectureLayer,
  ArchitectureLayerGroup,
  ArchitectureReport,
  ChainStep,
  EdgeResolution,
  Injection,
  LayerClass,
  LayerMethod,
  PatternCandidate,
} from '../../types/api'
import AIAvailabilityNote from '../ai/AIAvailabilityNote'
import SymbolInsights from '../ai/SymbolInsights'
import Badge from '../ui/Badge'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import { Accordion, Input, Select } from '../ui/Controls'
import { DataTable } from '../ui/Data'
import Icon from '../ui/icons'
import { Card, UnavailableState } from '../ui/Layout'
import { HttpMethodBadge } from '../ui/MoreBadges'
import SeverityBadge from '../ui/SeverityBadge'
import StatusLabel from '../ui/StatusLabel'
import MermaidDiagram from './MermaidDiagram'

const LAYER_LABELS: Record<ArchitectureLayer, string> = {
  CONTROLLER: 'Controllers',
  SERVICE: 'Services',
  REPOSITORY: 'Repositories',
  ENTITY: 'Entities',
  DTO: 'DTOs',
  CONFIGURATION: 'Configuration',
  INFRASTRUCTURE: 'Infrastructure',
}

/** The "How" tag of a type row, from how the backend assigned the layer. */
const METHOD_LABELS: Record<LayerMethod, string> = {
  ANNOTATION: 'annotation',
  TYPE_HIERARCHY: 'Spring Data interface',
  API_SIGNATURE: 'endpoint signature',
  PACKAGE_HEURISTIC: 'package name only',
}

const RESOLUTION_TONES: Record<EdgeResolution, 'success' | 'warning' | 'neutral'> = {
  RESOLVED: 'success',
  UNRESOLVED: 'neutral',
  AMBIGUOUS: 'warning',
}

const shortName = (fqn: string | null | undefined): string => {
  if (!fqn) {
    return '—'
  }
  const type = fqn.includes('#') ? fqn.slice(0, fqn.indexOf('#')) : fqn
  return type.slice(type.lastIndexOf('.') + 1)
}

/** Only GitHub permalinks built by the backend are linked. */
function SourceLink({ url, children }: { url: string | null; children: ReactNode }) {
  if (!url || !url.startsWith('https://github.com/')) {
    return <>{children}</>
  }
  return (
    <a href={url} target="_blank" rel="noreferrer" className="text-primary-text hover:underline">
      {children}
    </a>
  )
}

function Section({ title, children, count, icon }: { title: string; children: ReactNode; count?: number; icon?: string }) {
  return (
    <section aria-label={title} className="space-y-2">
      <h3 className="flex items-center gap-2 text-headline-md font-semibold">
        {icon && <Icon name={icon} size={18} className="text-fg-2" />}
        {title}
        {count != null && <span className="text-body-md font-normal text-muted">({count})</span>}
      </h3>
      {children}
    </section>
  )
}

/** The architecture of one snapshot. For unsupported technologies it says so instead of showing empty sections. */
export default function ArchitectureView({ report, stackLabel, links }: { report: ArchitectureReport; stackLabel?: string; links?: { to: string; label: string }[] }) {
  if (!report.supported) {
    return <UnsupportedArchitecture report={report} links={links} />
  }
  const { diagrams } = report
  const noDiagrams = !diagrams.layers && !diagrams.apiFlow && !diagrams.entities && !diagrams.modules
  const cap = diagrams.notes.find((n) => /\d+ of \d+/.test(n))
  return (
    <div className="space-y-8">
      <Overview report={report} stackLabel={stackLabel} />
      <Section title="Diagrams" icon="architecture">
        <div className="grid gap-4 xl:grid-cols-2">
          {diagrams.layers && <MermaidDiagram title="Layers" source={diagrams.layers} icon="architecture" note={cap} />}
          {diagrams.apiFlow && <MermaidDiagram title="API flow" source={diagrams.apiFlow} icon="route" />}
          {diagrams.entities && <MermaidDiagram title="Entity relationships" source={diagrams.entities} icon="entity" />}
          {diagrams.modules && <MermaidDiagram title="Modules" source={diagrams.modules} icon="package" />}
        </div>
        {noDiagrams && <p className="text-body-md text-fg-2">Nothing to draw: no layers, endpoints, entities or module dependencies were found.</p>}
        {diagrams.notes.length > 0 && (
          <ul aria-label="Diagram notes" className="list-disc pl-5 text-body-sm text-muted">
            {diagrams.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        )}
      </Section>
      <LayerOverview layers={report.layers} injections={report.injections} repositoryId={report.repositoryId} snapshotId={report.snapshotId} />
      <EndpointTable endpoints={report.endpoints} />
      <EntityView entities={report.entities} />
      <DiChains chains={report.diChains} />
      {report.cycles.length > 0 ? <CycleWarnings cycles={report.cycles} /> : <Section title="Dependency cycles" count={0}><p className="text-body-md text-fg-2">No cycles detected.</p></Section>}
      <InjectionTable injections={report.injections} />
      {report.moduleDependencies.length > 0 && (
        <Section title="Module dependencies" count={report.moduleDependencies.length}>
          <ul className="space-y-1 text-body-md">
            {report.moduleDependencies.map((dependency) => (
              <li key={dependency.source + '>' + dependency.target}>
                <span className="font-mono">{dependency.source}</span> → <span className="font-mono">{dependency.target}</span>
                {dependency.evidence[0] && (
                  <span className="ml-2 text-body-sm text-muted">
                    {dependency.evidence[0].path}
                    {dependency.evidence[0].line != null && `:${dependency.evidence[0].line}`}
                  </span>
                )}
              </li>
            ))}
          </ul>
        </Section>
      )}
      <PatternList patterns={report.patterns} />
      <Limitations limitations={report.limitations} />
    </div>
  )
}

/** Not supported (for example Python): says so, lists the detected technologies and points to what is available. */
export function UnsupportedArchitecture({ report, links }: { report: ArchitectureReport; links?: { to: string; label: string }[] }) {
  return (
    <div className="space-y-4">
      <UnavailableState
        icon="architecture"
        title="Architecture analysis is not supported for this technology"
        reason="Architecture discovery currently exists for Java and Spring Boot. The structure, dependency, security and test sections of the report are not affected."
      />
      {report.unsupported.length > 0 && (
        <ul aria-label="Unsupported technologies" className="flex flex-wrap gap-2">
          {report.unsupported.map((item) => (
            <li key={item}>
              <Badge tone="neutral">{item}</Badge>
            </li>
          ))}
        </ul>
      )}
      {links && (
        <p className="flex flex-wrap items-center gap-3 text-body-md">
          Available instead:
          {links.map((l) => (
            <Link key={l.to} to={l.to} className="text-primary-text hover:underline">
              {l.label}
            </Link>
          ))}
        </p>
      )}
    </div>
  )
}

function Overview({ report, stackLabel }: { report: ArchitectureReport; stackLabel?: string }) {
  const summary = report.summary
  const count = (layer: ArchitectureLayer) => report.layers.find((g) => g.layer === layer)?.total ?? 0
  const unresolved = (summary.unresolvedRelationships ?? 0) + (summary.ambiguousRelationships ?? 0)
  const chips: [string, number][] = [
    ['controllers', count('CONTROLLER')],
    ['repositories', count('REPOSITORY')],
    ['services', count('SERVICE')],
    ['DTOs', count('DTO')],
    ['configuration', count('CONFIGURATION')],
    ['entities', summary.entities ?? count('ENTITY')],
    ['endpoints', summary.endpoints ?? report.endpoints.length],
  ]
  const items: [string, number | undefined][] = [
    ['Types', summary.types],
    ['Layered types', summary.layeredTypes],
    ['Endpoints', summary.endpoints],
    ['Entities', summary.entities],
    ['Relationships', summary.relationships],
    ['Unresolved', summary.unresolvedRelationships],
    ['Ambiguous', summary.ambiguousRelationships],
  ]
  return (
    <Section title="Overview">
      <div className="flex flex-wrap items-center gap-2">
        <Badge tone="primary" icon="architecture">AST + graph inference</Badge>
        <StatusLabel status={report.status} />
        {stackLabel && <Badge tone="neutral">{stackLabel}</Badge>}
        <span className="ml-auto font-mono text-body-sm text-fg-2">{stackLabel ? `${stackLabel} · ` : ''}{report.commitSha.slice(0, 7)}</span>
      </div>
      <ul aria-label="Summary chips" className="flex flex-wrap gap-2">
        {chips.filter(([label, n]) => n > 0 || label === 'controllers' || label === 'endpoints').map(([label, n]) => (
          <li key={label} className="rounded border border-line bg-surface px-2 py-1 text-body-md">
            <span className="font-mono font-semibold">{n}</span> {label}
          </li>
        ))}
        {unresolved > 0 && (
          <li>
            <a href="#architecture-limitations" className="tone tone-warning inline-flex items-center gap-1 rounded px-2 py-1 text-body-md">
              <Icon name="warning" size={14} />
              {unresolved} unresolved or ambiguous edges
            </a>
          </li>
        )}
      </ul>
      <div className="flex flex-wrap items-center gap-2 text-body-sm text-fg-2">
        {report.analyzers.map((analyzer) => (
          <span key={analyzer.id}>
            {analyzer.id} {analyzer.version}
            {analyzer.capabilityLevel && <span className="ml-1">({analyzer.capabilityLevel.toLowerCase()})</span>}
          </span>
        ))}
      </div>
      <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
        {items.map(([label, value]) => (
          <div key={label} className="rounded-lg border border-line bg-surface p-2">
            <dt className="font-mono text-label-sm uppercase tracking-wider text-muted">{label}</dt>
            <dd className="text-headline-md font-semibold">{value ?? '—'}</dd>
          </div>
        ))}
      </dl>
    </Section>
  )
}

function CycleWarnings({ cycles }: { cycles: ArchitectureCycle[] }) {
  return (
    <Section title="Dependency cycles" count={cycles.length}>
      <ul className="space-y-2">
        {cycles.map((cycle) => (
          <li key={cycle.rule + cycle.members.join(',')} role="alert" className="tone tone-warning space-y-1 rounded-lg p-3 text-body-md">
            <div className="flex flex-wrap items-center gap-2 text-fg">
              <Badge tone="warning" icon="warning">WARN</Badge>
              <SeverityBadge severity={cycle.severity} />
              <ConfidenceBadge confidence={cycle.confidence} />
              <span className="font-medium">{cycle.title}</span>
            </div>
            {cycle.description && <p className="text-fg">{cycle.description}</p>}
            <ul className="font-mono text-code-md text-fg">
              {cycle.edges.map((edge, index) => (
                <li key={index}>
                  {shortName(String(edge.from))} → {shortName(String(edge.to))} ({String(edge.relationship).toLowerCase()},{' '}
                  {String(edge.file)}:{String(edge.line)})
                </li>
              ))}
            </ul>
          </li>
        ))}
      </ul>
    </Section>
  )
}

function TypeRow({ cls, injections, repositoryId, snapshotId }: { cls: LayerClass; injections: Injection[]; repositoryId: string; snapshotId: string }) {
  const [open, setOpen] = useState(false)
  const deps = injections.filter((i) => i.from === cls.fullyQualifiedName)
  return (
    <li className="rounded border border-line bg-canvas">
      <div className="flex flex-wrap items-center gap-2 px-3 py-2">
        <SourceLink url={cls.sourceUrl}>
          <span className="font-mono text-body-sm font-medium" title={cls.fullyQualifiedName}>
            {cls.name}
          </span>
        </SourceLink>
        <ConfidenceBadge confidence={cls.confidence} />
        <Badge tone="neutral">{METHOD_LABELS[cls.method]}</Badge>
        <span className="text-body-sm text-muted">{cls.evidence[0]?.signal && `${METHOD_LABELS[cls.method]}: ${cls.evidence[0].signal}`}</span>
        <button
          type="button"
          aria-expanded={open}
          aria-label={`${open ? 'Collapse' : 'Expand'} ${cls.name}`}
          onClick={() => setOpen((v) => !v)}
          className="ml-auto inline-flex size-7 items-center justify-center rounded text-fg-2 hover:bg-raised"
        >
          <Icon name={open ? 'chevron-up' : 'chevron-down'} size={14} />
        </button>
      </div>
      {open && (
        <div className="space-y-3 border-t border-line px-3 py-2">
          <div>
            <h5 className="font-mono text-label-sm uppercase tracking-wider text-muted">Injected dependencies</h5>
            {deps.length === 0 ? (
              <p className="text-body-sm text-muted">No injected dependencies were found for this type.</p>
            ) : (
              <ul className="mt-1 flex flex-wrap gap-1.5">
                {deps.map((d) => (
                  <li key={`${d.to}-${d.member}`} className={`rounded px-1.5 py-0.5 font-mono text-body-sm ${d.resolution === 'RESOLVED' ? 'bg-raised' : 'tone tone-neutral border-dashed'}`} title={d.to ?? undefined}>
                    {d.resolution === 'RESOLVED' ? shortName(d.to) : d.to}
                  </li>
                ))}
              </ul>
            )}
          </div>
          {cls.symbolId ? (
            <SymbolInsights repositoryId={repositoryId} snapshotId={snapshotId} symbolId={cls.symbolId} name={cls.name} />
          ) : (
            <p className="text-body-sm text-muted">AI actions need a parsed symbol for this type, which was not recorded.</p>
          )}
        </div>
      )}
    </li>
  )
}

function LayerOverview({ layers, injections, repositoryId, snapshotId }: { layers: ArchitectureLayerGroup[]; injections: Injection[]; repositoryId: string; snapshotId: string }) {
  return (
    <Section title="Layers" icon="class">
      {layers.length === 0 && <p className="text-body-md text-fg-2">No class could be assigned to a layer.</p>}
      {layers.length > 0 && <AIAvailabilityNote />}
      <div className="space-y-2">
        {layers.map((group) => (
          <div key={group.layer} aria-label={`Layer ${group.layer}`}>
            <Accordion
              defaultOpen
              title={
                <span className="font-semibold">
                  {LAYER_LABELS[group.layer]} <span className="font-normal text-muted">({group.total})</span>
                </span>
              }
              right={<span className="hidden font-mono text-body-sm text-muted md:inline">{group.classes[0]?.evidence[0]?.signal ?? ''}</span>}
            >
              <ul className="space-y-1.5">
                {group.classes.map((cls) => (
                  <TypeRow key={cls.fullyQualifiedName + cls.filePath} cls={cls} injections={injections} repositoryId={repositoryId} snapshotId={snapshotId} />
                ))}
              </ul>
              {group.total > group.classes.length && (
                <p className="mt-1 text-body-sm text-muted">
                  {group.classes.length} of {group.total} shown.
                </p>
              )}
            </Accordion>
          </div>
        ))}
      </div>
    </Section>
  )
}

function EndpointTable({ endpoints }: { endpoints: ArchitectureEndpoint[] }) {
  const [method, setMethod] = useState('')
  const [text, setText] = useState('')
  const methods = useMemo(() => [...new Set(endpoints.map((e) => e.httpMethod))].sort(), [endpoints])
  const rows = endpoints.filter((e) => (!method || e.httpMethod === method) && (!text || `${e.route} ${e.handler ?? ''}`.toLowerCase().includes(text.toLowerCase())))
  return (
    <Section title="REST endpoints" count={endpoints.length} icon="route">
      {endpoints.length === 0 ? (
        <p className="text-body-md text-fg-2">No Spring MVC request mappings were found.</p>
      ) : (
        <>
          <div className="flex flex-wrap items-center gap-2">
            <Select label="HTTP method" value={method} onChange={(e) => setMethod(e.target.value)}>
              <option value="">All methods</option>
              {methods.map((m) => (
                <option key={m} value={m}>
                  {m}
                </option>
              ))}
            </Select>
            <Input icon="search" aria-label="Search endpoints" placeholder="Search route or handler" value={text} onChange={(e) => setText(e.target.value)} className="w-60" />
            <span className="text-body-sm text-muted">{endpoints.length} mapped routes</span>
          </div>
          <DataTable
            caption="REST endpoints"
            rows={rows}
            rowKey={(e) => e.httpMethod + e.route + e.handler}
            columns={[
              { key: 'm', header: 'Method', cell: (e) => <HttpMethodBadge method={e.httpMethod} /> },
              {
                key: 'r',
                header: 'Route',
                cell: (e) => (
                  <span className="font-mono text-body-sm">
                    {e.route}
                    {!e.routeResolved && (
                      <span className="ml-2">
                        <Badge tone="warning">path not resolved</Badge>
                      </span>
                    )}
                  </span>
                ),
              },
              { key: 'h', header: 'Handler', cell: (e) => <span className="font-mono text-body-sm"><SourceLink url={e.sourceUrl}>{e.handler ?? '—'}</SourceLink></span> },
              { key: 'rq', header: 'Request', cell: (e) => <span className="font-mono text-body-sm">{e.requestType ? shortName(e.requestType) : '—'}</span> },
              { key: 'rs', header: 'Response', cell: (e) => <span className="font-mono text-body-sm">{e.responseType ? shortName(e.responseType) : '—'}</span> },
            ]}
          />
        </>
      )}
    </Section>
  )
}

function EntityView({ entities }: { entities: ArchitectureEntity[] }) {
  return (
    <Section title="JPA entities" count={entities.length} icon="entity">
      {entities.length === 0 ? (
        <p className="text-body-md text-fg-2">No JPA entities were found.</p>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {entities.map((entity) => (
            <Card key={entity.fullyQualifiedName} as="article" className="text-body-md">
              <h4 className="font-semibold">
                <SourceLink url={entity.sourceUrl}>{entity.name}</SourceLink>
                {entity.table && <span className="ml-2 font-mono text-body-sm font-normal text-muted">table {entity.table}</span>}
              </h4>
              {entity.relationships.length > 0 && (
                <ul className="mt-1 space-y-0.5 font-mono text-code-md">
                  {entity.relationships.map((relation) => (
                    <li key={(relation.field ?? '') + relation.type}>
                      {relation.field} {relation.type.toLowerCase().replace(/_/g, '-')} {shortName(relation.target)}
                      {!relation.targetResolved && <span className="text-muted"> (not in this repository)</span>}
                      {Object.keys(relation.attributes).length > 0 && (
                        <span className="text-muted">
                          {' '}
                          {Object.entries(relation.attributes)
                            .map(([key, value]) => `${key}=${value}`)
                            .join(', ')}
                        </span>
                      )}
                    </li>
                  ))}
                </ul>
              )}
              {entity.repositories.length > 0 && <p className="mt-1 text-body-sm text-fg-2">Repository: {entity.repositories.map(shortName).join(', ')}</p>}
            </Card>
          ))}
        </div>
      )}
    </Section>
  )
}

function DiChains({ chains }: { chains: ChainStep[][] }) {
  return (
    <Section title="Dependency injection chains" count={chains.length} icon="link">
      {chains.length === 0 ? (
        <p className="text-body-md text-fg-2">No controller → service → repository chain was found.</p>
      ) : (
        <ol className="space-y-1.5">
          {chains.map((chain) => (
            <li key={chain.map((step) => step.fullyQualifiedName).join('>')} className="flex flex-wrap items-center gap-1 font-mono text-code-md">
              {chain.map((step, index) => (
                <span key={step.fullyQualifiedName} className="inline-flex items-center gap-1">
                  {index > 0 && <span aria-hidden="true" className="text-muted"> → </span>}
                  <span className="rounded bg-raised px-1.5 py-0.5" title={step.fullyQualifiedName}>{step.name}</span>
                </span>
              ))}
            </li>
          ))}
        </ol>
      )}
    </Section>
  )
}

function InjectionTable({ injections }: { injections: Injection[] }) {
  if (injections.length === 0) {
    return null
  }
  return (
    <Section title="Injected dependencies" count={injections.length}>
      <DataTable
        caption="Injected dependencies"
        rows={injections}
        rowKey={(i) => `${i.from}>${i.to}>${i.member}`}
        columns={[
          { key: 'f', header: 'Bean', cell: (i) => <span className="font-mono text-body-sm">{shortName(i.from)}</span> },
          { key: 't', header: 'Depends on', cell: (i) => <span className="font-mono text-body-sm" title={i.to ?? undefined}>{i.resolution === 'RESOLVED' ? shortName(i.to) : i.to}</span> },
          {
            key: 'v',
            header: 'How',
            cell: (i) => (
              <span className="text-body-sm">
                <SourceLink url={i.sourceUrl}>
                  {i.via} {i.member}
                </SourceLink>
                {i.confidence && (
                  <span className="ml-1">
                    <ConfidenceBadge confidence={i.confidence} />
                  </span>
                )}
              </span>
            ),
          },
          { key: 'r', header: 'Resolution', cell: (i) => <Badge tone={RESOLUTION_TONES[i.resolution]}>{i.resolution.toLowerCase()}</Badge> },
        ]}
      />
    </Section>
  )
}

function PatternList({ patterns }: { patterns: PatternCandidate[] }) {
  return (
    <Section title="Design pattern candidates" count={patterns.length}>
      <p className="text-body-sm text-muted">
        Candidates inferred from structure (implementations, injection, object creation, delegation), never from class names. Possible and likely candidates need a human look.
      </p>
      {patterns.length === 0 ? (
        <p className="text-body-md text-fg-2">No pattern candidate was found.</p>
      ) : (
        <ul className="space-y-1.5">
          {patterns.map((pattern) => (
            <li key={pattern.pattern + pattern.symbols.join(',')}>
              <Accordion
                title={
                  <span className="flex flex-wrap items-center gap-2">
                    <Badge tone="info">{pattern.pattern.toLowerCase()}</Badge>
                    <span className="text-body-md">{pattern.summary ?? pattern.symbols.map(shortName).join(', ')}</span>
                  </span>
                }
                right={<ConfidenceBadge confidence={pattern.confidence} />}
              >
                <p className="font-mono text-body-sm text-fg-2">{pattern.symbols.join(', ')}</p>
              </Accordion>
            </li>
          ))}
        </ul>
      )}
    </Section>
  )
}

function Limitations({ limitations }: { limitations: string[] }) {
  if (limitations.length === 0) {
    return null
  }
  return (
    <div id="architecture-limitations">
      <Section title="Limitations of this architecture analysis">
        <ul className="list-disc space-y-1 pl-5 text-body-md text-fg-2">
          {limitations.map((limitation) => (
            <li key={limitation}>{limitation}</li>
          ))}
        </ul>
      </Section>
    </div>
  )
}
