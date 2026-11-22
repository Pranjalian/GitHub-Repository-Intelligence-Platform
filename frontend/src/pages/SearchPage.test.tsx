import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import {
  REPO_ID,
  SHA_NEW,
  SNAP_NEW,
  indexStatus,
  ownerChunk,
  ownerControllerSymbol,
  repository,
  snapshot,
} from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { IndexStatus, SearchResponse } from '../types/api'
import SearchPage from './SearchPage'

const BASE = `/api/v1/repositories/${REPO_ID}`
const SNAPSHOT = `${BASE}/snapshots/${SNAP_NEW}`

const page = <T,>(items: T[]) => ({ items, page: 0, size: 50, totalElements: items.length, totalPages: 1 })

function response(extra: Partial<SearchResponse>): SearchResponse {
  return {
    query: 'OwnerController',
    type: 'SYMBOL',
    status: 'OK',
    reason: null,
    commitSha: SHA_NEW,
    source: 'SYMBOL_GRAPH',
    results: [],
    terms: [],
    index: indexStatus('NOT_INDEXED'),
    ...extra,
  }
}

function stub(index: IndexStatus) {
  const searches: URLSearchParams[] = []
  const indexRequests: string[] = []
  server.use(
    http.get(BASE, () => HttpResponse.json(repository())),
    http.get(`${BASE}/snapshots`, () => HttpResponse.json(page([snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED')]))),
    http.get(`${SNAPSHOT}/index`, () => HttpResponse.json(index)),
    http.post(`${SNAPSHOT}/index`, () => {
      indexRequests.push('POST')
      return HttpResponse.json(indexStatus('QUEUED'), { status: 202 })
    }),
    http.get(`${SNAPSHOT}/search`, ({ request }) => {
      const params = new URL(request.url).searchParams
      searches.push(params)
      if (params.get('type') === 'symbol') {
        return HttpResponse.json(response({ results: [ownerControllerSymbol] }))
      }
      return HttpResponse.json(
        index.status === 'NOT_INDEXED'
          ? response({
              type: 'TEXT',
              status: 'NOT_INDEXED',
              reason: 'Text search needs the snapshot index; index this snapshot first.',
              source: 'KEYWORD_INDEX',
            })
          : response({ type: 'TEXT', source: 'KEYWORD_INDEX', query: 'last name', results: [ownerChunk] }),
      )
    }),
  )
  return { searches, indexRequests }
}

const route = { path: '/repositories/:repositoryId/search', initialEntry: `/repositories/${REPO_ID}/search?snapshot=${SNAP_NEW}` }

describe('search page', () => {
  it('finds a symbol with AI disabled and links to GitHub at the commit', async () => {
    const { searches } = stub(indexStatus('NOT_INDEXED'))
    renderPage(<SearchPage />, route)

    expect(await screen.findByRole('heading', { name: 'Search octo/demo' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Index this snapshot' })).toBeEnabled()
    await userEvent.selectOptions(screen.getByLabelText('Search type'), 'symbol')
    await userEvent.click(screen.getByLabelText('Search query'))
    await userEvent.paste('OwnerController')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    const link = await screen.findByRole('link', { name: /OwnerController\.java:48-188/ })
    expect(link).toHaveAttribute('href', ownerControllerSymbol.sourceUrl)
    expect(link).toHaveAttribute('rel', 'noreferrer noopener')
    expect(screen.getByText('org.example.owner.OwnerController')).toBeInTheDocument()
    expect(searches[0].get('type')).toBe('symbol')
    expect(searches[0].get('q')).toBe('OwnerController')
  })

  it('says that text search needs the index and starts an index job', async () => {
    const { indexRequests } = stub(indexStatus('NOT_INDEXED'))
    renderPage(<SearchPage />, route)

    await userEvent.click(await screen.findByLabelText('Search query'))
    await userEvent.paste('last name')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByRole('note')).toHaveTextContent('index this snapshot first')

    await userEvent.click(screen.getByRole('button', { name: 'Index this snapshot' }))
    await waitFor(() => expect(indexRequests).toHaveLength(1))
    expect(await screen.findByText('Queued')).toBeInTheDocument()
  })

  it('shows text results with their scrubbed snippet as plain text and the filters it sent', async () => {
    const { searches } = stub(indexStatus('COMPLETED'))
    renderPage(<SearchPage />, route)

    expect(await screen.findByText('Semantic search: no embedding provider configured.')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Search query'))
    await userEvent.paste('last name')
    await userEvent.selectOptions(screen.getByLabelText('File type'), 'SOURCE')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    expect(await screen.findByText(/findByLastName/)).toBeInTheDocument()
    expect(screen.getByText('METHOD')).toBeInTheDocument()
    expect(searches[0].get('fileType')).toBe('SOURCE')
    expect(searches[0].get('type')).toBe('text')
  })
})
