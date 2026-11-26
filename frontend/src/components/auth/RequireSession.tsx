import { Navigate, Outlet, useLocation } from 'react-router'
import { useSession } from '../../hooks/useSession'
import { safeNext } from '../../lib/safeNext'
import { Button, ErrorState } from '../ui'

/**
 * Guards the application shell. This is the user-interface half of the login: the backend rejects unauthenticated
 * calls by itself, this only decides what to show.
 *
 * - while the session is being read: a neutral splash (neither the app nor the login page flashes);
 * - login required and not signed in: `/login?next=<where you were going>`;
 * - no login required (local use): the app, and no login UI anywhere.
 */
export default function RequireSession() {
  const session = useSession()
  const location = useLocation()

  if (session.loading) {
    return (
      <div role="status" className="grid min-h-screen place-items-center bg-canvas text-fg-2">
        <span>Loading…</span>
      </div>
    )
  }
  if (session.failed) {
    return (
      <div className="grid min-h-screen place-items-center bg-canvas p-4">
        <div className="w-full max-w-md">
          <ErrorState
            title="Cannot reach the server"
            message="The application could not check your session. Check that the backend is running, then try again."
            action={<Button onClick={session.retry}>Try again</Button>}
          />
        </div>
      </div>
    )
  }
  if (session.authRequired && !session.authenticated) {
    const next = safeNext(location.pathname + location.search)
    return <Navigate to={next === '/' ? '/login' : `/login?next=${encodeURIComponent(next)}`} replace />
  }
  return <Outlet />
}
