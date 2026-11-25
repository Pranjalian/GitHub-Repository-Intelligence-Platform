import { act, render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { applyTheme, ThemeProvider, useTheme } from './theme'

function Toggle() {
  const { choice, resolved, setChoice } = useTheme()
  return (
    <div>
      <span data-testid="state">{`${choice}/${resolved}`}</span>
      <button onClick={() => setChoice('light')}>light</button>
      <button onClick={() => setChoice('system')}>system</button>
    </div>
  )
}

function mockSystem(dark: boolean) {
  let listener: (() => void) | null = null
  const query = {
    matches: dark,
    addEventListener: (_: string, l: () => void) => (listener = l),
    removeEventListener: () => (listener = null),
  }
  vi.stubGlobal('matchMedia', () => query)
  return {
    flip() {
      query.matches = !query.matches
      listener?.()
    },
  }
}

describe('theme', () => {
  beforeEach(() => {
    window.localStorage.clear()
    document.documentElement.classList.remove('dark')
  })
  afterEach(() => vi.unstubAllGlobals())

  it('defaults to dark and applies the class', () => {
    mockSystem(false)
    render(
      <ThemeProvider>
        <Toggle />
      </ThemeProvider>,
    )
    expect(screen.getByTestId('state')).toHaveTextContent('dark/dark')
    expect(document.documentElement).toHaveClass('dark')
  })

  it('persists a choice and switches the class', async () => {
    mockSystem(true)
    render(
      <ThemeProvider>
        <Toggle />
      </ThemeProvider>,
    )
    await userEvent.click(screen.getByText('light'))
    expect(document.documentElement).not.toHaveClass('dark')
    expect(window.localStorage.getItem('repointel.theme')).toBe('light')
  })

  it('follows the system preference when set to system', async () => {
    const system = mockSystem(true)
    render(
      <ThemeProvider>
        <Toggle />
      </ThemeProvider>,
    )
    await userEvent.click(screen.getByText('system'))
    expect(screen.getByTestId('state')).toHaveTextContent('system/dark')
    act(() => system.flip())
    expect(screen.getByTestId('state')).toHaveTextContent('system/light')
    expect(document.documentElement).not.toHaveClass('dark')
  })

  it('applyTheme reads a stored choice before first render', () => {
    mockSystem(true)
    window.localStorage.setItem('repointel.theme', 'light')
    expect(applyTheme()).toBe('light')
    expect(document.documentElement).not.toHaveClass('dark')
  })
})
