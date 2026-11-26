import { QueryClientProvider } from '@tanstack/react-query'
import { act, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { MemoryRouter, Route, Routes } from 'react-router'
import { axe } from 'vitest-axe'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ThemeProvider } from '../lib/theme'
import { server } from '../test/server'
import { LocationDisplay, makeQueryClient } from '../test/utils'
import LoginPage from './LoginPage'

function signedOut() {
  server.use(http.get('/api/v1/auth/session', () => HttpResponse.json({ authRequired: true, authenticated: false })))
}

function renderLogin(entry = '/login') {
  return render(
    <ThemeProvider>
      <QueryClientProvider client={makeQueryClient()}>
        <MemoryRouter initialEntries={[entry]}>
          <Routes>
            <Route path="/login" element={<LoginPage />} />
            <Route path="*" element={<p>the app</p>} />
          </Routes>
          <LocationDisplay />
        </MemoryRouter>
      </QueryClientProvider>
    </ThemeProvider>,
  )
}

async function fill(user: ReturnType<typeof userEvent.setup>, username = 'operator', password = 'secret-password') {
  await user.type(await screen.findByLabelText('Username'), username)
  await user.type(screen.getByLabelText('Password'), password)
}

describe('LoginPage', () => {
  beforeEach(() => signedOut())

  it('renders a real form with the right autocomplete values, no marketing, no register or reset links', async () => {
    renderLogin()
    expect(await screen.findByRole('heading', { name: 'Sign in' })).toBeInTheDocument()
    expect(screen.getByLabelText('Username')).toHaveAttribute('autocomplete', 'username')
    expect(screen.getByLabelText('Password')).toHaveAttribute('autocomplete', 'current-password')
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
    expect(screen.getByRole('button', { name: 'Sign in' }).closest('form')).not.toBeNull()
    expect(screen.queryByText(/forgot|register|create account/i)).not.toBeInTheDocument()
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument()
    expect(screen.getByText(/^RepoIntel v/)).toBeInTheDocument()
  })

  it('submits on Enter and lands on the dashboard, sending the credentials as JSON', async () => {
    let body: unknown
    server.use(
      http.post('/api/v1/auth/login', async ({ request }) => {
        body = await request.json()
        return HttpResponse.json({ authRequired: true, authenticated: true, username: 'operator' })
      }),
    )
    const user = userEvent.setup()
    renderLogin()
    await fill(user)
    await user.keyboard('{Enter}')

    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(body).toEqual({ username: 'operator', password: 'secret-password' })
  })

  it('returns to the page the user wanted after signing in', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({ authRequired: true, authenticated: true, username: 'operator' })))
    const user = userEvent.setup()
    renderLogin('/login?next=%2Frepositories%2F1%3Ftab%3Dquality')
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    await screen.findByText('the app')
    expect(screen.getByTestId('location')).toHaveTextContent('/repositories/1?tab=quality')
  })

  it('asks the server for a CSRF cookie first when the browser has none', async () => {
    let sessionReads = 0
    server.use(
      http.get('/api/v1/auth/session', () => {
        sessionReads += 1
        document.cookie = 'XSRF-TOKEN=fresh-token; Path=/'
        return HttpResponse.json({ authRequired: true, authenticated: false })
      }),
      http.post('/api/v1/auth/login', ({ request }) =>
        request.headers.get('X-XSRF-TOKEN') === 'fresh-token'
          ? HttpResponse.json({ authRequired: true, authenticated: true, username: 'operator' })
          : HttpResponse.json({ status: 403 }, { status: 403 }),
      ),
    )
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
    const user = userEvent.setup()
    renderLogin()
    await fill(user)
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/' // the user cleared cookies after the page loaded
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('the app')).toBeInTheDocument()
    expect(sessionReads).toBe(2)
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  it('shows and hides the password with a labelled toggle that reports its state', async () => {
    const user = userEvent.setup()
    renderLogin()
    const toggle = await screen.findByRole('button', { name: 'Show password' })
    expect(toggle).toHaveAttribute('aria-pressed', 'false')

    await user.click(toggle)
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'text')
    expect(toggle).toHaveAttribute('aria-pressed', 'true')

    await user.click(toggle)
    expect(screen.getByLabelText('Password')).toHaveAttribute('type', 'password')
  })

  it('while submitting: the button is busy and disabled and the fields are read-only', async () => {
    let release: () => void = () => undefined
    server.use(
      http.post('/api/v1/auth/login', async () => {
        await new Promise<void>((resolve) => (release = resolve))
        return HttpResponse.json({ authRequired: true, authenticated: true, username: 'operator' })
      }),
    )
    const user = userEvent.setup()
    renderLogin()
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    const busy = await screen.findByRole('button', { name: 'Signing in…' })
    expect(busy).toBeDisabled()
    expect(screen.getByLabelText('Username')).toHaveAttribute('readonly')
    expect(screen.getByLabelText('Password')).toHaveAttribute('readonly')
    release()
    await screen.findByText('the app')
  })

  it('wrong credentials: one generic alert, password cleared, focus back on the password field', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({ status: 401, detail: 'Invalid username or password' }, { status: 401 })))
    const user = userEvent.setup()
    renderLogin()
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid username or password')
    const password = screen.getByLabelText('Password')
    expect(password).toHaveValue('')
    await waitFor(() => expect(password).toHaveFocus())
    expect(screen.getByLabelText('Username')).toHaveValue('operator')
  })

  it('throttled: shows a countdown from Retry-After, disables the button, and re-enables it at zero', async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    try {
      server.use(
        http.post('/api/v1/auth/login', () =>
          HttpResponse.json({ status: 429, detail: 'Too many failed sign-in attempts.', retryAfterSeconds: 3 }, { status: 429, headers: { 'Retry-After': '3' } }),
        ),
      )
      const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime })
      renderLogin()
      await fill(user)
      await user.click(screen.getByRole('button', { name: 'Sign in' }))

      expect(await screen.findByText(/Too many failed attempts/)).toBeInTheDocument()
      expect(screen.getByTestId('countdown')).toHaveTextContent('Try again in 0:0')
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeDisabled()

      await act(async () => {
        vi.advanceTimersByTime(3500)
      })
      expect(screen.getByTestId('countdown')).toHaveTextContent('You can try again now.')
      expect(screen.getByRole('button', { name: 'Sign in' })).toBeEnabled()
    } finally {
      vi.useRealTimers()
    }
  })

  it('expired session: a calm note, and no error', async () => {
    renderLogin('/login?expired=1&next=%2Frepositories%2F1')
    expect(await screen.findByText('Your session ended. Sign in again.')).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('server or network error: a generic message, never the server text', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({ status: 500, detail: 'java.lang.NullPointerException at Secret.java:42' }, { status: 500 })))
    const user = userEvent.setup()
    renderLogin()
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))

    const alert = await screen.findByRole('alert')
    expect(alert).toHaveTextContent('Could not sign in right now')
    expect(alert).not.toHaveTextContent('NullPointer')
    expect(screen.getByLabelText('Password')).toHaveValue('')
  })

  it('a network failure is also generic', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.error()))
    const user = userEvent.setup()
    renderLogin()
    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not sign in right now')
  })

  it('does not submit an empty form', async () => {
    let called = false
    server.use(http.post('/api/v1/auth/login', () => ((called = true), HttpResponse.json({}))))
    const user = userEvent.setup()
    renderLogin()
    await user.click(await screen.findByRole('button', { name: 'Sign in' }))
    expect(called).toBe(false)
  })

  it('keeps the password out of every browser storage', async () => {
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({ status: 401 }, { status: 401 })))
    const user = userEvent.setup()
    renderLogin()
    await fill(user, 'operator', 'never-stored-password')
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await screen.findByRole('alert')
    const dump = JSON.stringify({ ...window.localStorage }) + JSON.stringify({ ...window.sessionStorage })
    expect(dump).not.toContain('never-stored-password')
  })

  afterEach(() => {
    window.localStorage.clear()
  })
})

/** jsdom has no layout, so colour contrast is checked by the token test (styles/contrast.test.ts), not here. */
async function expectNoViolations(container: HTMLElement) {
  const results = await axe(container, { rules: { 'color-contrast': { enabled: false } } })
  expect(results.violations.map((v) => `${v.id}: ${v.nodes.map((n) => n.html.slice(0, 80)).join(' | ')}`)).toEqual([])
}

describe.each(['dark', 'light'])('LoginPage accessibility, %s theme', (theme) => {
  it('has no axe violations in any state', async () => {
    signedOut()
    window.localStorage.setItem('repointel.theme', theme)
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({ status: 401 }, { status: 401 })))
    const user = userEvent.setup()
    const { container } = renderLogin('/login?expired=1')
    await expectNoViolations(container)

    await fill(user)
    await user.click(screen.getByRole('button', { name: 'Sign in' }))
    await screen.findByRole('alert')
    await expectNoViolations(container)
    window.localStorage.clear()
  })
})
