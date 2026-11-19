import { Link } from 'react-router'
import EmptyState from '../components/ui/EmptyState'

export default function DashboardPage() {
  return (
    <EmptyState
      title="No repositories analyzed yet"
      description="Submit a public GitHub repository to see its technology stack, structure and findings."
      action={
        <Link to="/repositories/new" className="rounded bg-slate-900 px-4 py-2 text-sm font-medium text-white">
          Add a repository
        </Link>
      }
    />
  )
}
