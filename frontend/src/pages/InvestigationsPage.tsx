import { useParams } from 'react-router'
import { UnavailableState } from '../components/ui'

/**
 * Placeholder for the investigation screens (Step 7.13). They need the agent workflow of MVP 6
 * (investigation API, audit trail), which has not been built. The route exists so the navigation and old
 * links resolve, and the page says so honestly instead of showing mock data.
 */
export default function InvestigationsPage() {
  const { investigationId } = useParams()
  return (
    <div className="space-y-4">
      <h1 className="text-headline-lg font-semibold tracking-tight">Automated Code Investigations</h1>
      <UnavailableState
        icon="investigation"
        title="Investigations are not available yet"
        reason={
          investigationId
            ? 'This investigation cannot be shown: the investigation backend (MVP 6) is not part of this build.'
            : 'Multi-step investigations need the agent workflow engine (MVP 6), which is not part of this build. Code search, Q&A and impact analysis work as usual.'
        }
      />
    </div>
  )
}
