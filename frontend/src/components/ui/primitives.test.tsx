import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Accordion, CapabilityBadge, ConfidenceBadge, Drawer, HttpMethodBadge, ProviderBadge, RunStatusLabel, SegmentedControl, SeverityBadge, StatusLabel, Tabs, VulnerabilityStatusBadge } from '.'
import type { CapabilityLevel, Confidence, RunStatus, Severity, VulnerabilityStatus } from '../../types/api'
import type { ExecutionStatus } from '../../types/status'

describe('badges', () => {
  it.each(['CRITICAL', 'HIGH', 'MEDIUM', 'LOW', 'INFO'] as Severity[])('severity %s renders text and an icon', (severity) => {
    const { container } = render(<SeverityBadge severity={severity} />)
    expect(screen.getByText(new RegExp(`^${severity}$`, 'i'))).toBeInTheDocument()
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it.each([
    ['DETECTED', 'Detected'],
    ['LIKELY', 'Likely'],
    ['POSSIBLE', 'Possible'],
    ['NOT_VERIFIED', 'Not verified'],
  ] as [Confidence, string][])('confidence %s renders %s with an icon', (confidence, text) => {
    const { container } = render(<ConfidenceBadge confidence={confidence} />)
    expect(screen.getByText(text)).toBeInTheDocument()
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it('likely is dashed and detected is not (not colour alone)', () => {
    const { container: likely } = render(<ConfidenceBadge confidence="LIKELY" />)
    const { container: detected } = render(<ConfidenceBadge confidence="DETECTED" />)
    expect(likely.firstElementChild).toHaveClass('border-dashed')
    expect(detected.firstElementChild).not.toHaveClass('border-dashed')
  })

  it.each(['QUEUED', 'INGESTING', 'ANALYZING', 'COMPLETED', 'COMPLETED_WITH_WARNINGS', 'FAILED', 'CANCELLED'] as RunStatus[])('run status %s has text and icon', (status) => {
    const { container } = render(<RunStatusLabel status={status} />)
    expect(container.textContent?.length).toBeGreaterThan(2)
    expect(container.querySelector('svg')).not.toBeNull()
  })

  it.each(['NOT_CONFIGURED', 'NOT_SUPPORTED', 'UNAVAILABLE', 'NOT_ANALYZED'] as ExecutionStatus[])('%s is neutral, not an error', (status) => {
    const { container } = render(<StatusLabel status={status} />)
    expect(container.firstElementChild).toHaveClass('tone-neutral')
  })

  it.each(['DEEP', 'BASIC', 'GENERIC', 'UNSUPPORTED', 'UNAVAILABLE'] as CapabilityLevel[])('capability %s renders', (level) => {
    render(<CapabilityBadge level={level} />)
    expect(screen.getByText(new RegExp(level, 'i'))).toBeInTheDocument()
  })

  it('"Not checked" is never shown as clean', () => {
    render(<VulnerabilityStatusBadge status="NOT_CHECKED" />)
    expect(screen.getByText('Not checked')).toBeInTheDocument()
    expect(screen.queryByText(/no known/i)).toBeNull()
    render(<VulnerabilityStatusBadge status={'NEW_VALUE' as VulnerabilityStatus} />)
    expect(screen.getByText('NEW_VALUE')).toBeInTheDocument()
  })

  it('unknown enum values render a neutral fallback instead of throwing', () => {
    render(
      <>
        <SeverityBadge severity={'WEIRD' as Severity} />
        <ConfidenceBadge confidence={'WEIRD' as Confidence} />
        <RunStatusLabel status={'WEIRD' as RunStatus} />
        <StatusLabel status={'WEIRD' as ExecutionStatus} />
        <HttpMethodBadge method="TRACE" />
        <ProviderBadge kind="unavailable" />
      </>,
    )
    expect(screen.getAllByText('WEIRD').length).toBeGreaterThanOrEqual(3)
    expect(screen.getByText('TRACE')).toBeInTheDocument()
    expect(screen.getByText('AI unavailable')).toBeInTheDocument()
  })
})

describe('keyboard operable controls', () => {
  it('SegmentedControl moves with arrow keys and exposes radio semantics', async () => {
    const onChange = vi.fn()
    function Harness() {
      const [value, setValue] = useState('a')
      return (
        <SegmentedControl
          label="Mode"
          value={value}
          options={[
            { value: 'a', label: 'A' },
            { value: 'b', label: 'B' },
          ]}
          onChange={(v) => {
            onChange(v)
            setValue(v)
          }}
        />
      )
    }
    render(<Harness />)
    expect(screen.getByRole('radiogroup', { name: 'Mode' })).toBeInTheDocument()
    screen.getByRole('radio', { name: 'A' }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(onChange).toHaveBeenCalledWith('b')
    expect(screen.getByRole('radio', { name: 'B' })).toHaveAttribute('aria-checked', 'true')
  })

  it('Tabs expose tab roles, selection and arrow-key navigation', async () => {
    const onChange = vi.fn()
    render(
      <Tabs
        label="Sections"
        active="one"
        onChange={onChange}
        tabs={[
          { id: 'one', label: 'One', count: 3 },
          { id: 'two', label: 'Two' },
        ]}
      />,
    )
    expect(screen.getByRole('tab', { name: /One/ })).toHaveAttribute('aria-selected', 'true')
    screen.getByRole('tab', { name: /One/ }).focus()
    await userEvent.keyboard('{ArrowRight}')
    expect(onChange).toHaveBeenCalledWith('two')
  })

  it('Accordion toggles aria-expanded from the keyboard', async () => {
    render(<Accordion title="Evidence">body text</Accordion>)
    const button = screen.getByRole('button', { name: /Evidence/ })
    expect(button).toHaveAttribute('aria-expanded', 'false')
    button.focus()
    await userEvent.keyboard('{Enter}')
    expect(button).toHaveAttribute('aria-expanded', 'true')
    expect(screen.getByText('body text')).toBeInTheDocument()
  })
})

describe('Drawer', () => {
  it('closes on Escape and returns focus', async () => {
    const onClose = vi.fn()
    const trigger = document.createElement('button')
    document.body.appendChild(trigger)
    const { unmount } = render(
      <Drawer open overlay onClose={onClose} title="Inspector title" returnFocusTo={trigger}>
        <button>inside</button>
      </Drawer>,
    )
    expect(screen.getByRole('dialog', { name: 'Inspector' })).toHaveAttribute('aria-modal', 'true')
    await userEvent.keyboard('{Escape}')
    expect(onClose).toHaveBeenCalled()
    unmount()
    expect(document.activeElement).toBe(trigger)
    trigger.remove()
  })

  it('traps Tab inside the overlay sheet', async () => {
    render(
      <Drawer open overlay onClose={() => {}} title="T">
        <button>last</button>
      </Drawer>,
    )
    const close = screen.getByRole('button', { name: 'Close inspector' })
    const last = screen.getByRole('button', { name: 'last' })
    last.focus()
    await userEvent.tab()
    expect(close).toHaveFocus()
    await userEvent.tab({ shift: true })
    expect(last).toHaveFocus()
  })

  it('renders nothing when closed and a complementary landmark when docked', () => {
    const { rerender } = render(
      <Drawer open={false} onClose={() => {}} title="T">
        x
      </Drawer>,
    )
    expect(screen.queryByText('x')).toBeNull()
    rerender(
      <Drawer open onClose={() => {}} title="T">
        x
      </Drawer>,
    )
    expect(screen.getByRole('complementary')).toBeInTheDocument()
  })
})
