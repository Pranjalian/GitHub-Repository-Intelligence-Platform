import { screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { SHA_NEW, repository } from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import DashboardPage from './DashboardPage'

describe('DashboardPage', () => {
  it('lists repositories with name, URL, short commit and status', async () => {
    server.use(
      http.get('/api/v1/repositories', () =>
        HttpResponse.json({ items: [repository()], page: 0, size: 20, totalElements: 1, totalPages: 1 }),
      ),
    )
    renderPage(<DashboardPage />)

    const link = await screen.findByRole('link', { name: 'octo/demo' })
    expect(link).toHaveAttribute('href', expect.stringMatching(/^\/repositories\/1111/))
    expect(screen.getByText('https://github.com/octo/demo')).toBeInTheDocument()
    expect(screen.getByText(SHA_NEW.slice(0, 7))).toBeInTheDocument()
    expect(screen.getByText('Completed')).toBeInTheDocument()
  })

  it('shows an empty state when there are no repositories', async () => {
    server.use(
      http.get('/api/v1/repositories', () =>
        HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
    )
    renderPage(<DashboardPage />)

    expect(await screen.findByText('No repositories analyzed yet')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Add a repository' })).toBeInTheDocument()
  })

  it('shows the API error', async () => {
    server.use(http.get('/api/v1/repositories', () => HttpResponse.json({ status: 500, detail: 'boom' }, { status: 500 })))
    renderPage(<DashboardPage />)

    expect(await screen.findByRole('alert')).toHaveTextContent('boom')
  })
})
