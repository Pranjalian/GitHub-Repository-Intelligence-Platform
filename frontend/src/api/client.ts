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

export async function apiFetch<T>(path: string, init?: RequestInit): Promise<T> {
  let response: Response
  try {
    response = await fetch(path, {
      ...init,
      headers: { Accept: 'application/json', ...init?.headers },
    })
  } catch {
    throw new ApiError(0, 'Cannot reach the server. Is the backend running?')
  }
  if (!response.ok) {
    throw await toApiError(response)
  }
  if (response.status === 204) {
    return undefined as T
  }
  return (await response.json()) as T
}
