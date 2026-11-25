import Icon from '../ui/icons'

/** A file location that links to GitHub at the snapshot's commit (the URL is built by the backend). */
export default function SourceLink({
  filePath,
  startLine,
  endLine,
  sourceUrl,
}: {
  filePath: string
  startLine: number | null
  endLine: number | null
  sourceUrl: string | null
}) {
  const lines = startLine == null ? '' : endLine != null && endLine !== startLine ? `:${startLine}-${endLine}` : `:${startLine}`
  const label = `${filePath}${lines}`
  if (!sourceUrl) {
    return <span className="font-mono text-body-sm">{label}</span>
  }
  return (
    <a href={sourceUrl} target="_blank" rel="noreferrer noopener" className="inline-flex items-center gap-1 break-all font-mono text-body-sm text-primary-text hover:underline">
      {label}
      <Icon name="external-link" size={11} />
    </a>
  )
}
