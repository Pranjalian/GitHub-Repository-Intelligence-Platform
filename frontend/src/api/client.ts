/** RFC 9457 problem detail as returned by the backend GlobalExceptionHandler. */
export interface ProblemDetail {
  status: number
  title?: string
  detail?: string
  instance?: string
  timestamp?: string
  requestId?: string
  details?: string[]
  retryAfterSeconds?: number
  resetAt?: string
}

export class ApiError extends Error {
  readonly status: number
  readonly requestId?: string
  readonly details: string[]
  readonly retryAfterSeconds?: number
  readonly resetAt?: string

  constructor(status: number, message: string, problem?: ProblemDetail) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.requestId = problem?.requestId
    this.details = problem?.details ?? []
    this.retryAfterSeconds = problem?.retryAfterSeconds
    this.resetAt = problem?.resetAt
  }
}

async function toApiError(response: Response): Promise<ApiError> {
  let problem: ProblemDetail | undefined
  try {
    problem = (await response.json()) as ProblemDetail
  } catch {
    // Non-JSON error body (e.g. proxy error page): fall through to the generic message.
  }
  const message = problem?.detail ?? problem?.title ?? `Request failed with status ${response.status}`
  return new ApiError(response.status, message, problem)
}

const STATE_CHANGING = new Set(['POST', 'PUT', 'PATCH', 'DELETE'])
const AUTH_PATH = '/api/v1/auth/'

/** Called once when a protected endpoint answers 401 (see lib/sessionExpiry). Never called for the auth endpoints. */
let onUnauthorized: (() => void) | null = null

export function setUnauthorizedHandler(handler: (() => void) | null): void {
  onUnauthorized = handler
}

/**
 * The CSRF token the backend put in the readable XSRF-TOKEN cookie. It is not a secret from this origin (the
 * session cookie is the secret, and is HttpOnly); sending it back in a header proves the call came from our page.
 */
export function readCsrfToken(): string | null {
  const match = /(?:^|;\s*)XSRF-TOKEN=([^;]*)/.exec(document.cookie)
  return match ? decodeURIComponent(match[1]) : null
}

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  const method = (init?.method ?? 'GET').toUpperCase()
  const csrf = STATE_CHANGING.has(method) ? readCsrfToken() : null
  try {
    response = await fetch(path, {
      credentials: 'same-origin',
      ...init,
      headers: { Accept: 'application/json', ...(csrf ? { 'X-XSRF-TOKEN': csrf } : {}), ...init?.headers },
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Is the backend running?')
  }
  if (!response.ok) {
    if (response.status === 401 && !path.startsWith(AUTH_PATH)) {
      onUnauthorized?.()
    }
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
