import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { getSession, login, logout, type SessionState } from '../api/auth'
import { readCsrfToken } from '../api/client'
import { resetSessionExpiry } from '../lib/sessionExpiry'

export const SESSION_KEY = ['auth', 'session'] as const

/**
 * Who is calling, from `GET /api/v1/auth/session` (always 200). Fetched once and after login or logout; a
 * server-reported expiry (a 401 elsewhere) rewrites it to "signed out" without another request.
 * With `authRequired=false` (local use) nothing in the UI changes.
 */
export function useSession() {
  const query = useQuery({
    queryKey: SESSION_KEY,
    queryFn: async () => {
      const state = await getSession()
      if (state.authenticated) resetSessionExpiry()
      return state
    },
    staleTime: Infinity,
  })
  return {
    loading: query.isPending,
    failed: query.isError,
    retry: () => void query.refetch(),
    authRequired: query.data?.authRequired ?? false,
    authenticated: query.data?.authenticated ?? false,
    username: query.data?.username,
  }
}

export function useLogin() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: async ({ username, password }: { username: string; password: string }) => {
      // The CSRF cookie is issued by the session endpoint; if it is missing (cleared cookies), get one first.
      if (!readCsrfToken()) await getSession()
      return login(username, password)
    },
    onSuccess: (state: SessionState) => {
      resetSessionExpiry()
      queryClient.setQueryData(SESSION_KEY, state)
    },
  })
}

/** Signs out on the server, drops everything cached for the signed-in user, and re-reads the (signed-out) session. */
export function useLogout() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: logout,
    onSettled: async () => {
      await queryClient.cancelQueries()
      queryClient.clear()
      // Re-read the state: this also issues a fresh XSRF-TOKEN cookie for the next login.
      await queryClient.fetchQuery({ queryKey: SESSION_KEY, queryFn: getSession, staleTime: 0 }).catch(() => {
        queryClient.setQueryData(SESSION_KEY, { authRequired: true, authenticated: false })
      })
    },
  })
}
