import { useState } from 'react'
import { useAIProviders } from '../../hooks/queries'
import { Select } from '../ui/Controls'
import Icon from '../ui/icons'
import InsightPanel from './InsightPanel'

/** Optional AI summary of one module's responsibility, from parser facts and findings (no source code). Visually lighter than the deterministic content. */
export default function ModuleSummaryPanel({
  repositoryId,
  snapshotId,
  modules,
}: {
  repositoryId: string
  snapshotId: string
  modules: string[]
}) {
  const [module, setModule] = useState(modules[0] ?? '')
  const providers = useAIProviders()
  if (modules.length === 0) {
    return null
  }
  const first = providers.data?.providers.find((p) => p.id === providers.data?.defaultProvider)
  const line = providers.data?.available && first
    ? `Generates a short summary using ${first.id} (${first.chatModel ?? 'model not set'}). Deterministic facts above are untouched.${first.dataHandling === 'LOCAL' ? ' Nothing leaves your machine.' : ' Evidence excerpts, with secrets removed, are sent to a hosted provider.'}`
    : 'AI is optional and off here. Deterministic facts above are unaffected.'
  return (
    <section aria-label="Module summary" className="ai-surface space-y-3 p-4">
      <div className="flex flex-wrap items-center gap-2">
        <Icon name="sparkle" size={18} className="text-ai-text" />
        <h3 className="text-headline-md font-semibold">Module Summary</h3>
        <span className="tone tone-ai rounded px-1.5 font-mono text-label-sm uppercase">AI-generated · optional</span>
      </div>
      <div className="flex flex-wrap items-center gap-3">
        <Select label="Module" value={module} onChange={(event) => setModule(event.target.value)}>
          {modules.map((path) => (
            <option key={path} value={path}>
              {path}
            </option>
          ))}
        </Select>
        <span className="font-mono text-body-sm text-fg-2">{modules.length} module(s)</span>
        <InsightPanel key={module} repositoryId={repositoryId} snapshotId={snapshotId} type="SUMMARIZE_MODULE" targetId={module} actionLabel="Summarize module" compact />
      </div>
      <p className="text-body-sm text-fg-2">{line}</p>
    </section>
  )
}
