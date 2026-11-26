import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen } from '@testing-library/react'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { server } from '../../test/server'
import { LocationDisplay, makeQueryClient } from '../../test/utils'
import RequireSession from './RequireSession'
import LoginPage from '../../pages/LoginPage'

function session(body: { authRequired: boolean; authenticated: boolean; username?: string }) {
  server.use(http.get('/api/v1/auth/session', () => HttpResponse.json(body)))
}

function renderApp(entry: string) {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={[entry]}>
        <Routes>
          <Route path="/login" element={<LoginPage />} />
          <Route element={<RequireSession />}>
            <Route path="*" element={<p>the app</p>} />
          </Route>
        </Routes>
        <LocationDisplay />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

describe('RequireSession', () => {
  it('shows only a neutral splash while the session is loading (no app, no login page)', async () => {
    server.use(http.get('/api/v1/auth/session', async () => new Promise(() => undefined)))
    renderApp('/repositories/1')

    expect(await screen.findByRole('status')).toHaveTextContent('Loading')
    expect(screen.queryByText('the app')).not.toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('redirects a signed-out visitor to /login with the page they wanted as next', async () => {
    session({ authRequired: true, authenticated: false })
    renderApp('/repositories/1?tab=quality')

    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/login?next=%2Frepositories%2F1%3Ftab%3Dquality')
    expect(screen.queryByText('the app')).not.toBeInTheDocument()
  })

  it('goes to plain /login when the wanted page is the dashboard', async () => {
    session({ authRequired: true, authenticated: false })
    renderApp('/')
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/login$/)
  })

  it('renders the app for a signed-in user', async () => {
    session({ authRequired: true, authenticated: true, username: 'operator' })
    renderApp('/repositories/1')
    expect(await screen.findByText('the app')).toBeInTheDocument()
  })

  it('does nothing when no login is required (local use): no redirect, no login UI', async () => {
    session({ authRequired: false, authenticated: false })
    renderApp('/repositories/1')
    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/repositories/1')
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('sends a signed-in user away from /login to next (safe paths only)', async () => {
    session({ authRequired: true, authenticated: true, username: 'operator' })
    renderApp('/login?next=%2Frepositories%2F1%3Ftab%3Dquality')
    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent('/repositories/1?tab=quality')
  })

  it('ignores an unsafe next after sign-in', async () => {
    session({ authRequired: true, authenticated: true, username: 'operator' })
    renderApp('/login?next=%2F%2Fevil.com')
    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(screen.getByTestId('location')).toHaveTextContent(/^\/$/)
  })

  it('never shows the login page when no login is required', async () => {
    session({ authRequired: false, authenticated: false })
    renderApp('/login')
    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(screen.queryByRole('heading', { name: 'Sign in' })).not.toBeInTheDocument()
  })

  it('offers a retry when the session cannot be read, instead of guessing', async () => {
    server.use(http.get('/api/v1/auth/session', () => HttpResponse.error()))
    renderApp('/repositories/1')
    expect(await screen.findByRole('button', { name: 'Try again' })).toBeInTheDocument()
    expect(screen.queryByText('the app')).not.toBeInTheDocument()
  })
})
