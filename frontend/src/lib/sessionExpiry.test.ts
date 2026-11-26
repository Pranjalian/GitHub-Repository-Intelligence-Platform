import { QueryClient } from '@tanstack/react-query'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi, type Mock } from 'vitest'
import { apiFetch } from '../api/client'
import { server } from '../test/server'
import { installSessionExpiry, resetSessionExpiry } from './sessionExpiry'

describe('session expiry', () => {
  let queryClient: QueryClient
  let navigate: Mock<(to: string) => void>
  let uninstall: () => void
  let here: string

  beforeEach(() => {
    resetSessionExpiry()
    queryClient = new QueryClient()
    navigate = vi.fn<(to: string) => void>()
    here = '/repositories/42?tab=quality'
    uninstall = installSessionExpiry(queryClient, navigate, () => here)
    server.use(http.get('/api/v1/repositories', () => HttpResponse.json({ status: 401 }, { status: 401 })))
  })

  afterEach(() => {
    uninstall()
    queryClient.clear()
  })

  it('on a 401 clears the cache, marks the session signed out and navigates to /login once', async () => {
    queryClient.setQueryData(['repositories', 0], { items: ['secret data of the old session'] })

    await apiFetch('/api/v1/repositories').catch(() => undefined)

    expect(queryClient.getQueryData(['repositories', 0])).toBeUndefined()
    expect(queryClient.getQueryData(['auth', 'session'])).toEqual({ authRequired: true, authenticated: false })
    expect(navigate).toHaveBeenCalledTimes(1)
    expect(navigate).toHaveBeenCalledWith('/login?expired=1&next=%2Frepositories%2F42%3Ftab%3Dquality')
  })

  it('handles a burst of 401s from requests already in flight as a single expiry', async () => {
    await Promise.all([1, 2, 3, 4].map(() => apiFetch('/api/v1/repositories').catch(() => undefined)))
    expect(navigate).toHaveBeenCalledTimes(1)
  })

  it('does not navigate when already on the login page', async () => {
    here = '/login?expired=1'
    await apiFetch('/api/v1/repositories').catch(() => undefined)
    expect(navigate).not.toHaveBeenCalled()
  })

  it('is re-armed by a new sign-in', async () => {
    await apiFetch('/api/v1/repositories').catch(() => undefined)
    resetSessionExpiry()
    await apiFetch('/api/v1/repositories').catch(() => undefined)
    expect(navigate).toHaveBeenCalledTimes(2)
  })

  it('never puts an unsafe location into next', async () => {
    here = '//evil.com/x'
    await apiFetch('/api/v1/repositories').catch(() => undefined)
    expect(navigate).toHaveBeenCalledWith('/login?expired=1&next=%2F')
  })

  it('is not triggered by the auth endpoints', async () => {
    server.use(http.get('/api/v1/auth/session', () => HttpResponse.json({ status: 401 }, { status: 401 })))
    await apiFetch('/api/v1/auth/session').catch(() => undefined)
    expect(navigate).not.toHaveBeenCalled()
  })
})
