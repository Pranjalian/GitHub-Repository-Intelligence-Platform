import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { REPO_ID, SNAP_NEW, indexStatus } from '../../test/fixtures'
import { server } from '../../test/server'
import { renderPage } from '../../test/utils'
import type { IndexState } from '../../types/api'
import IndexStatusPanel from './IndexStatusPanel'

const URL = `/api/v1/repositories/${REPO_ID}/snapshots/${SNAP_NEW}/index`
const view = () => renderPage(<IndexStatusPanel repositoryId={REPO_ID} snapshotId={SNAP_NEW} />)

describe('index status bar', () => {
  it('not indexed: offers Index this snapshot and says indexing is optional and separate from analysis', async () => {
    server.use(http.get(URL, () => HttpResponse.json(indexStatus('NOT_INDEXED'))))
    view()
    expect(await screen.findByRole('button', { name: 'Index this snapshot' })).toBeEnabled()
    expect(screen.getByText(/optional and separate from analysis/)).toBeInTheDocument()
  })

  it.each([
    ['COMPLETED', 'Indexed', 'Re-index'],
    ['COMPLETED_WITH_WARNINGS', 'Indexed with warnings', 'Re-index'],
    ['FAILED', 'Indexing failed', 'Retry indexing'],
  ] as [IndexState, string, string][])('%s shows "%s" and "%s"', async (status, label, action) => {
    server.use(http.get(URL, () => HttpResponse.json(indexStatus(status, { detail: 'the reason', warnings: status === 'COMPLETED_WITH_WARNINGS' ? ['3 files skipped'] : [] }))))
    view()
    expect((await screen.findAllByText(label)).length).toBeGreaterThan(0) // the state badge (and, for Indexed, the date label)
    expect(screen.getByRole('button', { name: action })).toBeInTheDocument()
    if (status === 'FAILED') expect(screen.getByText('the reason')).toBeInTheDocument()
    if (status === 'COMPLETED_WITH_WARNINGS') expect(screen.getByText('3 files skipped')).toBeInTheDocument()
  })

  it('indexing shows a progress bar, polls, and stops polling once it is done', async () => {
    const sequence: IndexState[] = ['QUEUED', 'INDEXING', 'COMPLETED']
    let calls = 0
    server.use(http.get(URL, () => HttpResponse.json(indexStatus(sequence[Math.min(calls++, sequence.length - 1)]))))
    view()
    expect(await screen.findByRole('progressbar', { name: 'Indexing' })).toBeInTheDocument()
    await waitFor(() => expect(screen.getAllByText('Indexed').length).toBeGreaterThan(0), { timeout: 8000 })
    const atDone = calls
    await new Promise((r) => setTimeout(r, 2500))
    expect(calls).toBe(atDone)
  }, 15000)

  it('embeddings not configured: keyword and symbol search still work', async () => {
    server.use(http.get(URL, () => HttpResponse.json(indexStatus('COMPLETED', { embeddingStatus: 'NOT_CONFIGURED' }))))
    view()
    expect(await screen.findByText('Semantic search: no embedding provider configured.')).toBeInTheDocument()
  })

  it('requesting an index job posts once', async () => {
    let posted = 0
    server.use(
      http.get(URL, () => HttpResponse.json(indexStatus('NOT_INDEXED'))),
      http.post(URL, () => {
        posted++
        return HttpResponse.json(indexStatus('QUEUED'), { status: 202 })
      }),
    )
    view()
    await userEvent.click(await screen.findByRole('button', { name: 'Index this snapshot' }))
    await waitFor(() => expect(posted).toBe(1))
  })
})
