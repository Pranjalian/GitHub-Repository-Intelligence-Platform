import type { AnalysisMethod, Finding } from '../../types/api'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import SeverityBadge from '../ui/SeverityBadge'

const methodLabels: Record<AnalysisMethod, string> = {
  DETERMINISTIC_SCANNER: 'Deterministic scanner',
  PARSER: 'Parser',
  HEURISTIC: 'Heuristic',
  GRAPH_INFERENCE: 'Graph inference',
  AI_INTERPRETATION: 'AI interpretation',
}

/** Evidence is untrusted repository text: it is always rendered as plain text, never as HTML. */
export default function FindingDetailPanel({ finding, onClose }: { finding: Finding; onClose: () => void }) {
  const excerpt = finding.evidence?.excerpt
  const lines =
    finding.startLine == null
      ? null
      : finding.endLine != null && finding.endLine !== finding.startLine
        ? `lines ${finding.startLine}–${finding.endLine}`
        : `line ${finding.startLine}`

  return (
    <aside aria-label="Finding detail" className="rounded-lg border border-slate-300 bg-white p-4 shadow-sm">
      <div className="flex items-start justify-between gap-3">
        <div className="flex flex-wrap items-center gap-2">
          <SeverityBadge severity={finding.severity} />
          <ConfidenceBadge confidence={finding.confidence} />
          <h3 className="text-base font-semibold">{finding.title}</h3>
        </div>
        <button type="button" onClick={onClose} className="rounded border border-slate-300 px-2 py-0.5 text-sm">
          Close
        </button>
      </div>
      {finding.description && <p className="mt-2 text-sm">{finding.description}</p>}
      <dl className="mt-3 grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-sm">
        <dt className="text-slate-500">Location</dt>
        <dd className="font-mono text-xs">
          {finding.filePath ?? 'No file'}
          {lines ? ` (${lines})` : ''}
        </dd>
        <dt className="text-slate-500">Rule</dt>
        <dd className="font-mono text-xs">{finding.ruleId}</dd>
        <dt className="text-slate-500">Analyzer</dt>
        <dd className="font-mono text-xs">{finding.analyzerId}</dd>
        <dt className="text-slate-500">Method</dt>
        <dd>{methodLabels[finding.analysisMethod]}</dd>
      </dl>
      {typeof excerpt === 'string' && (
        <pre className="mt-3 overflow-x-auto rounded bg-slate-900 p-3 text-xs text-slate-100">
          <code>{excerpt}</code>
        </pre>
      )}
      {finding.sourceUrl && (
        <p className="mt-3 text-sm">
          <a href={finding.sourceUrl} target="_blank" rel="noreferrer" className="text-blue-700 hover:underline">
            View on GitHub at this commit
          </a>
        </p>
      )}
      {finding.suggestedNextStep && (
        <p className="mt-2 text-sm">
          <span className="font-medium">Suggested next step: </span>
          {finding.suggestedNextStep}
        </p>
      )}
    </aside>
  )
}
