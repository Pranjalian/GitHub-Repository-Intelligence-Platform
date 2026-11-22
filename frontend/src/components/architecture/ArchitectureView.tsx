import type { ReactNode } from 'react'
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
  LayerMethod,
  PatternCandidate,
} from '../../types/api'
import AIAvailabilityNote from '../ai/AIAvailabilityNote'
import SymbolInsights from '../ai/SymbolInsights'
import Badge from '../ui/Badge'
import ConfidenceBadge from '../ui/ConfidenceBadge'
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
    <a href={url} target="_blank" rel="noreferrer" className="text-blue-700 hover:underline">
      {children}
    </a>
  )
}

function Section({ title, children, count }: { title: string; children: ReactNode; count?: number }) {
  return (
    <section aria-label={title} className="space-y-2">
      <h3 className="text-base font-semibold">
        {title}
        {count != null && <span className="ml-2 text-sm font-normal text-slate-500">({count})</span>}
      </h3>
      {children}
    </section>
  )
}

/** The architecture of one snapshot. For unsupported technologies it says so instead of showing empty sections. */
export default function ArchitectureView({ report }: { report: ArchitectureReport }) {
  if (!report.supported) {
    return <UnsupportedArchitecture report={report} />
  }
  const { diagrams } = report
  return (
    <div className="space-y-8">
      <Overview report={report} />
      {report.cycles.length > 0 && <CycleWarnings cycles={report.cycles} />}
      <Section title="Diagrams">
        <div className="grid gap-4 xl:grid-cols-2">
          {diagrams.layers && <MermaidDiagram title="Layers" source={diagrams.layers} />}
          {diagrams.apiFlow && <MermaidDiagram title="API flow" source={diagrams.apiFlow} />}
          {diagrams.entities && <MermaidDiagram title="Entity relationships" source={diagrams.entities} />}
          {diagrams.modules && <MermaidDiagram title="Modules" source={diagrams.modules} />}
        </div>
        {!diagrams.layers && !diagrams.apiFlow && !diagrams.entities && !diagrams.modules && (
          <p className="text-sm text-slate-600">Nothing to draw: no layers, endpoints, entities or module dependencies were found.</p>
        )}
        {diagrams.notes.length > 0 && (
          <ul aria-label="Diagram notes" className="list-disc pl-5 text-xs text-slate-600">
            {diagrams.notes.map((note) => (
              <li key={note}>{note}</li>
            ))}
          </ul>
        )}
      </Section>
      <LayerOverview layers={report.layers} repositoryId={report.repositoryId} snapshotId={report.snapshotId} />
      <EndpointTable endpoints={report.endpoints} />
      <EntityView entities={report.entities} />
      <DiChains chains={report.diChains} />
      <InjectionTable injections={report.injections} />
      {report.moduleDependencies.length > 0 && (
        <Section title="Module dependencies" count={report.moduleDependencies.length}>
          <ul className="space-y-1 text-sm">
            {report.moduleDependencies.map((dependency) => (
              <li key={dependency.source + '>' + dependency.target}>
                <span className="font-mono">{dependency.source}</span> → <span className="font-mono">{dependency.target}</span>
                {dependency.evidence[0] && (
                  <span className="ml-2 text-xs text-slate-500">
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

function UnsupportedArchitecture({ report }: { report: ArchitectureReport }) {
  return (
    <div className="space-y-4">
      <div className="rounded-lg border border-dashed border-slate-300 bg-white p-6">
        <h3 className="text-base font-semibold">Architecture analysis is not supported for this technology</h3>
        <p className="mt-2 text-sm text-slate-600">
          Architecture discovery currently exists for Java and Spring Boot. The structure, dependency, security and test
          sections of the report are not affected.
        </p>
        {report.unsupported.length > 0 && (
          <ul aria-label="Unsupported technologies" className="mt-3 list-disc pl-5 text-sm">
            {report.unsupported.map((item) => (
              <li key={item}>{item}</li>
            ))}
          </ul>
        )}
      </div>
    </div>
  )
}

function Overview({ report }: { report: ArchitectureReport }) {
  const summary = report.summary
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
      <div className="flex flex-wrap items-center gap-2 text-sm">
        <StatusLabel status={report.status} />
        {report.analyzers.map((analyzer) => (
          <span key={analyzer.id} className="text-slate-600">
            {analyzer.id} {analyzer.version}
            {analyzer.capabilityLevel && <span className="ml-1">({analyzer.capabilityLevel.toLowerCase()})</span>}
          </span>
        ))}
      </div>
      <dl className="grid grid-cols-2 gap-2 sm:grid-cols-4 lg:grid-cols-7">
        {items.map(([label, value]) => (
          <div key={label} className="rounded border border-slate-200 bg-white p-2">
            <dt className="text-xs text-slate-500">{label}</dt>
            <dd className="text-lg font-semibold">{value ?? '—'}</dd>
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
          <li key={cycle.rule + cycle.members.join(',')} role="alert" className="rounded border border-amber-300 bg-amber-50 p-3 text-sm">
            <div className="flex flex-wrap items-center gap-2">
              <SeverityBadge severity={cycle.severity} />
              <ConfidenceBadge confidence={cycle.confidence} />
              <span className="font-medium">{cycle.title}</span>
            </div>
            {cycle.description && <p className="mt-1 text-slate-700">{cycle.description}</p>}
            <ul className="mt-1 font-mono text-xs">
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

function LayerOverview({
  layers,
  repositoryId,
  snapshotId,
}: {
  layers: ArchitectureLayerGroup[]
  repositoryId: string
  snapshotId: string
}) {
  return (
    <Section title="Layers">
      {layers.length === 0 && <p className="text-sm text-slate-600">No class could be assigned to a layer.</p>}
      {layers.length > 0 && <AIAvailabilityNote />}
      <div className="grid gap-4 lg:grid-cols-2">
        {layers.map((group) => (
          <div key={group.layer} className="rounded border border-slate-200 bg-white p-3" aria-label={`Layer ${group.layer}`}>
            <h4 className="text-sm font-semibold">
              {LAYER_LABELS[group.layer]} <span className="font-normal text-slate-500">({group.total})</span>
            </h4>
            <ul className="mt-2 space-y-1 text-sm">
              {group.classes.map((cls) => (
                <li key={cls.fullyQualifiedName + cls.filePath} className="flex flex-wrap items-center gap-2">
                  <SourceLink url={cls.sourceUrl}>
                    <span className="font-mono text-xs" title={cls.fullyQualifiedName}>
                      {cls.name}
                    </span>
                  </SourceLink>
                  <ConfidenceBadge confidence={cls.confidence} />
                  <span className="text-xs text-slate-500">
                    {METHOD_LABELS[cls.method]}
                    {cls.evidence[0]?.signal && `: ${cls.evidence[0].signal}`}
                  </span>
                  {cls.symbolId && (
                    <SymbolInsights repositoryId={repositoryId} snapshotId={snapshotId} symbolId={cls.symbolId} name={cls.name} />
                  )}
                </li>
              ))}
            </ul>
            {group.total > group.classes.length && (
              <p className="mt-1 text-xs text-slate-500">
                {group.classes.length} of {group.total} shown.
              </p>
            )}
          </div>
        ))}
      </div>
    </Section>
  )
}

function EndpointTable({ endpoints }: { endpoints: ArchitectureEndpoint[] }) {
  return (
    <Section title="REST endpoints" count={endpoints.length}>
      {endpoints.length === 0 ? (
        <p className="text-sm text-slate-600">No Spring MVC request mappings were found.</p>
      ) : (
        <table className="w-full bg-white text-left text-sm">
          <thead className="border-b border-slate-200 text-xs text-slate-500">
            <tr>
              <th className="p-2">Method</th>
              <th className="p-2">Route</th>
              <th className="p-2">Handler</th>
              <th className="p-2">Request</th>
              <th className="p-2">Response</th>
            </tr>
          </thead>
          <tbody>
            {endpoints.map((endpoint) => (
              <tr key={endpoint.httpMethod + endpoint.route + endpoint.handler} className="border-b border-slate-100">
                <td className="p-2 font-mono text-xs">{endpoint.httpMethod}</td>
                <td className="p-2 font-mono text-xs">
                  {endpoint.route}
                  {!endpoint.routeResolved && (
                    <span className="ml-2">
                      <Badge tone="warning">path not resolved</Badge>
                    </span>
                  )}
                </td>
                <td className="p-2 font-mono text-xs">
                  <SourceLink url={endpoint.sourceUrl}>{endpoint.handler ?? '—'}</SourceLink>
                </td>
                <td className="p-2 font-mono text-xs">{endpoint.requestType ? shortName(endpoint.requestType) : '—'}</td>
                <td className="p-2 font-mono text-xs">{endpoint.responseType ? shortName(endpoint.responseType) : '—'}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </Section>
  )
}

function EntityView({ entities }: { entities: ArchitectureEntity[] }) {
  return (
    <Section title="JPA entities" count={entities.length}>
      {entities.length === 0 ? (
        <p className="text-sm text-slate-600">No JPA entities were found.</p>
      ) : (
        <div className="grid gap-3 lg:grid-cols-2">
          {entities.map((entity) => (
            <div key={entity.fullyQualifiedName} className="rounded border border-slate-200 bg-white p-3 text-sm">
              <h4 className="font-semibold">
                <SourceLink url={entity.sourceUrl}>{entity.name}</SourceLink>
                {entity.table && <span className="ml-2 font-mono text-xs font-normal text-slate-500">table {entity.table}</span>}
              </h4>
              {entity.relationships.length > 0 && (
                <ul className="mt-1 space-y-0.5 font-mono text-xs">
                  {entity.relationships.map((relation) => (
                    <li key={(relation.field ?? '') + relation.type}>
                      {relation.field} {relation.type.toLowerCase().replace(/_/g, '-')} {shortName(relation.target)}
                      {!relation.targetResolved && <span className="text-slate-500"> (not in this repository)</span>}
                      {Object.keys(relation.attributes).length > 0 && (
                        <span className="text-slate-500">
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
              {entity.repositories.length > 0 && (
                <p className="mt-1 text-xs text-slate-600">Repository: {entity.repositories.map(shortName).join(', ')}</p>
              )}
            </div>
          ))}
        </div>
      )}
    </Section>
  )
}

function DiChains({ chains }: { chains: ChainStep[][] }) {
  return (
    <Section title="Dependency injection chains" count={chains.length}>
      {chains.length === 0 ? (
        <p className="text-sm text-slate-600">No controller → service → repository chain was found.</p>
      ) : (
        <ol className="space-y-1 font-mono text-xs">
          {chains.map((chain) => (
            <li key={chain.map((step) => step.fullyQualifiedName).join('>')}>
              {chain.map((step, index) => (
                <span key={step.fullyQualifiedName}>
                  {index > 0 && ' → '}
                  <span title={step.fullyQualifiedName}>{step.name}</span>
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
      <table className="w-full bg-white text-left text-sm">
        <thead className="border-b border-slate-200 text-xs text-slate-500">
          <tr>
            <th className="p-2">Bean</th>
            <th className="p-2">Depends on</th>
            <th className="p-2">How</th>
            <th className="p-2">Resolution</th>
          </tr>
        </thead>
        <tbody>
          {injections.map((injection) => (
            <tr key={`${injection.from}>${injection.to}`} className="border-b border-slate-100">
              <td className="p-2 font-mono text-xs">{shortName(injection.from)}</td>
              <td className="p-2 font-mono text-xs" title={injection.to ?? undefined}>
                {injection.resolution === 'RESOLVED' ? shortName(injection.to) : injection.to}
              </td>
              <td className="p-2 text-xs">
                <SourceLink url={injection.sourceUrl}>
                  {injection.via} {injection.member}
                </SourceLink>
                {injection.confidence && (
                  <span className="ml-1">
                    <ConfidenceBadge confidence={injection.confidence} />
                  </span>
                )}
              </td>
              <td className="p-2">
                <Badge tone={RESOLUTION_TONES[injection.resolution]}>{injection.resolution.toLowerCase()}</Badge>
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </Section>
  )
}

function PatternList({ patterns }: { patterns: PatternCandidate[] }) {
  return (
    <Section title="Design pattern candidates" count={patterns.length}>
      <p className="text-xs text-slate-600">
        Candidates inferred from structure (implementations, injection, object creation, delegation), never from class
        names. Possible and likely candidates need a human look.
      </p>
      {patterns.length === 0 ? (
        <p className="text-sm text-slate-600">No pattern candidate was found.</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {patterns.map((pattern) => (
            <li key={pattern.pattern + pattern.symbols.join(',')} className="flex flex-wrap items-center gap-2">
              <Badge tone="info">{pattern.pattern.toLowerCase()}</Badge>
              <ConfidenceBadge confidence={pattern.confidence} />
              <span>{pattern.summary ?? pattern.symbols.map(shortName).join(', ')}</span>
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
    <Section title="Limitations of this architecture analysis">
      <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700">
        {limitations.map((limitation) => (
          <li key={limitation}>{limitation}</li>
        ))}
      </ul>
    </Section>
  )
}
