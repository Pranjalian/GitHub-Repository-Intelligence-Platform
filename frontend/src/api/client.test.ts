import { http, HttpResponse } from 'msw'
import { server } from '../test/server'
import { ApiError, apiFetch } from './client'

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
