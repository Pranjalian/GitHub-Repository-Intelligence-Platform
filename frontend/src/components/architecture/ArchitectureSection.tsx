import { useArchitecture } from '../../hooks/queries'
import ErrorState from '../ui/ErrorState'
import ArchitectureView from './ArchitectureView'

/** Loads and shows the architecture of one snapshot. */
export default function ArchitectureSection({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const architecture = useArchitecture(repositoryId, snapshotId)
  if (architecture.isPending) {
    return <p role="status">Loading architecture…</p>
  }
  if (architecture.error) {
    return <ErrorState title="Could not load the architecture" message={architecture.error.message} />
  }
  return <ArchitectureView report={architecture.data} />
}
