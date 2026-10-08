import { useEffect, useMemo, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";
import { Renderer } from "@openuidev/react-lang";
import { openuiChatLibrary } from "@openuidev/react-ui/genui-lib";
import { describeForm, looksLikeOpenUi, unwrapFence } from "./openuiDetect";
import { hostAction, hostOpenLink } from "./bridge";

type Props = { text: string; streaming?: boolean; interactive?: boolean };

function MarkdownBody({ text }: { text: string }) {
  return (
    <div className="md">
      <ReactMarkdown
        remarkPlugins={[remarkGfm]}
        components={{
          a: ({ href, children }) => (
            <a
              href={href}
              onClick={(e) => {
                e.preventDefault();
                if (href) hostOpenLink(href);
              }}
            >
              {children}
            </a>
          ),
          // No remote images inside the WebView (CSP blocks them anyway); show the link instead.
          img: ({ src, alt }) => (
            <a
              href={String(src ?? "")}
              onClick={(e) => {
                e.preventDefault();
                if (src) hostOpenLink(String(src));
              }}
            >
              🖼 {alt || "image"}
            </a>
          ),
        }}
      >
        {text}
      </ReactMarkdown>
    </div>
  );
}

/**
 * Bot message body: OpenUI when the reply is openui-lang, markdown otherwise.
 * A finished reply that fails to parse falls back to markdown (rendering rule 2).
 */
export function MessageBody({ text, streaming, interactive = true }: Props) {
  const code = useMemo(() => unwrapFence(text), [text]);
  const tryOpenUi = useMemo(() => looksLikeOpenUi(text), [text]);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    if (streaming) setFailed(false);
  }, [streaming]);

  if (!tryOpenUi || failed) return <MarkdownBody text={text} />;

  return (
    <div className="openui-root">
      <Renderer
        response={code}
        library={openuiChatLibrary}
        isStreaming={!!streaming}
        onParseResult={(result) => {
          if (streaming || !result) return;
          if (!result.root || result.meta.errors.length > 0) setFailed(true);
        }}
        onAction={(event) => {
          if (!interactive) return;
          if (event.type === "open_url") {
            const url = String((event.params as { url?: unknown })?.url ?? "");
            if (url) hostOpenLink(url);
            return;
          }
          const form = describeForm(event.formState);
          const label = event.humanFriendlyMessage || String((event.params as { context?: unknown })?.context ?? "");
          const text = form ? `${label}\n(${form})` : label;
          if (text.trim()) hostAction({ type: "message", text });
        }}
      />
    </div>
  );
}
