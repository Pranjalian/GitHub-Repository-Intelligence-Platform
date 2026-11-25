import { useState } from 'react'
import { Badge, Button, Card, CopyButton, DataTable, ErrorState, Icon, Pagination, ProviderBadge, SkeletonRows } from '../components/ui'
import { Select } from '../components/ui/Controls'
import { Tooltip } from '../components/ui/Controls'
import { useAIProviders, useAIUsage } from '../hooks/queries'
import { AI_ENV, HOSTED_ENV_LINES, LOCAL_ENV_LINES, OLLAMA_COMMANDS, QUICKSTART } from '../lib/aiEnv'
import { readPreferredProvider, writePreferredProvider } from '../lib/aiPreference'
import { formatDateTime } from '../lib/format'
import type { AIProvider, AIUsageRecord } from '../types/api'

const TAGS = (provider: AIProvider): string[] => {
  const c = provider.capabilities
  if (!c) return []
  return [...(c.supportsChat ? ['Explain', 'Refactor', 'Summarize', 'Q&A'] : []), ...(c.supportsEmbeddings ? ['Embeddings'] : [])]
}

function health(provider: AIProvider): { text: string; tone: 'success' | 'failed' | 'warning' | 'neutral' } {
  switch (provider.health.status) {
    case 'UP':
      return { text: 'Healthy', tone: 'success' }
    case 'DOWN':
      return { text: 'Down', tone: 'failed' }
    case 'RATE_LIMITED':
      return { text: 'Rate-limited', tone: 'warning' }
    case 'NOT_CONFIGURED':
      return { text: 'Unconfigured', tone: 'neutral' }
    default:
      return provider.apiKeyConfigured ? { text: 'Ready (API key)', tone: 'neutral' } : { text: 'Unconfigured', tone: 'neutral' }
  }
}

function tokens(reported: number | null, estimated: number | null) {
  if (reported != null) return <span>{reported} <span className="text-muted">reported</span></span>
  if (estimated != null) return <span>{estimated} est.</span>
  return <span className="text-muted">—</span>
}

function EnvBlock({ title, lines }: { title: string; lines: string[] }) {
  return (
    <div className="space-y-1">
      <h3 className="font-mono text-label-sm uppercase tracking-wider text-muted">{title}</h3>
      <ul className="space-y-1">
        {lines.map((line) => (
          <li key={line} className="flex items-center justify-between gap-2 rounded bg-code px-2 py-1 font-mono text-code-md">
            <code className="break-all">{line}</code>
            <CopyButton value={line} label="Copy line" compact />
          </li>
        ))}
      </ul>
    </div>
  )
}

