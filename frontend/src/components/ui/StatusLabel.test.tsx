import { render, screen } from '@testing-library/react'
import type { ExecutionStatus } from '../../types/status'
import StatusLabel from './StatusLabel'

const cases: [ExecutionStatus, string][] = [
  ['COMPLETED', 'Completed'],
  ['COMPLETED_WITH_WARNINGS', 'Completed with warnings'],
  ['NOT_CONFIGURED', 'Not configured'],
  ['NOT_SUPPORTED', 'Not supported'],
  ['UNAVAILABLE', 'Unavailable'],
  ['SKIPPED', 'Skipped'],
  ['FAILED', 'Failed'],
  ['TIMED_OUT', 'Timed out'],
  ['CANCELLED', 'Cancelled'],
  ['NOT_ANALYZED', 'Not analyzed'],
]

describe('StatusLabel', () => {
  it.each(cases)('renders %s as "%s"', (status, text) => {
    render(<StatusLabel status={status} />)
    expect(screen.getByText(text)).toBeInTheDocument()
  })
})
