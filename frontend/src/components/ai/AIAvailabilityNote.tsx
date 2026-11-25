import { Link } from 'react-router'
import { useAIProviders } from '../../hooks/queries'

/** One line above a list of AI actions: whether AI insights are available, and why not. */
export default function AIAvailabilityNote() {
  const providers = useAIProviders()
  if (!providers.data) {
    return null
  }
  if (providers.data.available) {
    return <p className="text-body-sm text-muted">AI explanations are available; every answer is marked AI-generated.</p>
  }
  return (
    <p role="note" className="text-body-sm text-muted">
      AI explanations are unavailable: {providers.data.unavailableReason ?? 'no provider is configured.'}{' '}
      <Link to="/settings/ai" className="text-primary-text underline">
        AI settings and setup guide
      </Link>
    </p>
  )
}