/** Optional AI: status, providers with health and data handling, routing, limits, usage, and how to enable it. */
export default function AISettingsPage() {
  const providers = useAIProviders()
  const [page, setPage] = useState(0)
  const usage = useAIUsage({ page, size: 25 })
  const [preferred, setPreferred] = useState<string | null>(() => readPreferredProvider())

  if (providers.isPending) {
    return <SkeletonRows rows={3} label="Loading AI settings" />
  }
  if (providers.error) {
    return <ErrorState title="Could not load the AI settings" message={providers.error.message} action={<Button onClick={() => providers.refetch()}>Retry</Button>} />
  }
  const data = providers.data
  const permitted = data.providers.filter((p) => p.active && p.permitted)
  const def = data.providers.find((p) => p.id === data.defaultProvider)
  const local = def?.dataHandling === 'LOCAL'
  const dim = data.enabled ? '' : 'opacity-60'

  function choose(value: string) {
    const next = value === '' ? null : value
    writePreferredProvider(next)
    setPreferred(next)
  }

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-headline-lg font-semibold tracking-tight">AI settings</h1>
          <p className="max-w-3xl text-body-md text-fg-2">
            AI is optional. Every analysis result comes from deterministic tools; AI only explains them, and its answers are always marked as AI-generated with the evidence they were based on.
          </p>
        </div>
        <Button variant="secondary" icon="refresh" busy={providers.isFetching} onClick={() => providers.refetch()}>
          Re-check health
        </Button>
      </header>

      <section aria-label="AI status" className={`rounded-lg border p-4 ${data.available ? 'tone tone-success' : 'tone tone-neutral'}`}>
        <div className="flex flex-wrap items-center gap-2">
          <Icon name={data.available ? 'check-circle' : 'info'} size={18} />
          <h2 className="text-headline-md font-semibold text-fg">AI Insights: {data.enabled ? (data.available ? 'Enabled & Available' : 'Enabled, unavailable') : 'Disabled'}</h2>
          {data.available && <Badge tone="success">Healthy</Badge>}
          {data.available && local && <Badge tone="success" icon="monitor">Local-first active</Badge>}
        </div>
        <p className="mt-1 text-body-md text-fg">
          {!data.enabled
            ? 'All deterministic analysis remains fully functional.'
            : data.available
              ? local
                ? `Local ${def?.id} (${def?.chatModel}) is reachable. Nothing leaves your machine.`
                : `${def?.id} (${def?.chatModel}) is hosted: evidence excerpts, with secrets removed, are sent to it.`
              : (data.unavailableReason ?? 'No provider is available.')}
        </p>
        <p className="sr-only">{data.enabled ? 'AI enabled' : 'AI disabled'}. {data.available ? 'Insights available' : 'Insights unavailable'}</p>
      </section>

      {!data.available && (
        <section aria-label="Setup guide">
          <HowTo url={data.setupGuideUrl} />
        </section>
      )}

      <div className={`space-y-6 ${dim}`}>
        {!data.enabled && <p className="font-mono text-label-sm uppercase tracking-wider text-muted">Suspended while AI is disabled</p>}
        <section aria-label="Providers">
          <Card icon="database" title="Model providers">
            <DataTable
              caption="Model providers"
              rows={data.providers}
              rowKey={(p) => p.id}
              columns={[
                {
                  key: 'p',
                  header: 'Provider',
                  cell: (p) => (
                    <span className="font-medium">
                      {p.id}
                      {p.id === data.defaultProvider && <span className="ml-2"><Badge tone="primary">Default</Badge></span>}
                    </span>
                  ),
                },
                {
                  key: 's',
                  header: 'State',
                  cell: (p) => {
                    const state = !p.enabled ? 'disabled' : !p.active ? 'enabled, AI off' : p.permitted ? 'active' : 'not permitted (data handling not acknowledged)'
                    return <span className="text-body-sm">{state}</span>
                  },
                },
                {
                  key: 'h',
                  header: 'Health',
                  cell: (p) => {
                    const h = health(p)
                    return (
                      <span>
                        <Tooltip text={p.health.detail ?? h.text}>
                          <Badge tone={h.tone}>{h.text}</Badge>
                        </Tooltip>
                        {p.health.detail && <span className="block text-body-sm text-muted">{p.health.detail}</span>}
                      </span>
                    )
                  },
                },
                {
                  key: 'd',
                  header: 'Data handling',
                  cell: (p) =>
                    p.dataHandling === 'LOCAL' ? (
                      <span><ProviderBadge kind="local" /><span className="block text-body-sm text-muted">no external egress</span></span>
                    ) : (
                      <span>
                        <ProviderBadge kind="hosted" />
                        <span className="block text-body-sm text-muted">
                          excerpts sent, secrets scrubbed · {p.dataHandlingAcknowledged ? 'acknowledged' : 'acknowledgement required'}
                        </span>
                      </span>
                    ),
                },
                { key: 'm', header: 'Model', cell: (p) => <span className="font-mono text-body-sm">{p.chatModel || '—'}</span> },
                {
                  key: 'c',
                  header: 'Capabilities',
                  cell: (p) => (
                    <span className="flex flex-wrap gap-1">
                      {TAGS(p).map((t) => (
                        <Badge key={t} tone="neutral">{t}</Badge>
                      ))}
                      {TAGS(p).length === 0 && <span className="text-muted">—</span>}
                    </span>
                  ),
                },
              ]}
            />
            {data.fallbackProviders.length > 0 && <p className="mt-2 text-body-sm text-muted">Fallback order: {data.fallbackProviders.join(' → ')}</p>}
          </Card>
        </section>

        <section aria-label="Preferred provider">
          <Card icon="route" title="Routing policy">
            <p className="mb-2 text-body-sm text-muted">
              Stored in this browser only. Only enabled providers that are permitted (local, or hosted with acknowledged data handling) can be chosen; the server still checks every request. If no provider answers, the deterministic result stays as it is.
            </p>
            <Select label="Preferred provider" value={preferred ?? ''} onChange={(event) => choose(event.target.value)}>
              <option value="">Server default ({data.defaultProvider ?? 'none'})</option>
              {permitted.map((provider) => (
                <option key={provider.id} value={provider.id}>
                  {provider.id} ({provider.dataHandling === 'LOCAL' ? 'local' : 'hosted'})
                </option>
              ))}
            </Select>
          </Card>
        </section>

        <section aria-label="Limits">
          <Card icon="shield" title="Guardrails & budget limits" meta="Read-only: set by environment variables">
            <dl className="grid grid-cols-2 gap-2 sm:grid-cols-3 lg:grid-cols-6">
              {(
                [
                  ['Calls per analysis', data.limits.maxCallsPerAnalysis],
                  ['Max input tokens', data.limits.maxInputTokens],
                  ['Max output tokens', data.limits.maxOutputTokens],
                  ['Timeout (s)', data.limits.timeoutSeconds],
                  ['Max retries', data.limits.maxRetries],
                  ['Cache', data.cache.enabled ? `on, ${data.cache.ttl}` : 'off'],
                ] as [string, string | number][]
              ).map(([label, value]) => (
                <div key={label} className="rounded-lg border border-line bg-canvas p-2">
                  <dt className="font-mono text-label-sm uppercase tracking-wider text-muted">{label}</dt>
                  <dd className="font-semibold">{value}</dd>
                </div>
              ))}
            </dl>
            <p className="mt-2 text-body-sm text-muted">
              The call limit is <code className="font-mono">{AI_ENV.maxCalls}</code>.
            </p>
          </Card>
        </section>

        <section aria-label="Usage">
          <Card icon="clock" title="Usage telemetry">
            <p className="mb-2 text-body-sm text-muted">
              Tokens marked &quot;est.&quot; are local estimates ({data.limits.charsPerToken} characters per token); the provider did not report them. Cost appears only when pricing is configured.
            </p>
            {usage.error && <ErrorState title="Could not load the usage" message={usage.error.message} />}
            {usage.data && usage.data.items.length === 0 && (
              <div>
                <p className="text-body-md font-medium">No AI calls yet.</p>
                <p className="text-body-sm text-muted">AI insights are only triggered on demand. Deterministic analysis runs without AI.</p>
              </div>
            )}
            {usage.data && usage.data.items.length > 0 && (
              <div className="space-y-2">
                <DataTable
                  caption="AI usage"
                  rows={usage.data.items}
                  rowKey={(r: AIUsageRecord) => r.id}
                  columns={[
                    { key: 'w', header: 'When', cell: (r) => <span className="text-body-sm">{formatDateTime(r.createdAt)}</span> },
                    { key: 'f', header: 'Feature', cell: (r) => r.feature },
                    { key: 'pm', header: 'Provider / model', cell: (r) => <span className="font-mono text-body-sm">{r.provider} / {r.model ?? '—'}</span> },
                    {
                      key: 's',
                      header: 'Status',
                      cell: (r) => (
                        <span>
                          {r.cacheHit ? <Badge tone="info">cache hit</Badge> : <Badge tone={r.status === 'OK' ? 'success' : 'failed'}>{r.status}</Badge>}
                          {r.detail && <span className="block text-body-sm text-muted">{r.detail}</span>}
                        </span>
                      ),
                    },
                    { key: 'i', header: 'Input tokens', cell: (r) => tokens(r.inputTokens, r.estimatedInputTokens) },
                    { key: 'o', header: 'Output tokens', cell: (r) => tokens(r.outputTokens, r.estimatedOutputTokens) },
                    { key: 'l', header: 'Latency', cell: (r) => (r.latencyMs != null ? `${r.latencyMs} ms` : '—') },
                    {
                      key: 'c',
                      header: 'Cost',
                      cell: (r) => (r.estimatedCost != null ? `${r.estimatedCost} ${r.currency ?? ''}${r.costSource === 'ESTIMATED_TOKENS' ? ' (est.)' : ''}` : '—'),
                    },
                  ]}
                />
                <Pagination page={usage.data.page} totalPages={usage.data.totalPages} totalElements={usage.data.totalElements} shown={usage.data.items.length} onPage={setPage} />
              </div>
            )}
          </Card>
        </section>
      </div>

      {data.available && (
        <section aria-label="How to enable AI">
          <HowTo url={data.setupGuideUrl} />
        </section>
      )}
    </div>
  )
}

