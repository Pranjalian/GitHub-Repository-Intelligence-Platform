import { Link } from 'react-router'
import { Button } from '../components/ui'
import EmptyState from '../components/ui/EmptyState'

export default function NotFoundPage() {
  return (
    <EmptyState
      icon="search"
      title="Page not found"
      description="This address does not match any page. Check the link, or go back to the repositories."
      action={
        <Link to="/">
          <Button variant="primary">Go to Repositories</Button>
        </Link>
      }
    />
  )
}
