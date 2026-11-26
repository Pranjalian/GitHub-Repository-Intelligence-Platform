import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { ThemeProvider } from './lib/theme'
import { installSessionExpiry } from './lib/sessionExpiry'
import { router } from './routes'

const queryClient = new QueryClient({
  defaultOptions: { queries: { retry: 1, refetchOnWindowFocus: false } },
})

// A 401 from any protected call ends the session once and returns the user to /login (see lib/sessionExpiry).
installSessionExpiry(
  queryClient,
  (to) => void router.navigate(to, { replace: true }),
  () => router.state.location.pathname + router.state.location.search,
)

export default function App() {
  return (
    <ThemeProvider>
      <QueryClientProvider client={queryClient}>
        <RouterProvider router={router} />
      </QueryClientProvider>
    </ThemeProvider>
  )
}
