import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { render } from '@testing-library/react'
import type { ReactElement } from 'react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'

export function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, refetchOnWindowFocus: false } } })
}

/** Shows the current URL so tests can assert on navigation. */
export function LocationDisplay() {
  const location = useLocation()
  return <div data-testid="location">{location.pathname + location.search}</div>
}

/** Renders a page at a route inside the providers it needs. */
export function renderPage(
  element: ReactElement,
  { path = '/', initialEntry = '/', extraRoutes = null }: { path?: string; initialEntry?: string; extraRoutes?: ReactElement | null } = {},
) {
  return render(
    <QueryClientProvider client={makeQueryClient()}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path={path} element={element} />
          <Route path="*" element={null} />
        </Routes>
        {extraRoutes}
        <LocationDisplay />
      </MemoryRouter>
    </QueryClientProvider>,
  )
}
