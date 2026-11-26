import { apiFetch } from './client'

/** `GET /api/v1/auth/session` and the `POST /login` body. `username` is only present when signed in. */
export interface SessionState {
  authRequired: boolean
  authenticated: boolean
  username?: string
}

export const getSession = () => apiFetch<SessionState>('/api/v1/auth/session')

export const login = (username: string, password: string) =>
  apiFetch<SessionState>('/api/v1/auth/login', {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ username, password }),
  })

export const logout = () => apiFetch<void>('/api/v1/auth/logout', { method: 'POST' })
