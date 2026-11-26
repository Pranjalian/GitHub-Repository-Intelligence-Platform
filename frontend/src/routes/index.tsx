import { createBrowserRouter, type RouteObject } from 'react-router'
import RequireSession from '../components/auth/RequireSession'
import MainLayout from '../components/layout/MainLayout'

/** Pages are loaded per route so Mermaid (architecture, impact) is fetched only where diagrams are drawn. */
const page = (load: () => Promise<{ default: React.ComponentType }>): Pick<RouteObject, 'lazy'> => ({
  lazy: async () => ({ Component: (await load()).default }),
})

export const routes: RouteObject[] = [
  // The sign-in page is outside the shell: no sidebar, no header navigation.
  { path: '/login', ...page(() => import('../pages/LoginPage')) },
  {
    // Everything else sits behind the session guard (a no-op when the server requires no login).
    element: <RequireSession />,
    HydrateFallback: () => <p role="status" className="p-6 text-fg-2">Loading…</p>,
    children: [
      {
        path: '/',
        element: <MainLayout />,
        children: [
          { index: true, ...page(() => import('../pages/DashboardPage')) },
          { path: 'repositories/new', ...page(() => import('../pages/AddRepositoryPage')) },
          { path: 'repositories/:repositoryId', ...page(() => import('../pages/RepositoryOverviewPage')) },
          { path: 'repositories/:repositoryId/arch', ...page(() => import('../pages/ArchitecturePage')) },
          { path: 'repositories/:repositoryId/search', ...page(() => import('../pages/SearchPage')) },
          { path: 'repositories/:repositoryId/qa', ...page(() => import('../pages/QAPage')) },
          { path: 'repositories/:repositoryId/impact', ...page(() => import('../pages/ImpactAnalysisPage')) },
          { path: 'repositories/:repositoryId/investigations', ...page(() => import('../pages/InvestigationsPage')) },
          { path: 'investigations/:investigationId', ...page(() => import('../pages/InvestigationsPage')) },
          { path: 'investigations/:investigationId/audit', ...page(() => import('../pages/InvestigationsPage')) },
          { path: 'settings/ai', ...page(() => import('../pages/AISettingsPage')) },
          ...(import.meta.env.DEV ? [{ path: 'dev/components', ...page(() => import('../pages/dev/ComponentSheet')) }] : []),
          { path: '*', ...page(() => import('../pages/NotFoundPage')) },
        ],
      },
    ],
  },
]

export const router = createBrowserRouter(routes)
