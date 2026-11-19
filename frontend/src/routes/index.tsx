import { createBrowserRouter } from 'react-router'
import MainLayout from '../components/layout/MainLayout'
import AddRepositoryPage from '../pages/AddRepositoryPage'
import DashboardPage from '../pages/DashboardPage'

export const routes = [
  {
    path: '/',
    element: <MainLayout />,
    children: [
      { index: true, element: <DashboardPage /> },
      { path: 'repositories/new', element: <AddRepositoryPage /> },
    ],
  },
]

export const router = createBrowserRouter(routes)
