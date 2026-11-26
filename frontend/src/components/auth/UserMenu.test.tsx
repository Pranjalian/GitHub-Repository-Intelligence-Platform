import { QueryClientProvider } from '@tanstack/react-query'
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { describe, expect, it } from 'vitest'
import { writePreferredProvider, readPreferredProvider } from '../../lib/aiPreference'
import { server } from '../../test/server'
import { LocationDisplay, makeQueryClient } from '../../test/utils'
import UserMenu from './UserMenu'

function renderMenu(queryClient = makeQueryClient()) {
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/repositories/1']}>
        <Routes>
          <Route path="*" element={<UserMenu />} />
        </Routes>
        <LocationDisplay />
      </MemoryRouter>
    </QueryClientProvider>,
  )
  return queryClient
}

const signedIn = { authRequired: true, authenticated: true, username: 'operator' }

describe('UserMenu', () => {
  it('is absent when no login is required (local use)', async () => {
    renderMenu()
    // wait for the session to load
    await waitFor(() => expect(screen.queryByRole('button')).not.toBeInTheDocument())
  })

  it('shows the username and Sign out, keyboard operable, and Escape closes it', async () => {
    server.use(http.get('/api/v1/auth/session', () => HttpResponse.json(signedIn)))
    const user = userEvent.setup()
    renderMenu()

    const trigger = await screen.findByRole('button', { name: /Account menu, signed in as operator/ })
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    trigger.focus()
    await user.keyboard('{Enter}')
    expect(trigger).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('Signed in as')).toBeInTheDocument()
    await user.tab()
    expect(screen.getByRole('button', { name: 'Sign out' })).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(trigger).toHaveAttribute('aria-expanded', 'false')
    expect(trigger).toHaveFocus()
  })

  it('signing out calls the server, clears the cache, keeps the AI preference and goes to /login', async () => {
    let logoutCalled = false
    let sessionReads = 0
    server.use(
      http.get('/api/v1/auth/session', () => {
        sessionReads += 1
        return HttpResponse.json(logoutCalled ? { authRequired: true, authenticated: false } : signedIn)
      }),
      http.post('/api/v1/auth/logout', () => {
        logoutCalled = true
        return new HttpResponse(null, { status: 204 })
      }),
    )
    writePreferredProvider('gemini')
    const user = userEvent.setup()
    const queryClient = renderMenu()
    queryClient.setQueryData(['repositories', 0], { items: ['private to the signed-in user'] })

    await user.click(await screen.findByRole('button', { name: /Account menu/ }))
    await user.click(screen.getByRole('button', { name: 'Sign out' }))

    await waitFor(() => expect(screen.getByTestId('location')).toHaveTextContent('/login'))
    expect(logoutCalled).toBe(true)
    expect(queryClient.getQueryData(['repositories', 0])).toBeUndefined()
    expect(queryClient.getQueryData(['auth', 'session'])).toEqual({ authRequired: true, authenticated: false })
    expect(sessionReads).toBeGreaterThanOrEqual(2)
    expect(readPreferredProvider()).toBe('gemini')
    writePreferredProvider(null)
  })
})
