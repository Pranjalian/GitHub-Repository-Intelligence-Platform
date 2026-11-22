import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import IndexStatusPanel from '../components/rag/IndexStatusPanel'
import AnswerView from '../components/rag/AnswerView'
import SnapshotPageFrame from '../components/rag/SnapshotPageFrame'
import { useAIProviders, useAskQuestion, useIndexStatus } from '../hooks/queries'
import { readPreferredProvider } from '../lib/aiPreference'
import { isIndexSearchable, type QuestionAnswer } from '../types/api'

/** `/repositories/:repositoryId/qa?snapshot=`: questions about one commit, answered from its index. */
export default function QAPage() {
  return (
    <SnapshotPageFrame title="Ask about">
      {({ repositoryId, snapshotId }) => <QAView key={snapshotId} repositoryId={repositoryId} snapshotId={snapshotId} />}
    </SnapshotPageFrame>
  )
}

function QAView({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const providers = useAIProviders()
  const index = useIndexStatus(repositoryId, snapshotId)
  const ask = useAskQuestion(repositoryId, snapshotId)
  const [question, setQuestion] = useState('')
  const [turns, setTurns] = useState<QuestionAnswer[]>([])
  const conversationId = turns.length > 0 ? turns[turns.length - 1].conversationId : null
  const searchLink = `/repositories/${repositoryId}/search?snapshot=${snapshotId}`
  const searchable = isIndexSearchable(index.data?.status)

  function submit(event: FormEvent) {
    event.preventDefault()
    const text = question.trim()
    if (!text || ask.isPending) {
      return
    }
    ask.mutate(
      { question: text, conversationId, provider: readPreferredProvider() },
      {
        onSuccess: (answer) => {
          setTurns((previous) => [...previous, answer])
          setQuestion('')
        },
      },
    )
  }

  return (
    <div className="space-y-4">
      {providers.data && !providers.data.available && (
        <p role="note" className="rounded bg-slate-100 p-2 text-sm text-slate-700">
          AI unavailable: {providers.data.unavailableReason ?? 'no provider is configured.'} Questions still return
          deterministic search results, and{' '}
          <Link to={searchLink} className="text-blue-700 hover:underline">
            Search
          </Link>{' '}
          works without any model.{' '}
          <Link to="/settings/ai" className="text-blue-700 hover:underline">
            AI settings
          </Link>
        </p>
      )}
      {index.data?.embeddingStatus === 'NOT_CONFIGURED' && (
        <p role="note" className="rounded bg-slate-100 p-2 text-sm text-slate-700">
          No embedding provider configured: answers use keyword and symbol retrieval only (no semantic search).{' '}
          <Link to={searchLink} className="text-blue-700 hover:underline">
            Search
          </Link>{' '}
          finds exact identifiers and words.
        </p>
      )}
      <IndexStatusPanel repositoryId={repositoryId} snapshotId={snapshotId} />

      {turns.length > 0 && (
        <ol aria-label="Conversation" className="space-y-4">
          {turns.map((turn) => (
            <li key={turn.messageId ?? turn.question} className="space-y-2">
              <p className="ml-auto max-w-prose rounded bg-slate-900 px-3 py-2 text-sm text-white">{turn.question}</p>
              <div className="rounded border border-slate-200 bg-white p-3">
                <AnswerView answer={turn} />
              </div>
            </li>
          ))}
        </ol>
      )}

      <form onSubmit={submit} className="space-y-2 rounded border border-slate-200 bg-white p-3">
        <label htmlFor="question" className="text-sm font-medium">
          {turns.length > 0 ? 'Follow-up question' : 'Question about this commit'}
        </label>
        <textarea
          id="question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          maxLength={2000}
          rows={3}
          placeholder="For example: Where is the owner form validated?"
          className="w-full rounded border border-slate-300 px-3 py-2 text-sm"
        />
        <div className="flex flex-wrap items-center gap-2">
          <button
            type="submit"
            disabled={ask.isPending || !searchable || question.trim() === ''}
            className="rounded bg-slate-900 px-3 py-1.5 text-sm font-medium text-white hover:bg-slate-700 disabled:cursor-not-allowed disabled:opacity-50"
          >
            {ask.isPending ? 'Asking…' : 'Ask'}
          </button>
          {turns.length > 0 && (
            <button type="button" onClick={() => setTurns([])} className="text-sm text-slate-600 hover:underline">
              New conversation
            </button>
          )}
          {!searchable && index.data && <span className="text-xs text-slate-600">Index this snapshot to ask questions.</span>}
        </div>
        {ask.isPending && (
          <p role="status" className="text-xs text-slate-600">
            Searching the index and asking the model; this can take up to a minute…
          </p>
        )}
        {ask.error && (
          <p role="alert" className="text-xs text-red-700">
            {ask.error.message}
          </p>
        )}
      </form>
    </div>
  )
}
