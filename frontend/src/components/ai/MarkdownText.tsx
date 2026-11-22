import ReactMarkdown from 'react-markdown'

/**
 * AI-generated text is untrusted (it can echo repository content). It is rendered as Markdown with raw
 * HTML dropped (skipHtml), and without images or links: an image could load an external URL and a link
 * could point anywhere. Their text is kept. Evidence links come from the backend, never from the model.
 */
export default function MarkdownText({ text }: { text: string }) {
  return (
    <div className="ai-markdown space-y-2 text-sm leading-relaxed [&_code]:rounded [&_code]:bg-slate-100 [&_code]:px-1 [&_li]:ml-5 [&_ol]:list-decimal [&_pre]:overflow-x-auto [&_pre]:rounded [&_pre]:bg-slate-900 [&_pre]:p-2 [&_pre]:text-slate-100 [&_ul]:list-disc">
      <ReactMarkdown skipHtml disallowedElements={['img', 'a']} unwrapDisallowed>
        {text}
      </ReactMarkdown>
    </div>
  )
}
