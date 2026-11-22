import { useIndexStatus, useRequestIndex } from '../../hooks/queries'
import { formatDateTime } from '../../lib/format'
import { isIndexInFlight, type EmbeddingState, type IndexState } from '../../types/api'
import Badge, { type BadgeTone } from '../ui/Badge'

const STATE: Record<IndexState, { label: string; tone: BadgeTone }> = {
  NOT_INDEXED: { label: 'Not indexed', tone: 'neutral' },
  QUEUED: { label: 'Queued', tone: 'info' },
  INDEXING: { label: 'Indexing…', tone: 'info' },
  COMPLETED: { label: 'Indexed', tone: 'success' },
  COMPLETED_WITH_WARNINGS: { label: 'Indexed with warnings', tone: 'warning' },
  FAILED: { label: 'Indexing failed', tone: 'danger' },
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
 * The snapshot's search index: state, numbers, whether semantic search is available and why not, and the
 * "Index this snapshot" action. Keyword and symbol search never need a model.
 */
export default function IndexStatusPanel({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const index = useIndexStatus(repositoryId, snapshotId)
  const request = useRequestIndex(repositoryId, snapshotId)

  if (index.isPending) {
    return <p role="status" className="text-sm text-slate-600">Loading the index state…</p>
  }
  if (index.error) {
    return (
      <p role="alert" className="text-sm text-red-700">
        Could not load the index state: {index.error.message}
      </p>
    )
  }
  const data = index.data
  const state = STATE[data.status]
  const busy = isIndexInFlight(data.status) || request.isPending

  return (
    <section aria-label="Search index" className="space-y-2 rounded border border-slate-200 bg-white p-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="flex flex-wrap items-center gap-2">
          <h3 className="text-sm font-semibold">Search index</h3>
          <Badge tone={state.tone}>{state.label}</Badge>
        </div>
        <button
          type="button"
          onClick={() => request.mutate()}
          disabled={busy}
          className="rounded bg-slate-900 px-3 py-1 text-xs font-medium text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
        >
          {data.status === 'NOT_INDEXED' ? 'Index this snapshot' : busy ? 'Indexing…' : 'Index again'}
        </button>
      </div>
      {data.status === 'NOT_INDEXED' && (
        <p className="text-xs text-slate-600">
          Indexing splits the files of this commit into searchable chunks (secrets withheld). It needs no AI; embeddings
          for semantic search are added when an embedding model is configured.
        </p>
      )}
      {data.status !== 'NOT_INDEXED' && (
        <dl className="grid grid-cols-[max-content_1fr] gap-x-4 gap-y-0.5 text-xs">
          {data.detail && (
            <>
              <dt className="text-slate-500">Result</dt>
              <dd>{data.detail}</dd>
            </>
          )}
          {data.chunkCount != null && (
            <>
              <dt className="text-slate-500">Chunks</dt>
              <dd>
                {data.chunkCount} from {data.filesIndexed ?? 0} files ({data.filesSkipped ?? 0} skipped)
              </dd>
            </>
          )}
          {data.embeddingStatus && (
            <>
              <dt className="text-slate-500">Embeddings</dt>
              <dd>
                {EMBEDDINGS[data.embeddingStatus]}
                {data.embeddingModel && data.embeddingStatus !== 'NOT_CONFIGURED' && (
                  <span className="text-slate-500">
                    {' '}
                    ({data.embeddingProvider}/{data.embeddingModel}
                    {data.embeddingModelVersion ? ` ${data.embeddingModelVersion}` : ''}
                    {data.embeddedChunks != null ? `, ${data.embeddedChunks} embedded` : ''}
                    {data.reusedEmbeddings != null ? `, ${data.reusedEmbeddings} reused` : ''})
                  </span>
                )}
                {data.embeddingDetail && <span className="block text-slate-500">{data.embeddingDetail}</span>}
              </dd>
            </>
          )}
          {data.completedAt && (
            <>
              <dt className="text-slate-500">Indexed</dt>
              <dd>{formatDateTime(data.completedAt)}</dd>
            </>
          )}
        </dl>
      )}
      {data.warnings.length > 0 && (
        <ul className="list-disc pl-5 text-xs text-amber-800">
          {data.warnings.map((warning) => (
            <li key={warning}>{warning}</li>
          ))}
        </ul>
      )}
      {request.error && (
        <p role="alert" className="text-xs text-red-700">
          {request.error.message}
        </p>
      )}
    </section>
  )
}