function HowTo({ url }: { url: string | null }) {
  return (
    <Card icon="key" title="How to enable AI insights" actions={<CopyButton value={QUICKSTART} label="Copy Quickstart" labelOnly />}>
      <div className="grid gap-4 md:grid-cols-2">
        <div className="space-y-3">
          <EnvBlock title="Local (nothing leaves your machine): CLI" lines={OLLAMA_COMMANDS} />
          <p className="text-body-sm text-muted">Pull any chat model you like; nothing is assumed.</p>
        </div>
        <EnvBlock title="Local: environment variables (.env)" lines={LOCAL_ENV_LINES} />
      </div>
      <div className="mt-4 space-y-2">
        <EnvBlock title="Hosted (Gemini or OpenRouter): environment variables" lines={HOSTED_ENV_LINES} />
        <p className="text-body-sm text-muted">
          Read the provider&apos;s current terms, quotas and data-use policy first. Hosted providers need the acknowledgement variable (
          <code className="font-mono">AI_PROVIDERS_&lt;ID&gt;_DATA_HANDLING_ACKNOWLEDGED=true</code>); repository excerpts, with secrets removed, are sent. No model is assumed to be free.
        </p>
        <p className="text-body-sm text-fg-2">See the README section &quot;AI insights (optional)&quot; for every setting.</p>
        {url && url.startsWith('https://') && (
          <p>
            <a href={url} target="_blank" rel="noreferrer" className="text-primary-text hover:underline">
              Open the setup guide
            </a>
          </p>
        )}
      </div>
    </Card>
  )
}
