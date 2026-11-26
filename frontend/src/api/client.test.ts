import { afterEach, vi } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { ApiError, apiFetch, setUnauthorizedHandler } from './client'

describe('apiFetch', () => {
  it('returns parsed JSON on success', async () => {
    await expect(apiFetch<{ ok: boolean }>('/api/v1/ping')).resolves.toEqual({ ok: true })
  })

  it('parses the backend ProblemDetail into an ApiError', async () => {
    server.use(
      http.get('/api/v1/limited', () =>
        HttpResponse.json(
          { status: 429, detail: 'GitHub rate limit reached', requestId: 'req-123', retryAfterSeconds: 60 },
          { status: 429, headers: { 'Content-Type': 'application/problem+json' } },
        ),
      ),
    )
    const error = await apiFetch('/api/v1/limited').catch((e: unknown) => e)
    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({
      status: 429,
      message: 'GitHub rate limit reached',
      requestId: 'req-123',
      retryAfterSeconds: 60,
    })
  })

  it('exposes validation details', async () => {
    server.use(
      http.post('/api/v1/bad', () =>
        HttpResponse.json({ status: 400, detail: 'Request validation failed.', details: ['url: must not be blank'] }, { status: 400 }),
      ),
    )
    const error = (await apiFetch('/api/v1/bad', { method: 'POST' }).catch((e: unknown) => e)) as ApiError
    expect(error.details).toEqual(['url: must not be blank'])
  })

  it('falls back to a generic message for non-JSON errors', async () => {
    server.use(http.get('/api/v1/html', () => new HttpResponse('<html>Bad gateway</html>', { status: 502 })))
    const error = (await apiFetch('/api/v1/html').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(502)
    expect(error.message).toBe('Request failed with status 502')
  })

  it('reports an unreachable server', async () => {
    server.use(http.get('/api/v1/down', () => HttpResponse.error()))
    const error = (await apiFetch('/api/v1/down').catch((e: unknown) => e)) as ApiError
    expect(error.status).toBe(0)
  })
})

describe('apiFetch authentication behaviour', () => {
  afterEach(() => {
    setUnauthorizedHandler(null)
    document.cookie = 'XSRF-TOKEN=; Max-Age=0; Path=/'
  })

  function captureHeaders(method: 'get' | 'post' | 'put' | 'patch' | 'delete', path: string) {
    const seen: { csrf: string | null; credentials?: string } = { csrf: null }
    server.use(
      http[method](path, ({ request }) => {
        seen.csrf = request.headers.get('X-XSRF-TOKEN')
        seen.credentials = request.credentials
        return HttpResponse.json({ ok: true })
      }),
    )
    return seen
  }

  it('sends the CSRF header on state-changing methods only, from the XSRF-TOKEN cookie', async () => {
    document.cookie = 'XSRF-TOKEN=token-123; Path=/'
    for (const method of ['POST', 'PUT', 'PATCH', 'DELETE'] as const) {
      const seen = captureHeaders(method.toLowerCase() as 'post', '/api/v1/thing')
      await apiFetch('/api/v1/thing', { method })
      expect(seen.csrf, method).toBe('token-123')
    }
    const seen = captureHeaders('get', '/api/v1/thing')
    await apiFetch('/api/v1/thing')
    expect(seen.csrf, 'GET').toBeNull()
  })

  it('sends no header when there is no CSRF cookie yet', async () => {
    const seen = captureHeaders('post', '/api/v1/thing')
    await apiFetch('/api/v1/thing', { method: 'POST' })
    expect(seen.csrf).toBeNull()
  })

  it('runs the unauthorized handler for a 401 from a protected endpoint and still rejects', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    server.use(http.get('/api/v1/repositories', () => HttpResponse.json({ status: 401, detail: 'Authentication is required.' }, { status: 401 })))

    const error = (await apiFetch('/api/v1/repositories').catch((e: unknown) => e)) as ApiError

    expect(error.status).toBe(401)
    expect(handler).toHaveBeenCalledTimes(1)
  })

  it('never runs it for the auth endpoints (a wrong password is a 401 too)', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    server.use(http.post('/api/v1/auth/login', () => HttpResponse.json({ status: 401, detail: 'Invalid username or password' }, { status: 401 })))

    await apiFetch('/api/v1/auth/login', { method: 'POST' }).catch(() => undefined)

    expect(handler).not.toHaveBeenCalled()
  })

  it('does not run it for other errors', async () => {
    const handler = vi.fn()
    setUnauthorizedHandler(handler)
    server.use(http.get('/api/v1/forbidden', () => HttpResponse.json({ status: 403 }, { status: 403 })))
    await apiFetch('/api/v1/forbidden').catch(() => undefined)
    expect(handler).not.toHaveBeenCalled()
  })
})
