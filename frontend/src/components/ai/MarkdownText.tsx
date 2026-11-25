import ReactMarkdown from 'react-markdown'
import CodeBlock from '../ui/CodeBlock'

/**
 * AI-generated text is untrusted (it can echo repository content). It is rendered as Markdown with raw
 * HTML dropped (skipHtml), and without images or links: an image could load an external URL and a link
 * could point anywhere. Their text is kept. Evidence links come from the backend, never from the model.
 * Fenced code becomes a CodeBlock (text nodes only); a fenced `diff` block gets red/green lines and a Copy button.
 */
export default function MarkdownText({ text }: { text: string }) {
  return (
    <div className="ai-markdown space-y-2 text-body-lg leading-relaxed [&_ol]:list-decimal [&_ol]:pl-5 [&_ul]:list-disc [&_ul]:pl-5">
      <ReactMarkdown
        skipHtml
        disallowedElements={['img', 'a']}
        unwrapDisallowed
        components={{
          pre: ({ children }) => <>{children}</>,
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
