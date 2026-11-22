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
    return <span className="font-mono text-xs">{label}</span>
  }
  return (
    <a href={sourceUrl} target="_blank" rel="noreferrer noopener" className="break-all font-mono text-xs text-blue-700 hover:underline">
      {label}
    </a>
  )
}
