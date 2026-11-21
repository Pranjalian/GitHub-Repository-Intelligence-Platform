import { createBrowserRouter } from 'react-router'
import MainLayout from '../components/layout/MainLayout'
import AddRepositoryPage from '../pages/AddRepositoryPage'
import ArchitecturePage from '../pages/ArchitecturePage'
import DashboardPage from '../pages/DashboardPage'
import RepositoryOverviewPage from '../pages/RepositoryOverviewPage'

export const routes = [
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'repositories/new', element: <AddRepositoryPage /> },
      { path: 'repositories/:repositoryId', element: <RepositoryOverviewPage /> },
      { path: 'repositories/:repositoryId/arch', element: <ArchitecturePage /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
