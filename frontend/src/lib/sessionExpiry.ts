import type { QueryClient } from '@tanstack/react-query'
import { setUnauthorizedHandler } from '../api/client'
import { safeNext } from './safeNext'

const SESSION_KEY = ['auth', 'session'] as const

let fired = false

/** A new sign-in (or a session read that says "signed in") re-arms the expiry handling. */
export function resetSessionExpiry(): void {
  fired = false
}

/**
 * When any protected endpoint answers 401 the session is gone (idle or absolute timeout, a server restart, a
 * password change). Handle it exactly once: cancel what is in flight, forget everything cached for the old
 * session, mark the session signed out, and go to `/login?expired=1&next=<where the user was>`. Further 401s from
 * requests that were already running are ignored, so there is no redirect loop and no pile of error messages.
 */
export function installSessionExpiry(
  queryClient: QueryClient,
  navigate: (to: string) => void,
  currentLocation: () => string,
): () => void {
  setUnauthorizedHandler(() => {
    if (fired) return
    fired = true
    void queryClient.cancelQueries()
    queryClient.clear()
    queryClient.setQueryData(SESSION_KEY, { authRequired: true, authenticated: false })
    const here = currentLocation()
    if (here === '/login' || here.startsWith('/login?')) return
    navigate(`/login?expired=1&next=${encodeURIComponent(safeNext(here))}`)
  })
  return () => setUnauthorizedHandler(null)
}
