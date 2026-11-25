import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const mermaid = vi.hoisted(() => ({ initialize: vi.fn(), run: vi.fn() }))
vi.mock('mermaid', () => ({ default: mermaid }))

import { MERMAID_CONFIG, mermaidConfig } from '../../lib/mermaid'
import { ThemeProvider, useTheme } from '../../lib/theme'
import MermaidDiagram from './MermaidDiagram'

const SOURCE = 'flowchart LR\n  n1["A"]\n  n2["B"]\n  n1 --> n2\n'

describe('MermaidDiagram', () => {
  beforeEach(() => {
    mermaid.run.mockReset()
  })

  it('renders with Mermaid in strict security mode, from text content', async () => {
    mermaid.run.mockResolvedValue(undefined)

    render(<MermaidDiagram title="Layers" source={SOURCE} />)

    await waitFor(() => expect(mermaid.run).toHaveBeenCalledTimes(1))
    expect(MERMAID_CONFIG.securityLevel).toBe('strict')
    expect(mermaid.initialize).toHaveBeenCalledWith(
      expect.objectContaining({ securityLevel: 'strict', startOnLoad: false, flowchart: { htmlLabels: false } }),
    )
    const node = mermaid.run.mock.calls[0][0].nodes[0] as HTMLElement
    expect(node).toBe(screen.getByTestId('mermaid-diagram'))
    expect(node.textContent).toBe(SOURCE)
    expect(screen.getByRole('figure', { name: 'Layers' })).toBeInTheDocument()
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })

  it('explains a rendering failure and keeps the source readable', async () => {
    mermaid.run.mockRejectedValue(new Error('Parse error on line 2'))

    render(<MermaidDiagram title="API flow" source={SOURCE} />)

    expect(await screen.findByRole('alert')).toHaveTextContent('Parse error on line 2')
    expect(screen.getByText('Mermaid source').closest('details')).toHaveTextContent('n1 --> n2')
  })

  it('re-renders with the matching theme when the theme changes, still in strict mode', async () => {
    mermaid.run.mockResolvedValue(undefined)
    mermaid.initialize.mockClear()
    function Switch() {
      const { setChoice } = useTheme()
      return <button onClick={() => setChoice('light')}>go light</button>
    }
    window.localStorage.setItem('repointel.theme', 'dark')
    render(
      <ThemeProvider>
        <Switch />
        <MermaidDiagram title="Layers" source={SOURCE} />
      </ThemeProvider>,
    )
    await waitFor(() => expect(mermaid.run).toHaveBeenCalledTimes(1))
    await userEvent.click(screen.getByText('go light'))
    await waitFor(() => expect(mermaid.run).toHaveBeenCalledTimes(2))
    const configs = mermaid.initialize.mock.calls.map((c) => c[0])
    expect(configs[0].themeVariables.primaryColor).toBe(mermaidConfig('dark').themeVariables?.primaryColor)
    expect(configs[configs.length - 1].themeVariables.primaryColor).toBe(mermaidConfig('light').themeVariables?.primaryColor)
    expect(configs.every((c) => c.securityLevel === 'strict')).toBe(true)
  })

  it('hostile label text stays text in the source and the strict config never changes between themes', () => {
    expect(mermaidConfig('light').securityLevel).toBe('strict')
    expect(mermaidConfig('dark').flowchart).toEqual({ htmlLabels: false })
    render(<MermaidDiagram title="Layers" source={'flowchart LR n1["<img src=x onerror=alert(1)>"]'} />)
    expect(document.querySelector('img')).toBeNull()
  })
})
