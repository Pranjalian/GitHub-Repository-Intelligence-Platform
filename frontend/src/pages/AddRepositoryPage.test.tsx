import { screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { REPO_ID, RUN_ID, SNAP_NEW, SHA_NEW } from '../test/fixtures'
import { renderPage } from '../test/utils'
import AddRepositoryPage from './AddRepositoryPage'

const accepted = {
  repositoryId: REPO_ID,
  snapshotId: SNAP_NEW,
  commitSha: SHA_NEW,
  branch: 'main',
  analysisRunId: RUN_ID,
  status: 'QUEUED',
  reused: false,
  warnings: [],
}

function renderAdd() {
  return renderPage(<AddRepositoryPage />, { path: '/' })
}

describe('AddRepositoryPage', () => {
  it('renders the URL input and the force-rescan checkbox', async () => {
    renderAdd()
    const input = screen.getByLabelText('Repository URL')
    await userEvent.type(input, 'https://github.com/o/r')
    expect(input).toHaveValue('https://github.com/o/r')
    expect(screen.getByLabelText('Force full rescan')).not.toBeChecked()
  })

  it('rejects an invalid URL on the client without calling the API', async () => {
    let called = false
    server.use(
      http.post('/api/v1/repositories/analyze', () => {
        called = true
        return HttpResponse.json(accepted, { status: 202 })
      }),
    )
    renderAdd()

    await userEvent.type(screen.getByLabelText('Repository URL'), 'https://gitlab.com/o/r')
    await userEvent.click(screen.getByRole('button', { name: 'Analyze' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Only github.com repositories are supported.')
    expect(called).toBe(false)
  })

  it('submits, shows the loading state, and navigates to the repository page with the run id', async () => {
    let body: unknown
    server.use(
      http.post('/api/v1/repositories/analyze', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(accepted, { status: 202 })
      }),
    )
    renderAdd()

    await userEvent.type(screen.getByLabelText('Repository URL'), 'github.com/o/r')
    await userEvent.click(screen.getByLabelText('Force full rescan'))
    await userEvent.click(screen.getByRole('button', { name: 'Analyze' }))

    await waitFor(() =>
      expect(screen.getByTestId('location')).toHaveTextContent(
        `/repositories/${REPO_ID}?snapshot=${SNAP_NEW}&run=${RUN_ID}`,
      ),
    )
    expect(body).toEqual({ url: 'github.com/o/r', forceRescan: true })
  })

  it('also navigates when an existing run is reused (200)', async () => {
    server.use(
      http.post('/api/v1/repositories/analyze', () => HttpResponse.json({ ...accepted, reused: true }, { status: 200 })),
    )
    renderAdd()

    await userEvent.type(screen.getByLabelText('Repository URL'), 'https://github.com/o/r')
    await userEvent.click(screen.getByRole('button', { name: 'Analyze' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent(`/repositories/${REPO_ID}`))
  })

  it('disables the button while the request is in flight', async () => {
    let release: () => void = () => {}
    server.use(
      http.post('/api/v1/repositories/analyze', async () => {
        await new Promise<void>((resolve) => {
          release = resolve
        })
        return HttpResponse.json(accepted, { status: 202 })
      }),
    )
    renderAdd()

    await userEvent.type(screen.getByLabelText('Repository URL'), 'https://github.com/o/r')
    await userEvent.click(screen.getByRole('button', { name: 'Analyze' }))

    expect(await screen.findByRole('button', { name: 'Analyzing…' })).toBeDisabled()
    release()
  })

  it.each([
    [400, 'Repository URL is malformed.'],
    [404, 'The repository does not exist or is private.'],
    [422, 'The repository is too large to analyze.'],
  ])('shows the API error message for %i', async (status, detail) => {
    server.use(http.post('/api/v1/repositories/analyze', () => HttpResponse.json({ status, detail }, { status })))
    renderAdd()

    await userEvent.type(screen.getByLabelText('Repository URL'), 'https://github.com/o/r')
    await userEvent.click(screen.getByRole('button', { name: 'Analyze' }))

    expect(await screen.findByRole('alert')).toHaveTextContent(detail)
  })

  it('shows when to retry after a 429', async () => {
    server.use(
      http.post('/api/v1/repositories/analyze', () =>
        HttpResponse.json({ status: 429, detail: 'Too many submissions.', retryAfterSeconds: 42 }, { status: 429 }),
      ),
    )
    renderAdd()

    await userEvent.type(screen.getByLabelText('Repository URL'), 'https://github.com/o/r')
    await userEvent.click(screen.getByRole('button', { name: 'Analyze' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Too many submissions. Try again in about 42 seconds.')
  })
})
