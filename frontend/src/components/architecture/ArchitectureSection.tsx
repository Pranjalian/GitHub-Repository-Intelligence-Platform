import { useArchitecture, useReport } from '../../hooks/queries'
import { Button } from '../ui'
import ErrorState from '../ui/ErrorState'
import { SkeletonRows } from '../ui/Layout'
import ArchitectureView from './ArchitectureView'

/** "Java 17 · Spring Boot 3.2.4" from the detected technologies. */
function stackLabel(technologies: { name: string; kind: string; version: string | null }[] | undefined): string | undefined {
  if (!technologies) return undefined
  const pick = (kind: string) => technologies.find((t) => t.kind === kind)
  const parts = [pick('LANGUAGE'), pick('FRAMEWORK')].filter(Boolean).map((t) => `${t!.name}${t!.version ? ` ${t!.version}` : ''}`)
  return parts.length ? parts.join(' · ') : undefined
}

/** Loads and shows the architecture of one snapshot. */
export default function ArchitectureSection({ repositoryId, snapshotId }: { repositoryId: string; snapshotId: string }) {
  const architecture = useArchitecture(repositoryId, snapshotId)
  const report = useReport(repositoryId, snapshotId, true)
  if (architecture.isPending) {
    return <SkeletonRows rows={3} label="Loading architecture" />
  }
  if (architecture.error) {
    return <ErrorState title="Could not load the architecture" message={architecture.error.message} action={<Button onClick={() => architecture.refetch()}>Retry</Button>} />
  }
  const base = `/repositories/${repositoryId}`
  const links = [
    { to: `${base}?snapshot=${snapshotId}`, label: 'Overview' },
    { to: `${base}?tab=dependencies&snapshot=${snapshotId}`, label: 'Dependencies' },
    { to: `${base}?tab=code-quality&snapshot=${snapshotId}`, label: 'Code Quality' },
    { to: `${base}?tab=security&snapshot=${snapshotId}`, label: 'Security' },
    { to: `${base}/search?snapshot=${snapshotId}`, label: 'Code Search' },
  ]
  return <ArchitectureView report={architecture.data} stackLabel={stackLabel(report.data?.technologies)} links={links} />
}
