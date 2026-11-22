import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import {
  REPO_ID,
  SHA_NEW,
  SNAP_NEW,
  aiProvidersEnabled,
  answer,
  indexStatus,
  ownerChunk,
  repository,
  snapshot,
} from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { IndexStatus, QuestionAnswer, QuestionRequest } from '../types/api'
import QAPage from './QAPage'

const BASE = `/api/v1/repositories/${REPO_ID}`
const SNAPSHOT = `${BASE}/snapshots/${SNAP_NEW}`

const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })

function stub(index: IndexStatus, answers: QuestionAnswer[], aiEnabled = true) {
  const asked: QuestionRequest[] = []
  server.use(
    http.get(BASE, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]))),
    http.get(`${SNAPSHOT}/index`, () => HttpResponse.json(index)),
    http.post(`${SNAPSHOT}/questions`, async ({ request }) => {
      asked.push((await request.json()) as QuestionRequest)
      return HttpResponse.json(answers[Math.min(asked.length, answers.length) - 1])
    }),
  )
  if (aiEnabled) {
    server.use(http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)))
  }
  return asked
}

const route = { path: '/repositories/:repositoryId/qa', initialEntry: `/repositories/${REPO_ID}/qa?snapshot=${SNAP_NEW}` }

async function ask(text: string) {
  const box = await screen.findByRole('textbox')
  await userEvent.clear(box)
  // pasted, not typed key by key: typing long questions is slow on a loaded machine and adds nothing here
  await userEvent.click(box)
  await userEvent.paste(text)
  await userEvent.click(screen.getByRole('button', { name: 'Ask' }))
}

describe('Q&A page', () => {
  it('answers with an AI-generated badge and validated source links, and sends follow-ups in the same conversation', async () => {
    const followUp = answer({ question: 'And where is it tested?', messageId: '99999999-9999-9999-9999-999999999999' })
    const asked = stub(indexStatus('COMPLETED', { embeddingStatus: 'COMPLETED', embeddingModel: 'nomic-embed-text' }), [
      answer(),
      followUp,
    ])
    renderPage(<QAPage />, route)

    await ask('Where are owners searched by last name?')

    const conversation = await screen.findByRole('list', { name: 'Conversation' })
    expect(within(conversation).getByText('AI-generated')).toBeInTheDocument()
    expect(within(conversation).getByText(/Owners are looked up in/)).toBeInTheDocument()
    expect(within(conversation).getByRole('link', { name: /OwnerController\.java:90-118/ })).toHaveAttribute(
      'href',
      ownerChunk.sourceUrl,
    )
    // repository-derived and model text is never rendered as HTML
    expect(conversation.querySelector('img')).toBeNull()
    expect(asked[0].conversationId).toBeNull()

    await ask('And where is it tested?')
    await waitFor(() => expect(asked).toHaveLength(2))
    expect(asked[1].conversationId).toBe('77777777-7777-7777-7777-777777777777')
    expect(await screen.findByText('And where is it tested?')).toBeInTheDocument()
  })

  it('explains that AI is unavailable, points to Search and shows the deterministic results it got', async () => {
    const unavailable = answer({
      status: 'UNAVAILABLE',
      aiGenerated: false,
      answer: null,
      sources: [],
      reason: 'Conversational answers are unavailable: AI is disabled (ai.enabled=false).',
      searchResults: [ownerChunk],
      provider: null,
      model: null,
    })
    stub(indexStatus('COMPLETED'), [unavailable], false)
    renderPage(<QAPage />, route)

    const notes = await screen.findAllByRole('note')
    expect(notes[0]).toHaveTextContent('AI unavailable')
    expect(within(notes[0]).getByRole('link', { name: 'Search' })).toHaveAttribute(
      'href',
      `/repositories/${REPO_ID}/search?snapshot=${SNAP_NEW}`,
    )
    expect(notes[1]).toHaveTextContent('No embedding provider configured')

    await ask('Where are owners searched?')

    expect(await screen.findByText('AI unavailable')).toBeInTheDocument()
    expect(screen.queryByText('AI-generated')).not.toBeInTheDocument()
    expect(screen.getByText(/Deterministic search results/)).toBeInTheDocument()
    expect(screen.getByText(/findByLastName/)).toBeInTheDocument()
  })

  it('shows an explicit not-enough-evidence answer with what was searched', async () => {
    stub(indexStatus('COMPLETED'), [
      answer({
        status: 'INSUFFICIENT_EVIDENCE',
        aiGenerated: false,
        insufficientEvidence: true,
        answer: 'No evidence for this question was found in octo/demo at commit aaaaaaa.',
        clarifyingQuestion: 'Could you name a class, method, file or configuration key?',
        sources: [],
      }),
    ])
    renderPage(<QAPage />, route)

    await ask('Where is the xylophone configured?')

    expect(await screen.findByText('Not enough evidence')).toBeInTheDocument()
    expect(screen.getByText(/Could you name a class/)).toBeInTheDocument()
    expect(screen.getByText(/Searched: keyword search/)).toBeInTheDocument()
  })

  it('cannot ask before the snapshot is indexed', async () => {
    stub(indexStatus('NOT_INDEXED'), [])
    renderPage(<QAPage />, route)

    expect(await screen.findByText('Index this snapshot to ask questions.')).toBeInTheDocument()
    await userEvent.type(screen.getByRole('textbox'), 'Anything?')
    expect(screen.getByRole('button', { name: 'Ask' })).toBeDisabled()
  })
})
