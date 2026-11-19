import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import AddRepositoryPage from './AddRepositoryPage'

describe('AddRepositoryPage', () => {
  it('renders the URL input', async () => {
    render(<AddRepositoryPage />)
    const input = screen.getByLabelText('Repository URL')
    expect(input).toBeInTheDocument()
    await userEvent.type(input, 'https://github.com/o/r')
    expect(input).toHaveValue('https://github.com/o/r')
  })
})
