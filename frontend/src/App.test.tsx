import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import App from './App'
import MainLayout from './components/layout/MainLayout'
import Sidebar from './components/layout/Sidebar'
import AddRepositoryPage from './pages/AddRepositoryPage'
import DashboardPage from './pages/DashboardPage'
import { server } from './test/server'
import { makeQueryClient } from './test/utils'

describe('App', () => {
  it('renders the dashboard shell with the empty state', async () => {
    server.use(
      http.get('/api/v1/repositories', () =>
        HttpResponse.json({ items: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
    )
    render(<App />)
    expect(await screen.findByRole('link', { name: 'RepoIntel home' })).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Skip to content' })).toBeInTheDocument()
    expect(await screen.findByText('No repositories analyzed yet')).toBeInTheDocument()
  })
})

describe('Sidebar', () => {
  it('renders the navigation items', () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter>
          <Sidebar />
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Add Repository' })).toHaveAttribute('href', '/repositories/new')
  })
})

describe('MainLayout', () => {
  it('renders the routed page inside the layout', () => {
    render(
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter initialEntries={['/repositories/new']}>
          <Routes>
            <Route element={<MainLayout />}>
              <Route index element={<DashboardPage />} />
              <Route path="repositories/new" element={<AddRepositoryPage />} />
            </Route>
          </Routes>
        </MemoryRouter>
      </QueryClientProvider>,
    )
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.getByLabelText('Repository URL')).toBeInTheDocument()
  })
})
