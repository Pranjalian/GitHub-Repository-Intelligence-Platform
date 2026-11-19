import { render, screen, within } from '@testing-library/react'
import { matrix } from '../../test/fixtures'
import CapabilityMatrix from './CapabilityMatrix'

describe('CapabilityMatrix', () => {
  it('renders UNSUPPORTED and UNAVAILABLE rows with their reasons', () => {
    render(<CapabilityMatrix rows={matrix} />)

    const unsupported = screen.getByRole('row', { name: /Rust/ })
    expect(within(unsupported).getByText('Unsupported')).toBeInTheDocument()
    expect(within(unsupported).getByText('Not supported')).toBeInTheDocument()
    expect(within(unsupported).getByText('no analyzer pack for Rust')).toBeInTheDocument()
    expect(within(unsupported).getByText('None')).toBeInTheDocument()

    const unavailable = screen.getByRole('row', { name: /semgrep/ })
    expect(within(unavailable).getByText('Unavailable')).toBeInTheDocument()
    expect(within(unavailable).getByText('semgrep is not installed')).toBeInTheDocument()
  })

  it('says so when there is nothing to report', () => {
    render(<CapabilityMatrix rows={[]} />)
    expect(screen.getByText('No capabilities to report for this snapshot.')).toBeInTheDocument()
  })
})
