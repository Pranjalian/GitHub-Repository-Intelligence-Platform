import ReactMarkdown from 'react-markdown'
import CodeBlock from '../ui/CodeBlock'

interface HastNode {
  type: string
  value?: string
  tagName?: string
  properties?: Record<string, unknown>
  children?: HastNode[]
}

/** Replaces "[E2]" or "[2]" markers in text nodes with <sup data-cite="n">: the only place a marker can come from is the answer text. */
function citePlugin() {
  const split = (node: HastNode): void => {
    if (!node.children) return
    const next: HastNode[] = []
    for (const child of node.children) {
      if (child.type === 'text' && child.value && /\[E?\d{1,3}\]/.test(child.value)) {
        for (const piece of child.value.split(/(\[E?\d{1,3}\])/)) {
          const match = /^\[(E?\d{1,3})\]$/.exec(piece)
          if (match) next.push({ type: 'element', tagName: 'sup', properties: { dataCite: match[1] }, children: [{ type: 'text', value: piece }] })
          else if (piece) next.push({ type: 'text', value: piece })
        }
      } else {
        if (child.type === 'element' && child.tagName !== 'code') split(child)
        next.push(child)
      }
    }
    node.children = next
  }
  return (tree: HastNode) => split(tree)
}

/**
 * AI-generated text is untrusted (it can echo repository content). It is rendered as Markdown with raw
 * HTML dropped (skipHtml), and without images or links: an image could load an external URL and a link
 * could point anywhere. Their text is kept. Evidence links come from the backend, never from the model.
 * Fenced code becomes a CodeBlock (text nodes only); a fenced `diff` block gets red/green lines and a Copy button.
 * With `onCite`, "[n]" markers become buttons that report the reference number (Q&A citations).
 */
export default function MarkdownText({ text, onCite }: { text: string; onCite?: (ref: string) => void }) {
  return (
    <div className="ai-markdown space-y-2 text-body-lg leading-relaxed [&_ol]:list-decimal [&_ol]:pl-5 [&_ul]:list-disc [&_ul]:pl-5">
      <ReactMarkdown
        skipHtml
        disallowedElements={['img', 'a']}
        unwrapDisallowed
        rehypePlugins={onCite ? [citePlugin] : []}
        components={{
          pre: ({ children }) => <>{children}</>,
          sup: (props) => {
            const cite = (props as Record<string, unknown>)['data-cite'] as string | undefined
            if (!cite || !onCite) return <sup>{props.children}</sup>
            return (
              <button
                type="button"
                onClick={() => onCite(cite)}
                aria-label={`Source ${cite}`}
                className="mx-0.5 rounded bg-[color-mix(in_srgb,var(--c-ai)_20%,transparent)] px-1 align-baseline font-mono text-body-sm text-ai-text hover:underline"
              >
                [{cite}]
              </button>
            )
          },
          code: ({ className, children }) => {
            const raw = String(children ?? '')
            const language = /language-(\w+)/.exec(className ?? '')?.[1]
            if (!language && !raw.includes('\n')) {
              return <code className="rounded bg-raised px-1 font-mono text-code-md">{raw}</code>
            }
            return <CodeBlock code={raw} diff={language === 'diff'} lineNumbers={false} label={language} />
          },
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  )
}
