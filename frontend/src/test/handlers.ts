import { http, HttpResponse } from 'msw'

// Default handlers; individual tests add overrides with server.use(...).
export const handlers = [
  http.get('/api/v1/ping', () => HttpResponse.json({ ok: true })),
]
