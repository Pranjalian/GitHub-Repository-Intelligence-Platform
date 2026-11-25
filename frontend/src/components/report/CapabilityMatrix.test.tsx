import { render, screen, within } from '@testing-library/react'
import { matrix } from '../../test/fixtures'
import CapabilityMatrix from './CapabilityMatrix'

describe('CapabilityMatrix', () => {
  it('has all seven category columns, technology rows and a legend', () => {
    render(<CapabilityMatrix rows={matrix} />)
    const region = screen.getByRole('region', { name: 'Capability matrix' })
    for (const column of ['Technology', 'Structure', 'Dependencies', 'Code quality', 'Security', 'Tests', 'Architecture']) {
      expect(within(region).getAllByRole('columnheader', { name: column }).length).toBeGreaterThan(0)
    }
    expect(within(region).getByRole('rowheader', { name: 'Java' })).toBeInTheDocument()
    expect(screen.getByRole('list', { name: 'Legend' })).toHaveTextContent('Unsupported')
  })

  it('unsupported and unavailable cells carry their reason for hover and keyboard focus and are neutral, not red', () => {
    render(<CapabilityMatrix rows={matrix} />)
    const unsupported = screen.getByLabelText(/Rust, Code quality: unsupported/)
    expect(unsupported).toHaveAttribute('tabindex', '0')
    expect(unsupported).toHaveAttribute('aria-label', expect.stringContaining('no analyzer pack for Rust'))
    expect(unsupported).toHaveClass('tone-neutral')
    const unavailable = screen.getByLabelText(/Java, Security: basic/)
    expect(unavailable).toHaveAttribute('aria-label', expect.stringContaining('semgrep is not installed'))
    expect(unavailable.closest('span[aria-describedby]')).not.toBeNull()
  })

  it('says so when there is nothing to report', () => {
    render(<CapabilityMatrix rows={[]} />)
    expect(screen.getByText('No capabilities to report for this snapshot.')).toBeInTheDocument()
  })
})
