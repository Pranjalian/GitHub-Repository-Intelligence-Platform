import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import {
  REPO_ID,
  RUN_ID,
  SHA_NEW,
  SHA_OLD,
  SNAP_NEW,
  SNAP_OLD,
  repository,
  report,
  run,
  snapshot,
} from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import RepositoryOverviewPage from './RepositoryOverviewPage'

const ROUTE = '/repositories/:repositoryId'

function stubRepository(snapshots = [snapshot(SNAP_NEW, SHA_NEW, 'COMPLETED'), snapshot(SNAP_OLD, SHA_OLD, 'COMPLETED')]) {
  const requestedReports: string[] = []
  server.use(
    http.get(`/api/v1/repositories/${REPO_ID}`, () => HttpResponse.json(repository(snapshots[0]))),
    http.get(`/api/v1/repositories/${REPO_ID}/snapshots`, () =>
      HttpResponse.json({ items: snapshots, page: 0, size: 50, totalElements: snapshots.length, totalPages: 1 }),
    ),
    http.get(`/api/v1/repositories/${REPO_ID}/reports/:snapshotId`, ({ params }) => {
      const id = params.snapshotId as string
      requestedReports.push(id)
      return HttpResponse.json(report(id, id === SNAP_NEW ? SHA_NEW : SHA_OLD))
    }),
  )
  return requestedReports
}

function renderOverview(search = '') {
  return renderPage(<RepositoryOverviewPage pollIntervalMs={25} />, {
    path: ROUTE,
    initialEntry: `/repositories/${REPO_ID}${search}`,
  })
}

describe('RepositoryOverviewPage', () => {
  it('shows the header, tech stack, structure, capability matrix and limitations of the latest completed snapshot', async () => {
    const requested = stubRepository()
    renderOverview()

    expect(await screen.findByRole('heading', { name: 'octo/demo' })).toBeInTheDocument()
    expect(await screen.findByRole('region', { name: 'Technology stack' })).toBeInTheDocument()
    expect(screen.getByTitle(SHA_NEW)).toBeInTheDocument()
    expect(screen.getByRole('button', { name: `Copy SHA ${SHA_NEW}` })).toBeInTheDocument()
    const tech = screen.getByRole('region', { name: 'Technology stack' })
    expect(within(tech).getByText('Java')).toBeInTheDocument()
    expect(within(tech).getAllByText('Likely').length).toBeGreaterThan(0)
    expect(within(tech).getAllByText('Detected').length).toBeGreaterThan(0)
    expect(screen.getByRole('region', { name: 'Structure' })).toHaveTextContent('12 files')
    expect(screen.getByRole('region', { name: 'Capability matrix' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Limitations' })).toHaveTextContent('semgrep is not installed')
    expect(requested).toEqual([SNAP_NEW])
  })

  it('does not render fake panels for categories that are not implemented', async () => {
    stubRepository()
    renderOverview()

    await screen.findByRole('region', { name: 'Capability matrix' })
    expect(screen.queryByText(/code quality findings/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('region', { name: /security/i })).not.toBeInTheDocument()
  })

  it('switches the report request to the chosen snapshot and puts it in the URL', async () => {
    const requested = stubRepository()
    renderOverview()
    await screen.findByRole('region', { name: 'Technology stack' })

    await userEvent.selectOptions(screen.getByRole('combobox', { name: 'Snapshot' }), SNAP_OLD)

    await waitFor(() => expect(requested).toContain(SNAP_OLD))
    expect(await screen.findByTitle(SHA_OLD)).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(`?snapshot=${SNAP_OLD}`)
  })

  it('honours ?snapshot= in the URL', async () => {
    const requested = stubRepository()
    renderOverview(`?snapshot=${SNAP_OLD}`)

    expect(await screen.findByTitle(SHA_OLD)).toBeInTheDocument()
    await waitFor(() => expect(requested).toEqual([SNAP_OLD]))
  })

  it('defaults to the latest completed snapshot when the newest one is still running', async () => {
    const requested = stubRepository([snapshot(SNAP_NEW, SHA_NEW, 'ANALYZING'), snapshot(SNAP_OLD, SHA_OLD, 'COMPLETED')])
    server.use(http.get(`/api/v1/analysis-runs/${RUN_ID}`, () => HttpResponse.json(run('ANALYZING', { phase: 'ANALYZING' }))))
    renderOverview()

    expect(await screen.findByTitle(SHA_OLD)).toBeInTheDocument()
    await waitFor(() => expect(requested).toEqual([SNAP_OLD]))
  })

  it('shows live progress for a run that has not completed, not a report', async () => {
    const requested = stubRepository([snapshot(SNAP_NEW, SHA_NEW, 'INGESTING')])
    server.use(http.get(`/api/v1/analysis-runs/${RUN_ID}`, () => HttpResponse.json(run('INGESTING', { phase: 'INGESTING' }))))
    renderOverview(`?snapshot=${SNAP_NEW}&run=${RUN_ID}`)

    expect(await screen.findByRole('region', { name: 'Analysis progress' })).toBeInTheDocument()
    expect(screen.queryByRole('region', { name: 'Capability matrix' })).not.toBeInTheDocument()
    expect(requested).toEqual([])
  })

  it('shows an empty state when the repository has no snapshots', async () => {
    stubRepository([])
    renderOverview()

    expect(await screen.findByText('Nothing analyzed yet')).toBeInTheDocument()
  })
})
