import { screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
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
  it('explains that AI is disabled and shows the setup guide', async () => {
    stubUsage([])
    renderPage(<AISettingsPage />)

    expect(await screen.findByText('AI disabled')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Setup guide' })).toBeInTheDocument()
    expect(screen.getByText('No AI calls yet.')).toBeInTheDocument()
    const providers = screen.getByRole('region', { name: 'Providers' })
    expect(within(providers).getAllByText('Hosted')).toHaveLength(2)
    expect(within(providers).getByText('Local')).toBeInTheDocument()
  })

  it('lists providers with health and data handling, offers only permitted ones, and labels estimated tokens', async () => {
    server.use(http.get('/api/v1/ai/providers', () => HttpResponse.json(aiProvidersEnabled)))
    stubUsage([
      record({}),
      record({ id: 'u2', cacheHit: true, estimatedInputTokens: null, estimatedOutputTokens: null, usageSource: 'UNKNOWN' }),
      record({ id: 'u3', status: 'RATE_LIMITED', inputTokens: 100, outputTokens: 20, usageSource: 'REPORTED', detail: 'quota' }),
    ])
    renderPage(<AISettingsPage />)

    expect(await screen.findByText('Insights available')).toBeInTheDocument()
    const providers = screen.getByRole('region', { name: 'Providers' })
    expect(within(providers).getByText('Model llama3.2 is available.')).toBeInTheDocument()
    expect(within(providers).getByText('not permitted (data handling not acknowledged)')).toBeInTheDocument()

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
    expect(within(usage).getByText('100')).toBeInTheDocument()
  })
})
