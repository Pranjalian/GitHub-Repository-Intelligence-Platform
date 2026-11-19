import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import App from './App'
import MainLayout from './components/layout/MainLayout'
import Sidebar from './components/layout/Sidebar'
import AddRepositoryPage from './pages/AddRepositoryPage'
import DashboardPage from './pages/DashboardPage'

describe('App', () => {
  it('renders the dashboard shell with the empty state', async () => {
    render(<App />)
    expect(await screen.findByRole('heading', { name: 'GitHub Repository Intelligence Platform' })).toBeInTheDocument()
    expect(screen.getByText('No repositories analyzed yet')).toBeInTheDocument()
  })
})

describe('Sidebar', () => {
  it('renders the navigation items', () => {
    render(
      <MemoryRouter>
        <Sidebar />
      </MemoryRouter>,
    )
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('href', '/')
    expect(screen.getByRole('link', { name: 'Add Repository' })).toHaveAttribute('href', '/repositories/new')
  })
})

describe('MainLayout', () => {
  it('renders the routed page inside the layout', () => {
    render(
      <MemoryRouter initialEntries={['/repositories/new']}>
        <Routes>
          <Route element={<MainLayout />}>
            <Route index element={<DashboardPage />} />
            <Route path="repositories/new" element={<AddRepositoryPage />} />
          </Route>
        </Routes>
      </MemoryRouter>,
    )
    expect(screen.getByRole('navigation', { name: 'Main' })).toBeInTheDocument()
    expect(screen.getByLabelText('Repository URL')).toBeInTheDocument()
  })
})
