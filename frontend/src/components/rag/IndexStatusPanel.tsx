import { useIndexStatus, useRequestIndex } from '../../hooks/queries'
import { formatDateTime } from '../../lib/format'
import { isIndexInFlight, type EmbeddingState, type IndexState } from '../../types/api'
import Badge, { type BadgeTone } from '../ui/Badge'
import Button from '../ui/Button'
import Icon from '../ui/icons'
import { SkeletonRows } from '../ui/Layout'

const STATE: Record<IndexState, { label: string; tone: BadgeTone; icon: string }> = {
  NOT_INDEXED: { label: 'Not indexed', tone: 'neutral', icon: 'search-index' },
  QUEUED: { label: 'Queued', tone: 'running', icon: 'hourglass' },
  INDEXING: { label: 'Indexing…', tone: 'running', icon: 'spinner' },
  COMPLETED: { label: 'Indexed', tone: 'success', icon: 'check-circle' },
  COMPLETED_WITH_WARNINGS: { label: 'Indexed with warnings', tone: 'warning', icon: 'warning' },
  FAILED: { label: 'Indexing failed', tone: 'failed', icon: 'error' },
}

const EMBEDDINGS: Record<EmbeddingState, string> = {
  PENDING: 'Semantic search: waiting for the index job.',
  NOT_CONFIGURED: 'Semantic search: no embedding provider configured.',
  UNAVAILABLE: 'Semantic search: the embedding model is unavailable.',
  COMPLETED: 'Semantic search: available.',
  PARTIAL: 'Semantic search: some chunks have embeddings.',
  FAILED: 'Semantic search: embedding failed.',
}

/**
 * One live index bar for Search and Ask: Not indexed (with the note that indexing is optional and separate
 * from analysis), Indexing (polled), Indexed (counts, embedding model, time, Re-index), with warnings,
 * Failed (reason, retry) and "Embeddings not configured" (keyword and symbol search still work).
 */
export default function IndexStatusPanel({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const index = useIndexStatus(repositoryId, snapshotId)
  const request = useRequestIndex(repositoryId, snapshotId)

  if (index.isPending) {
    return <SkeletonRows rows={1} label="Loading the index state" />
  }
  if (index.error) {
    return (
      <p role="alert" className="text-body-md text-[color:var(--t-failed-fg)]">
        Could not load the index state: {index.error.message}
      </p>
    )
  }
  const data = index.data
  const state = STATE[data.status]
  const inFlight = isIndexInFlight(data.status)
  const busy = inFlight || request.isPending

  return (
    <section aria-label="Search index" className="space-y-2 rounded-lg border border-line bg-surface p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <Icon name="search-index" size={16} className="text-fg-2" />
          <h3 className="text-body-md font-semibold">Search index</h3>
          <Badge tone={state.tone} icon={state.icon}>{state.label}</Badge>
          {data.chunkCount != null && data.status !== 'NOT_INDEXED' && <span className="font-mono text-body-sm text-fg-2">{data.chunkCount.toLocaleString()} chunks</span>}
        </div>
        <Button variant={data.status === 'NOT_INDEXED' || data.status === 'FAILED' ? 'primary' : 'secondary'} icon={data.status === 'NOT_INDEXED' ? 'search-index' : 'refresh'} busy={busy} onClick={() => request.mutate()}>
          {data.status === 'NOT_INDEXED' ? 'Index this snapshot' : data.status === 'FAILED' ? 'Retry indexing' : busy ? 'Indexing…' : 'Re-index'}
        </Button>
      </div>
      {inFlight && (
        <div role="progressbar" aria-label="Indexing" className="h-1.5 overflow-hidden rounded bg-overlay">
          <div className="pulse h-full w-1/2 rounded bg-primary" />
        </div>
      )}
      {data.status === 'NOT_INDEXED' && (
        <p className="text-body-sm text-muted">
          Indexing is optional and separate from analysis. It splits the files of this commit into searchable chunks (secrets withheld). It needs no AI; embeddings for semantic search are added when an embedding model is configured.
        </p>
      )}
      {data.status === 'FAILED' && data.detail && <p className="text-body-md text-[color:var(--t-failed-fg)]">{data.detail}</p>}
      {data.status !== 'NOT_INDEXED' && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-0.5 text-body-sm">
          {data.detail && data.status !== 'FAILED' && (
            <>
              <dt className="text-muted">Result</dt>
              <dd>{data.detail}</dd>
            </>
          )}
          {data.chunkCount != null && (
            <>
              <dt className="text-muted">Chunks</dt>
              <dd>
                {data.chunkCount} from {data.filesIndexed ?? 0} files ({data.filesSkipped ?? 0} skipped)
              </dd>
            </>
          )}
          {data.embeddingStatus && (
            <>
              <dt className="text-muted">Embeddings</dt>
              <dd>
                {EMBEDDINGS[data.embeddingStatus]}
                {data.embeddingModel && data.embeddingStatus !== 'NOT_CONFIGURED' && (
                  <span className="text-muted">
                    {' '}
                    ({data.embeddingProvider}/{data.embeddingModel}
                    {data.embeddingModelVersion ? ` ${data.embeddingModelVersion}` : ''}
                    {data.embeddedChunks != null ? `, ${data.embeddedChunks} embedded` : ''}
                    {data.reusedEmbeddings != null ? `, ${data.reusedEmbeddings} reused` : ''})
                  </span>
                )}
                {data.embeddingDetail && <span className="block text-muted">{data.embeddingDetail}</span>}
              </dd>
            </>
          )}
          {data.completedAt && (
            <>
              <dt className="text-muted">Indexed</dt>
              <dd>{formatDateTime(data.completedAt)}</dd>
            </>
          )}
        </dl>
      )}
      {data.warnings.length > 0 && (
        <ul className="list-disc pl-5 text-body-sm text-[color:var(--t-warning-fg)]">
          {data.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      {request.error && (
        <p role="alert" className="text-body-sm text-[color:var(--t-failed-fg)]">
          {request.error.message}
        </p>
      )}
    </section>
  )
}
