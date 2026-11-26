import { useEffect, useId, useRef, useState, type FormEvent } from 'react'
import { Navigate, useSearchParams } from 'react-router'
import { ApiError } from '../api/client'
import ThemeToggle from '../components/layout/ThemeToggle'
import { Button, Icon, Notice } from '../components/ui'
import { useLogin, useSession } from '../hooks/useSession'
import { safeNext } from '../lib/safeNext'

const GENERIC_ERROR = 'Could not sign in right now. Try again in a moment.'

function formatCountdown(seconds: number): string {
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${m}:${String(s).padStart(2, '0')}`
}

/** The current time, refreshed once a second while `active` (drives the Retry-After countdown). */
function useTicker(active: boolean): [number, () => void] {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!active) return
    const timer = window.setInterval(() => setNow(Date.now()), 1000)
    return () => window.clearInterval(timer)
  }, [active])
  return [now, () => setNow(Date.now())]
}

/**
 * The sign-in screen (`/login`), outside the app shell. One generic message for any credential failure; a
 * throttled attempt shows a live countdown from the server's Retry-After; a network or server problem shows a
 * generic message and never the server's text.
 */
export default function LoginPage() {
  const session = useSession()
  const login = useLogin()
  const [params] = useSearchParams()
  const userId = useId()
  const passwordId = useId()
  const errorId = useId()
  const passwordRef = useRef<HTMLInputElement>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [reveal, setReveal] = useState(false)
  const [failure, setFailure] = useState<'credentials' | 'throttled' | 'server' | null>(null)
  const [lockUntil, setLockUntil] = useState<number | null>(null)
  const [now, syncNow] = useTicker(lockUntil !== null)
  const secondsLeft = lockUntil === null ? 0 : Math.max(0, Math.ceil((lockUntil - now) / 1000))
  const locked = failure === 'throttled' && secondsLeft > 0

  const next = safeNext(params.get('next'))
  const expired = params.get('expired') === '1'

  // Focus returns to the password field after a wrong password (the field was cleared).
  useEffect(() => {
    if (failure === 'credentials') passwordRef.current?.focus()
  }, [failure])

  if (session.loading) {
    return (
      <div role="status" className="grid min-h-screen place-items-center bg-canvas text-fg-2">
        <span>Loading…</span>
      </div>
    )
  }
  // Local use (no login required) or already signed in: nothing to do here.
  if (!session.authRequired || session.authenticated) {
    return <Navigate to={next} replace />
  }

  function submit(event: FormEvent) {
    event.preventDefault()
    if (login.isPending || locked || !username.trim() || !password) return
    setFailure(null)
    login.mutate(
      { username: username.trim(), password },
      {
        onError: (error) => {
          setPassword('')
          if (error instanceof ApiError && error.status === 401) {
            setFailure('credentials')
          } else if (error instanceof ApiError && error.status === 429) {
            setFailure('throttled')
            syncNow()
            setLockUntil(Date.now() + Math.max(1, error.retryAfterSeconds ?? 60) * 1000)
          } else {
            setFailure('server')
          }
        },
      },
    )
  }

  const busy = login.isPending

  return (
    <div className="flex min-h-screen flex-col bg-canvas text-fg">
      <main className="grid flex-1 place-items-center p-4">
        <div className="w-full max-w-[400px]">
          <div className="mb-6 flex items-center justify-center gap-2 font-semibold tracking-tight">
            <Icon name="logo" size={24} className="text-primary-text" />
            <span className="text-headline-lg">RepoIntel</span>
          </div>
          <form onSubmit={submit} noValidate className="space-y-4 rounded-xl border border-line bg-surface p-6" aria-labelledby="signin-title">
            <h1 id="signin-title" className="text-headline-lg font-semibold tracking-tight">
              Sign in
            </h1>

            {expired && !failure && <Notice>Your session ended. Sign in again.</Notice>}

            <div>
              <label htmlFor={userId} className="mb-1 block text-body-md font-medium">
                Username
              </label>
              <input
                id={userId}
                name="username"
                type="text"
                autoComplete="username"
                autoCapitalize="none"
                spellCheck={false}
                required
                autoFocus
                readOnly={busy}
                value={username}
                onChange={(event) => setUsername(event.target.value)}
                aria-invalid={failure === 'credentials' ? true : undefined}
                aria-describedby={failure ? errorId : undefined}
                className="h-9 w-full rounded border border-line-strong bg-canvas px-3 text-body-md"
              />
            </div>

            <div>
              <label htmlFor={passwordId} className="mb-1 block text-body-md font-medium">
                Password
              </label>
              <div className="relative">
                <input
                  id={passwordId}
                  ref={passwordRef}
                  name="password"
                  type={reveal ? 'text' : 'password'}
                  autoComplete="current-password"
                  required
                  readOnly={busy}
                  value={password}
                  onChange={(event) => setPassword(event.target.value)}
                  aria-invalid={failure === 'credentials' ? true : undefined}
                  aria-describedby={failure ? errorId : undefined}
                  className="h-9 w-full rounded border border-line-strong bg-canvas pl-3 pr-10 text-body-md"
                />
                <button
                  type="button"
                  aria-label="Show password"
                  aria-pressed={reveal}
                  onClick={() => setReveal((value) => !value)}
                  className="absolute right-1 top-1 inline-flex size-7 items-center justify-center rounded text-fg-2 hover:bg-raised hover:text-fg"
                >
                  <Icon name={reveal ? 'eye-off' : 'eye'} size={16} />
                </button>
              </div>
            </div>

            <div id={errorId}>
              {failure === 'credentials' && (
                <p role="alert" className="flex items-start gap-2 text-body-md text-[color:var(--t-failed-fg)]">
                  <Icon name="error" size={16} className="mt-0.5" />
                  Invalid username or password
                </p>
              )}
              {failure === 'throttled' && (
                <div className="space-y-1 text-body-md">
                  <p role="alert" className="flex items-start gap-2 text-[color:var(--t-failed-fg)]">
                    <Icon name="error" size={16} className="mt-0.5" />
                    Too many failed attempts. Sign-in is paused.
                  </p>
                  <p data-testid="countdown" className="pl-6 text-fg-2">
                    {secondsLeft > 0 ? `Try again in ${formatCountdown(secondsLeft)}.` : 'You can try again now.'}
                  </p>
                </div>
              )}
              {failure === 'server' && (
                <p role="alert" className="flex items-start gap-2 text-body-md text-[color:var(--t-failed-fg)]">
                  <Icon name="error" size={16} className="mt-0.5" />
                  {GENERIC_ERROR}
                </p>
              )}
            </div>

            <Button type="submit" variant="primary" busy={busy} disabled={locked} className="h-9 w-full">
              {busy ? 'Signing in…' : 'Sign in'}
            </Button>
          </form>
        </div>
      </main>
      <footer className="flex items-center justify-between gap-2 px-4 py-3 text-body-sm text-muted">
        <span>RepoIntel v{__APP_VERSION__}</span>
        <ThemeToggle />
      </footer>
    </div>
  )
}

