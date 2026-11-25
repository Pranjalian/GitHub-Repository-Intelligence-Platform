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
          : response({ type: 'TEXT', source: 'KEYWORD_INDEX', query: 'last name', results: [ownerChunk], terms: ['last', 'name'] }),
      )
    }),
  )
  return { searches, indexRequests }
}

/** Terms are emphasised with <mark>, which splits a line into several nodes: match on the line's own text. */
const containsText = (text: string) => (_: string, element: Element | null) => element?.tagName === 'SPAN' && element.textContent?.includes(text) === true && !element.querySelector('span')

const route = { path: '/repositories/:repositoryId/search', initialEntry: `/repositories/${REPO_ID}/search?snapshot=${SNAP_NEW}` }

describe('search page', () => {
  it('finds a symbol with AI disabled and links to GitHub at the commit', async () => {
    const { searches } = stub(indexStatus('NOT_INDEXED'))
    renderPage(<SearchPage />, route)

    expect(await screen.findByRole('heading', { name: 'Code Search' })).toBeInTheDocument()
    expect(await screen.findByRole('button', { name: 'Index this snapshot' })).toBeEnabled()
    await userEvent.click(screen.getByRole('radio', { name: 'Symbol' }))
    await userEvent.click(screen.getByLabelText('Search query'))
    await userEvent.paste('OwnerController')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))

    const link = await screen.findByRole('link', { name: /OwnerController\.java:48-188/ })
    expect(link).toHaveAttribute('href', ownerControllerSymbol.sourceUrl)
    expect(link).toHaveAttribute('rel', 'noreferrer noopener')
    expect(screen.getByText('org.example.owner.OwnerController')).toBeInTheDocument()
    expect(searches[0].get('type')).toBe('symbol')
    expect(searches[0].get('q')).toBe('OwnerController')
    expect(screen.getByText(/Showing 1 symbol match for/)).toBeInTheDocument()
    expect(screen.getByText('symbol graph')).toBeInTheDocument()
    // filters and the query live in the URL
    expect(screen.getByTestId('location').textContent).toContain('q=OwnerController')
    expect(screen.getByTestId('location').textContent).toContain('type=symbol')
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

    expect((await screen.findAllByText(containsText('findByLastName'))).length).toBeGreaterThan(0)
    expect(screen.getAllByText('METHOD').length).toBeGreaterThan(0)
    expect(searches[0].get('fileType')).toBe('SOURCE')
    // the matched line is highlighted and terms are emphasised as marks
    expect(document.querySelector('[data-highlighted]')).not.toBeNull()
    expect(document.querySelector('mark')).not.toBeNull()
    expect(searches[0].get('type')).toBe('text')
  })

  it('starts the search from the URL, so a search can be shared and survives a reload', async () => {
    const { searches } = stub(indexStatus('COMPLETED'))
    renderPage(<SearchPage />, { ...route, initialEntry: `${route.initialEntry}&q=last%20name&type=text&ftype=TEST` })
    expect((await screen.findAllByText(containsText('findByLastName'))).length).toBeGreaterThan(0)
    expect(searches[0].get('q')).toBe('last name')
    expect(searches[0].get('fileType')).toBe('TEST')
  })

  it('says what to do when there are no results, and shows the empty prompt before searching', async () => {
    stub(indexStatus('COMPLETED'))
    server.use(http.get(`${SNAPSHOT}/search`, () => HttpResponse.json(response({ query: 'zzz', results: [] }))))
    renderPage(<SearchPage />, route)
    expect(await screen.findByText('Search this commit')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Search query'))
    await userEvent.paste('zzz')
    await userEvent.click(screen.getByRole('button', { name: 'Search' }))
    expect(await screen.findByText(/No results for "zzz"/)).toBeInTheDocument()
  })

  it('shows the redaction marker for chunks with withheld secrets', async () => {
    stub(indexStatus('COMPLETED'))
    server.use(http.get(`${SNAPSHOT}/search`, () => HttpResponse.json(response({ type: 'TEXT', results: [{ ...ownerChunk, redacted: true }] }))))
    renderPage(<SearchPage />, { ...route, initialEntry: `${route.initialEntry}&q=owner` })
    expect(await screen.findByText('secrets withheld')).toBeInTheDocument()
  })
})
