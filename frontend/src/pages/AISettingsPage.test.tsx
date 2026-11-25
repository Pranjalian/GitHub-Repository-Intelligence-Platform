import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { AI_ENV } from '../lib/aiEnv'
import { aiProvidersEnabled } from '../test/fixtures'
import { server } from '../test/server'
import { renderPage } from '../test/utils'
import type { AIUsageRecord } from '../types/api'
import AISettingsPage from './AISettingsPage'

const record = (extra: Partial<AIUsageRecord>): AIUsageRecord => ({
  id: 'u1',
  createdAt: '2026-11-22T10:00:00Z',
  provider: 'ollama',
  model: 'llama3.2',
  feature: 'explain-class',
  promptVersion: 'explain-class.v1',
  repositoryId: null,
  snapshotId: null,
  runId: null,
  status: 'OK',
  cacheHit: false,
  inputTokens: null,
  outputTokens: null,
  estimatedInputTokens: 812,
  estimatedOutputTokens: 95,
  usageSource: 'ESTIMATED',
  estimatedCost: null,
  costSource: null,
  currency: null,
  latencyMs: 1500,
  detail: null,
  ...extra,
})

function stubUsage(items: AIUsageRecord[]) {
  server.use(
    http.get('/api/v1/ai/usage', () => HttpResponse.json({ items, page: 0, size: 25, totalElements: items.length, totalPages: 1 })),
  )
}

describe('AISettingsPage', () => {
  it('disabled variant: says deterministic analysis is unaffected, suspends the sections and shows the setup guide', async () => {
    stubUsage([])
    renderPage(<AISettingsPage />)

    expect(await screen.findByText('AI Insights: Disabled')).toBeInTheDocument()
    expect(screen.getByText('All deterministic analysis remains fully functional.')).toBeInTheDocument()
    expect(screen.getByText(/Suspended while AI is disabled/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Setup guide' })).toBeInTheDocument()
    expect(screen.getByText('No AI calls yet.')).toBeInTheDocument()
    expect(screen.getByText(/only triggered on demand/)).toBeInTheDocument()
    const providers = screen.getByRole('region', { name: 'Providers' })
    expect(within(providers).getAllByText('Hosted')).toHaveLength(2)
    expect(within(providers).getByText('Local')).toBeInTheDocument()
  })

  it('enabled variant: healthy banner, health and data handling per provider, only permitted ones offered, estimated vs reported tokens', async () => {
    server.use(http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)))
    stubUsage([
      record({}),
      record({ id: 'u2', cacheHit: true, estimatedInputTokens: null, estimatedOutputTokens: null, usageSource: 'UNKNOWN' }),
      record({ id: 'u3', status: 'RATE_LIMITED', inputTokens: 100, outputTokens: 20, usageSource: 'REPORTED', detail: 'quota' }),
    ])
    renderPage(<AISettingsPage />)

    expect(await screen.findByText('AI Insights: Enabled & Available')).toBeInTheDocument()
    expect(screen.getByText(/Nothing leaves your machine/)).toBeInTheDocument()
    expect(screen.getByText('Local-first active')).toBeInTheDocument()
    const providers = screen.getByRole('region', { name: 'Providers' })
    expect(within(providers).getAllByText('Model llama3.2 is available.').length).toBeGreaterThan(0) // detail line and tooltip
    expect(within(providers).getAllByText('Healthy').length).toBeGreaterThan(0)
    expect(within(providers).getByText('not permitted (data handling not acknowledged)')).toBeInTheDocument()
    expect(within(providers).getAllByText(/acknowledgement required/).length).toBeGreaterThan(0)

    const select = screen.getByRole('combobox', { name: 'Preferred provider' })
    const options = within(select).getAllByRole('option').map((o) => o.textContent)
    expect(options).toEqual(['Server default (ollama)', 'ollama (local)'])
    await userEvent.selectOptions(select, 'ollama')
    expect(window.localStorage.getItem('repointel.ai.preferredProvider')).toBe('ollama')
    window.localStorage.removeItem('repointel.ai.preferredProvider')

    const usage = screen.getByRole('region', { name: 'Usage' })
    expect(within(usage).getByText('812 est.')).toBeInTheDocument()
    expect(within(usage).getByText('cache hit')).toBeInTheDocument()
    expect(within(usage).getByText('RATE_LIMITED')).toBeInTheDocument()
    expect(within(usage).getByText(/^100/)).toHaveTextContent('100 reported')
  })

  it('shows the limits read-only and names the real call-limit variable; Re-check health refetches', async () => {
    let calls = 0
    server.use(
      http.get('/api/v1/ai/providers', () => {
        calls++
        return HttpResponse.json(aiProvidersEnabled)
      }),
    )
    stubUsage([])
    renderPage(<AISettingsPage />)
    const limits = await screen.findByRole('region', { name: 'Limits' })
    expect(limits).toHaveTextContent('Calls per analysis')
    expect(limits).toHaveTextContent('AI_MAX_CALLS_PER_ANALYSIS')
    expect(screen.queryByRole('button', { name: /Save/ })).toBeNull()
    await userEvent.click(screen.getByRole('button', { name: 'Re-check health' }))
    await waitFor(() => expect(calls).toBe(2))
  })

  it('the how-to uses the real environment variable names, which are the ones in the README', async () => {
    stubUsage([])
    renderPage(<AISettingsPage />)
    const guide = await screen.findByRole('region', { name: 'Setup guide' })
    for (const name of Object.values(AI_ENV)) {
      expect(guide.textContent ?? '').toContain(name === AI_ENV.maxCalls ? 'AI_PROVIDERS_' : name)
    }
    expect(guide.textContent).not.toContain('REPOINTEL_AI')
    expect(screen.getByRole('button', { name: 'Copy Quickstart' })).toBeInTheDocument()
    const readme = readFileSync(resolve(__dirname, '../../../README.md'), 'utf-8')
    for (const name of Object.values(AI_ENV)) expect(readme, name).toContain(name)
  })
})
