import { http, HttpResponse } from 'msw'

import { aiProvidersDisabled } from './fixtures'

// Default handlers; individual tests add overrides with server.use(...).
export const handlers = [
  http.get('/api/v1/ping', () => HttpResponse.json({ ok: true })),
  // AI is off unless a test says otherwise (as in the default backend configuration)
  http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersDisabled)),
  // shell and summary cards read these for every repository page; tests override them when they care
  http.get('/api/v1/repositories/stats', () =>
    HttpResponse.json({ repositoriesTotal: 0, repositoriesAnalyzed: 0, indexedForSearch: 0, openFindings: null, activeRuns: 0 }),
  ),
  http.get('/api/v1/repositories/:repositoryId/snapshots/:snapshotId/tests', () =>
    HttpResponse.json({ testClasses: null, testMethods: null, methodsByFramework: null, records: [], untestedClasses: [] }),
  ),
  http.get('/api/v1/repositories/:repositoryId/snapshots/:snapshotId/coverage', () =>
    HttpResponse.json({ status: 'NOT_ANALYZED', message: 'Coverage unavailable: no JaCoCo report found in the repository.', records: [] }),
  ),
]
