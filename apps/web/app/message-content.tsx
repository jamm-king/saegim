import Markdown from "react-markdown";
import { markdownPlugins } from "./markdown-plugins";

export default function MessageContent({ content }: { content: string }) {
  return <div className="message-markdown">
    <Markdown remarkPlugins={markdownPlugins} skipHtml components={{
      a: ({ children, href }) => href ? <a href={href} target="_blank" rel="noopener noreferrer">{children}</a> : <span>{children}</span>,
      pre: ({ children }) => <pre tabIndex={0}>{children}</pre>,
      table: ({ children }) => <div className="message-table" tabIndex={0} role="region" aria-label="메시지 표"><table>{children}</table></div>,
      // Message text should not fetch external images automatically.
      img: ({ alt }) => <span className="text-stone-500">[이미지: {alt || "설명 없음"}]</span>,
    }}>{content}</Markdown>
  </div>;
}
