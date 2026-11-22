import { useState } from 'react'
import Badge from '../components/ui/Badge'
import ErrorState from '../components/ui/ErrorState'
import { useAIProviders, useAIUsage } from '../hooks/queries'
import { readPreferredProvider, writePreferredProvider } from '../lib/aiPreference'
import { formatDateTime } from '../lib/format'
import type { AIProvider, AIUsageRecord, ProviderHealthStatus } from '../types/api'

const HEALTH_TONES: Record<ProviderHealthStatus, 'success' | 'danger' | 'warning' | 'neutral'> = {
  UP: 'success',
  DOWN: 'danger',
  NOT_CONFIGURED: 'warning',
  RATE_LIMITED: 'warning',
  UNKNOWN: 'neutral',
}

/** Optional AI: providers with health and data handling, limits, the preferred provider, and usage. */
export default function AISettingsPage() {
  const providers = useAIProviders()
  const [page, setPage] = useState(0)
  const usage = useAIUsage({ page, size: 25 })
  const [preferred, setPreferred] = useState<string | null>(() => readPreferredProvider())

  if (providers.isPending) {
    return <p role="status">Loading AI settings…</p>
  }
  if (providers.error) {
    return <ErrorState title="Could not load the AI settings" message={providers.error.message} />
  }
  const data = providers.data
  const permitted = data.providers.filter((p) => p.active && p.permitted)

  function choose(value: string) {
    const next = value === '' ? null : value
    writePreferredProvider(next)
    setPreferred(next)
  }

  return (
    <div className="space-y-8">
      <header className="space-y-2">
        <h2 className="text-xl font-semibold">AI settings</h2>
        <p className="text-sm text-slate-600">
          AI is optional. Every analysis result comes from deterministic tools; AI only explains them, and its answers are
          always marked as AI-generated with the evidence they were based on.
        </p>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          <Badge tone={data.enabled ? 'success' : 'neutral'}>{data.enabled ? 'AI enabled' : 'AI disabled'}</Badge>
          <Badge tone={data.available ? 'success' : 'warning'}>
            {data.available ? 'Insights available' : 'Insights unavailable'}
          </Badge>
          {data.unavailableReason && <span className="text-slate-700">{data.unavailableReason}</span>}
        </div>
      </header>

      {!data.available && <SetupGuide url={data.setupGuideUrl} />}

      <section aria-label="Providers" className="space-y-2">
        <h3 className="text-base font-semibold">Providers</h3>
        <div className="overflow-x-auto">
          <table className="min-w-full text-left text-sm">
            <thead className="text-xs text-slate-500">
              <tr>
                <th className="py-1 pr-4">Provider</th>
                <th className="py-1 pr-4">Data handling</th>
                <th className="py-1 pr-4">State</th>
                <th className="py-1 pr-4">Health</th>
                <th className="py-1 pr-4">Model</th>
                <th className="py-1 pr-4">Capabilities</th>
              </tr>
            </thead>
            <tbody>
              {data.providers.map((provider) => (
                <ProviderRow key={provider.id} provider={provider} isDefault={provider.id === data.defaultProvider} />
              ))}
            </tbody>
          </table>
        </div>
        {data.fallbackProviders.length > 0 && (
          <p className="text-xs text-slate-600">Fallback order: {data.fallbackProviders.join(' → ')}</p>
        )}
      </section>

      <section aria-label="Preferred provider" className="space-y-2">
        <h3 className="text-base font-semibold">Preferred provider</h3>
        <p className="text-xs text-slate-600">
          Stored in this browser only. Only enabled providers that are permitted (local, or hosted with acknowledged data
          handling) can be chosen; the server still checks every request.
        </p>
        <select
          aria-label="Preferred provider"
          value={preferred ?? ''}
          onChange={(event) => choose(event.target.value)}
          className="rounded border border-slate-300 bg-white px-2 py-1 text-sm"
        >
          <option value="">Server default ({data.defaultProvider ?? 'none'})</option>
          {permitted.map((provider) => (
            <option key={provider.id} value={provider.id}>
              {provider.id} ({provider.dataHandling === 'LOCAL' ? 'local' : 'hosted'})
            </option>
          ))}
        </select>
      </section>

      <section aria-label="Limits" className="space-y-2">
        <h3 className="text-base font-semibold">Limits</h3>
        <dl className="grid grid-cols-2 gap-2 text-sm sm:grid-cols-3 lg:grid-cols-6">
          {(
            [
              ['Max input tokens', data.limits.maxInputTokens],
              ['Max output tokens', data.limits.maxOutputTokens],
              ['Calls per analysis', data.limits.maxCallsPerAnalysis],
              ['Timeout (s)', data.limits.timeoutSeconds],
              ['Max retries', data.limits.maxRetries],
              ['Cache', data.cache.enabled ? `on, ${data.cache.ttl}` : 'off'],
            ] as [string, string | number][]
          ).map(([label, value]) => (
            <div key={label} className="rounded border border-slate-200 bg-white p-2">
              <dt className="text-xs text-slate-500">{label}</dt>
              <dd className="font-semibold">{value}</dd>
            </div>
          ))}
        </dl>
      </section>

      <section aria-label="Usage" className="space-y-2">
        <h3 className="text-base font-semibold">Usage</h3>
        <p className="text-xs text-slate-600">
          Tokens marked “est.” are local estimates ({data.limits.charsPerToken} characters per token); the provider did not
          report them. Cost appears only when pricing is configured.
        </p>
        {usage.error && <ErrorState title="Could not load the usage" message={usage.error.message} />}
        {usage.data && usage.data.items.length === 0 && <p className="text-sm text-slate-600">No AI calls yet.</p>}
        {usage.data && usage.data.items.length > 0 && (
          <>
            <div className="overflow-x-auto">
              <table className="min-w-full text-left text-sm">
                <thead className="text-xs text-slate-500">
                  <tr>
                    <th className="py-1 pr-4">When</th>
                    <th className="py-1 pr-4">Feature</th>
                    <th className="py-1 pr-4">Provider / model</th>
                    <th className="py-1 pr-4">Status</th>
                    <th className="py-1 pr-4">Input tokens</th>
                    <th className="py-1 pr-4">Output tokens</th>
                    <th className="py-1 pr-4">Cost</th>
                    <th className="py-1 pr-4">Latency</th>
                  </tr>
                </thead>
                <tbody>
                  {usage.data.items.map((record) => (
                    <UsageRow key={record.id} record={record} />
                  ))}
                </tbody>
              </table>
            </div>
            <div className="flex items-center gap-2 text-sm">
              <button type="button" disabled={page === 0} onClick={() => setPage(page - 1)} className="rounded border px-2 py-0.5 disabled:opacity-40">
                Previous
              </button>
              <span>
                Page {usage.data.page + 1} of {Math.max(1, usage.data.totalPages)}
              </span>
              <button
                type="button"
                disabled={page + 1 >= usage.data.totalPages}
                onClick={() => setPage(page + 1)}
                className="rounded border px-2 py-0.5 disabled:opacity-40"
              >
                Next
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  )
}

function ProviderRow({ provider, isDefault }: { provider: AIProvider; isDefault: boolean }) {
  const state = !provider.enabled
    ? 'disabled'
    : !provider.active
      ? 'enabled, AI off'
      : provider.permitted
        ? 'active'
        : 'not permitted (data handling not acknowledged)'
  return (
    <tr className="border-t border-slate-100 align-top">
      <td className="py-1 pr-4 font-medium">
        {provider.id}
        {isDefault && <span className="ml-1 text-xs text-slate-500">(default)</span>}
      </td>
      <td className="py-1 pr-4">
        {provider.dataHandling === 'LOCAL' ? (
          <Badge tone="success">Local</Badge>
        ) : (
          <Badge tone="warning">Hosted{provider.dataHandlingAcknowledged ? ', acknowledged' : ''}</Badge>
        )}
      </td>
      <td className="py-1 pr-4">{state}</td>
      <td className="py-1 pr-4">
        <Badge tone={HEALTH_TONES[provider.health.status]}>{provider.health.status.toLowerCase().replace('_', ' ')}</Badge>
        {provider.health.detail && <p className="text-xs text-slate-600">{provider.health.detail}</p>}
      </td>
      <td className="py-1 pr-4 font-mono text-xs">{provider.chatModel || '—'}</td>
      <td className="py-1 pr-4 text-xs text-slate-600">
        {provider.capabilities
          ? [
              provider.capabilities.supportsStructuredOutput ? 'structured output' : 'text only',
              provider.capabilities.maxContextTokens != null ? `${provider.capabilities.maxContextTokens} context tokens` : 'context size unknown',
              `from ${provider.capabilities.source.toLowerCase()}`,
            ].join(', ')
          : '—'}
      </td>
    </tr>
  )
}

function tokens(reported: number | null, estimated: number | null): string {
  if (reported != null) {
    return String(reported)
  }
  if (estimated != null) {
    return `${estimated} est.`
  }
  return '—'
}

function UsageRow({ record }: { record: AIUsageRecord }) {
  return (
    <tr className="border-t border-slate-100">
      <td className="py-1 pr-4 text-xs">{formatDateTime(record.createdAt)}</td>
      <td className="py-1 pr-4">{record.feature}</td>
      <td className="py-1 pr-4 text-xs">
        {record.provider} / {record.model ?? '—'}
      </td>
      <td className="py-1 pr-4">
        {record.cacheHit ? <Badge tone="info">cache hit</Badge> : <Badge tone={record.status === 'OK' ? 'success' : 'danger'}>{record.status}</Badge>}
        {record.detail && <p className="text-xs text-slate-600">{record.detail}</p>}
      </td>
      <td className="py-1 pr-4">{tokens(record.inputTokens, record.estimatedInputTokens)}</td>
      <td className="py-1 pr-4">{tokens(record.outputTokens, record.estimatedOutputTokens)}</td>
      <td className="py-1 pr-4">
        {record.estimatedCost != null
          ? `${record.estimatedCost} ${record.currency ?? ''}${record.costSource === 'ESTIMATED_TOKENS' ? ' (est.)' : ''}`
          : '—'}
      </td>
      <td className="py-1 pr-4 text-xs">{record.latencyMs != null ? `${record.latencyMs} ms` : '—'}</td>
    </tr>
  )
}

function SetupGuide({ url }: { url: string | null }) {
  return (
    <section aria-label="Setup guide" className="space-y-2 rounded border border-slate-200 bg-white p-4 text-sm">
      <h3 className="text-base font-semibold">How to enable AI insights</h3>
      <ol className="list-decimal space-y-1 pl-5">
        <li>
          Local (nothing leaves your machine): install Ollama, pull a model (for example <code>ollama pull llama3.2</code>),
          then set <code>AI_ENABLED=true</code>, <code>AI_PROVIDERS_OLLAMA_ENABLED=true</code> and{' '}
          <code>OLLAMA_CHAT_MODEL</code> in <code>.env</code> and restart the backend.
        </li>
        <li>
          Hosted (Gemini, OpenRouter): read the provider’s current terms, quotas and data-use policy first. Set the API key
          and model, then <code>AI_PROVIDERS_&lt;ID&gt;_DATA_HANDLING_ACKNOWLEDGED=true</code>. Repository excerpts (with
          secrets removed) are sent to that provider. No model is assumed to be free.
        </li>
        <li>See the README section “AI insights (optional)” for every setting.</li>
      </ol>
      {url && url.startsWith('https://') && (
        <p>
          <a href={url} target="_blank" rel="noreferrer" className="text-blue-700 hover:underline">
            Open the setup guide
          </a>
        </p>
      )}
    </section>
  )
}
