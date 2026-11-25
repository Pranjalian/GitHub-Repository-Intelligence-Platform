import { useState, type FormEvent } from 'react'
import { Link } from 'react-router'
import AnswerView from '../components/rag/AnswerView'
import IndexStatusPanel from '../components/rag/IndexStatusPanel'
import SnapshotPageFrame from '../components/rag/SnapshotPageFrame'
import { Button, Icon } from '../components/ui'
import { Notice } from '../components/ui/Layout'
import { useAIProviders, useAskQuestion, useIndexStatus } from '../hooks/queries'
import { readPreferredProvider } from '../lib/aiPreference'
import { isIndexSearchable, type QuestionAnswer } from '../types/api'

const EXAMPLES = ['Where is the owner form validated?', 'Which classes handle HTTP requests?', 'How are database entities mapped?']
const LIMIT = 2000

/** `/repositories/:repositoryId/qa?snapshot=`: questions about one commit, answered from its index. */
export default function QAPage() {
  return (
    <SnapshotPageFrame title="Ask">
      {({ repositoryId, snapshotId, commitSha, repositoryName }) => (
        <QAView key={snapshotId} repositoryId={repositoryId} snapshotId={snapshotId} commitSha={commitSha} repositoryName={repositoryName} />
      )}
    </SnapshotPageFrame>
  )
}

function QAView({ repositoryId, snapshotId, commitSha, repositoryName }: { repositoryId: string; snapshotId: string; commitSha: string; repositoryName: string }) {
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
        <Notice>
          <span role="note">
            AI unavailable: {providers.data.unavailableReason ?? 'no provider is configured.'} Questions still return deterministic search results, and{' '}
            <Link to={searchLink} className="text-primary-text underline">
              Search
            </Link>{' '}
            works without any model.{' '}
            <Link to="/settings/ai" className="text-primary-text underline">
              AI settings
            </Link>
          </span>
        </Notice>
      )}
      {index.data?.embeddingStatus === 'NOT_CONFIGURED' && (
        <Notice>
          <span role="note">
            No embedding provider configured: answers use keyword and symbol retrieval only (no semantic search).{' '}
            <Link to={searchLink} className="text-primary-text underline">
              Search
            </Link>{' '}
            finds exact identifiers and words.
          </span>
        </Notice>
      )}
      <div className="flex flex-wrap items-start gap-3">
        <div className="min-w-0 flex-1">
          <IndexStatusPanel repositoryId={repositoryId} snapshotId={snapshotId} />
        </div>
        {turns.length > 0 && (
          <Button variant="secondary" icon="add" onClick={() => setTurns([])}>
            New conversation
          </Button>
        )}
      </div>

      {turns.length > 0 && (
        <ol aria-label="Conversation" className="space-y-4">
          {turns.map((turn) => (
            <li key={turn.messageId ?? turn.question} className="space-y-2">
              <p className="ml-auto w-fit max-w-prose rounded-lg bg-primary px-3 py-2 text-body-lg text-primary-fg">{turn.question}</p>
              <div className={`rounded-lg border p-4 ${turn.aiGenerated ? 'ai-surface' : 'border-line bg-surface'}`}>
                <AnswerView answer={turn} searchLink={searchLink} commitSha={commitSha} />
              </div>
            </li>
          ))}
        </ol>
      )}

      <form onSubmit={submit} className="space-y-2 rounded-lg border border-line bg-surface p-3">
        <label htmlFor="question" className="flex items-center gap-2 text-body-md font-medium">
          <Icon name="sparkle" size={14} className="text-ai-text" />
          {turns.length > 0 ? 'Follow-up question' : `Ask a question about ${repositoryName} (${commitSha.slice(0, 7)})`}
        </label>
        <textarea
          id="question"
          value={question}
          onChange={(event) => setQuestion(event.target.value)}
          maxLength={LIMIT}
          rows={3}
          placeholder="For example: Where is the owner form validated?"
          className="w-full rounded border border-line-strong bg-canvas px-3 py-2 text-body-lg"
        />
        {question.length > LIMIT * 0.8 && <p className="text-body-sm text-muted" aria-live="polite">{question.length} / {LIMIT}</p>}
        <div className="flex flex-wrap items-center gap-2">
          <Button type="submit" variant="ai" icon="sparkle" busy={ask.isPending} disabled={!searchable || question.trim() === ''}>
            {ask.isPending ? 'Asking…' : 'Ask'}
          </Button>
          {turns.length === 0 &&
            EXAMPLES.map((example) => (
              <button key={example} type="button" onClick={() => setQuestion(example)} className="rounded border border-line-strong px-2 py-1 text-body-sm text-fg-2 hover:bg-raised">
                {example}
              </button>
            ))}
          {!searchable && index.data && <span className="text-body-sm text-muted">Index this snapshot to ask questions.</span>}
        </div>
        {ask.isPending && (
          <p role="status" className="text-body-sm text-muted">
            Searching the index and asking the model; this can take up to a minute…
          </p>
        )}
        {ask.error && (
          <p role="alert" className="text-body-sm text-[color:var(--t-failed-fg)]">
            {ask.error.message}
          </p>
        )}
      </form>
    </div>
  )
}
