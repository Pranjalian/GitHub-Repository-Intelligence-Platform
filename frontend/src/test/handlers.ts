import { http, HttpResponse } from 'msw'

import { aiProvidersDisabled } from './fixtures'

// Default handlers; individual tests add overrides with server.use(...).
export const handlers = [
  http.get('/api/v1/ping', () => HttpResponse.json({ ok: true })),
  // AI is off unless a test says otherwise (as in the default backend configuration)
  http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersDisabled)),
]
