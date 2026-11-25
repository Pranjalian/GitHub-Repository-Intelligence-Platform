import type { AnalysisMethod, Finding } from '../../types/api'
import InsightPanel from '../ai/InsightPanel'
import CodeBlock from '../ui/CodeBlock'
import { IconButton } from '../ui/Button'
import ConfidenceBadge from '../ui/ConfidenceBadge'
import CopyButton from '../ui/CopyButton'
import Icon from '../ui/icons'
import SeverityBadge from '../ui/SeverityBadge'
import { shortSha } from '../../lib/format'

const methodLabels: Record<AnalysisMethod, string> = {
  DETERMINISTIC_SCANNER: 'Deterministic scanner',
  PARSER: 'Parser',
  HEURISTIC: 'Heuristic',
  GRAPH_INFERENCE: 'Graph inference',
  AI_INTERPRETATION: 'AI interpretation',
}

/** The short id shown in the drawer header: findings have a fingerprint, not a sequence number. */
export const shortFingerprint = (finding: Finding) => finding.fingerprint.slice(0, 8)

/**
 * Everything the platform knows about one finding, deterministic part first, then (separately, in the AI
 * surface) an optional AI explanation. Evidence is untrusted repository text and is only ever rendered as text.
 */
export default function FindingDetailPanel({
  finding,
  repositoryId,
  snapshotId,
  commitSha,
  position,
  onPrev,
  onNext,
}: {
  finding: Finding
  repositoryId?: string
  snapshotId?: string
  commitSha?: string
  position?: { index: number; total: number }
  onPrev?: () => void
  onNext?: () => void
}) {
  const excerpt = finding.evidence?.excerpt
  const range =
    finding.startLine == null
      ? null
      : finding.endLine != null && finding.endLine !== finding.startLine
        ? `Lines ${finding.startLine}-${finding.endLine}`
        : `Line ${finding.startLine}`
  const location = `${finding.filePath ?? 'No file'}${finding.startLine != null ? `:${finding.startLine}` : ''}`

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="font-mono text-label-md text-fg-2">
          #{shortFingerprint(finding)} · {finding.analyzerId}
        </p>
        {position && (
          <div className="flex items-center gap-1" role="group" aria-label="Finding navigation">
            <IconButton icon="chevron-up" label="Previous finding" disabled={position.index <= 0} onClick={onPrev} />
            <span className="font-mono text-body-sm text-muted">
              {position.index + 1}/{position.total}
            </span>
            <IconButton icon="chevron-down" label="Next finding" disabled={position.index + 1 >= position.total} onClick={onNext} />
          </div>
        )}
      </div>
      <div className="flex flex-wrap items-center gap-2">
        <SeverityBadge severity={finding.severity} />
        <ConfidenceBadge confidence={finding.confidence} />
      </div>
      <h3 className="text-headline-md font-semibold">{finding.title}</h3>
      {finding.description && <p className="text-body-lg text-fg-2">{finding.description}</p>}

      <section aria-label="File and anchor" className="space-y-1">
        <h4 className="font-mono text-label-sm uppercase tracking-wider text-muted">File & anchor</h4>
        <p className="flex items-center gap-2 break-all font-mono text-code-md">
          <Icon name="file" size={14} />
          {location}
          <CopyButton value={location} label="Copy location" compact labelOnly />
        </p>
        {finding.sourceUrl && (
          <a href={finding.sourceUrl} target="_blank" rel="noreferrer" className="inline-flex items-center gap-1 text-body-md text-primary-text hover:underline">
            View on GitHub @ {shortSha(commitSha ?? finding.sourceUrl.match(/\/blob\/([0-9a-f]{7,40})/)?.[1])}
            <Icon name="external-link" size={12} />
          </a>
        )}
      </section>

      <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-1 text-body-md">
        <dt className="text-muted">Rule id</dt>
        <dd className="font-mono text-body-sm">{finding.ruleId}</dd>
        <dt className="text-muted">Module</dt>
        <dd className="font-mono text-body-sm">{finding.module ?? '—'}</dd>
        <dt className="text-muted">Analyzer</dt>
        <dd className="font-mono text-body-sm">{finding.analyzerId}</dd>
        <dt className="text-muted">Method</dt>
        <dd>{methodLabels[finding.analysisMethod] ?? finding.analysisMethod}</dd>
      </dl>

      {typeof excerpt === 'string' && (
        <section aria-label="Evidence excerpt" className="space-y-1">
          <h4 className="font-mono text-label-sm uppercase tracking-wider text-muted">Evidence excerpt{range ? ` · ${range}` : ''}</h4>
          <CodeBlock code={excerpt} startLine={finding.startLine ?? 1} highlight={finding.startLine != null ? [finding.startLine] : []} />
        </section>
      )}

      {finding.suggestedNextStep && (
        <section aria-label="Suggested next step" className="rounded-lg border border-line bg-canvas p-3">
          <h4 className="font-mono text-label-sm uppercase tracking-wider text-muted">Suggested next step</h4>
          <p className="mt-1 text-body-md">{finding.suggestedNextStep}</p>
        </section>
      )}

      {repositoryId && snapshotId && (
        <section aria-label="AI explanation" className="space-y-2 border-t border-line pt-3">
          <h4 className="font-mono text-label-sm uppercase tracking-wider text-ai-text">AI explanation</h4>
          <InsightPanel repositoryId={repositoryId} snapshotId={snapshotId} type="EXPLAIN_FINDING" targetId={finding.id} actionLabel="Explain this finding" />
        </section>
      )}
    </div>
  )
}
