import { createBrowserRouter } from 'react-router'
import MainLayout from '../components/layout/MainLayout'
import AddRepositoryPage from '../pages/AddRepositoryPage'
import AISettingsPage from '../pages/AISettingsPage'
import ArchitecturePage from '../pages/ArchitecturePage'
import DashboardPage from '../pages/DashboardPage'
import QAPage from '../pages/QAPage'
import RepositoryOverviewPage from '../pages/RepositoryOverviewPage'
import SearchPage from '../pages/SearchPage'

export const routes = [
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'repositories/new', element: <AddRepositoryPage /> },
      { path: 'repositories/:repositoryId', element: <RepositoryOverviewPage /> },
      { path: 'repositories/:repositoryId/arch', element: <ArchitecturePage /> },
      { path: 'repositories/:repositoryId/search', element: <SearchPage /> },
      { path: 'repositories/:repositoryId/qa', element: <QAPage /> },
      { path: 'settings/ai', element: <AISettingsPage /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
