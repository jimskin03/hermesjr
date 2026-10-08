import { useEffect, useMemo, useRef, useState } from "react";
import {
  ChatMessage,
  ChatSnapshot,
  getSnapshot,
  hostReady,
  hostSend,
  hostStop,
  subscribe,
  subscribeErrors,
} from "./bridgeHelpers";
import { MessageBody } from "./MessageBody";
import { stripUiFormat } from "./openuiDetect";

function avatarColor(name: string): string {
  const colors = ["#C08532", "#3FB950", "#58A6FF", "#BC8CFF", "#FF7B72", "#39C5CF", "#D29922", "#F778BA"];
  let h = 0;
  const s = name.toLowerCase();
  for (let i = 0; i < s.length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return colors[Math.abs(h) % colors.length];
}

function relativeTime(at?: number): string {
  if (!at || at <= 0) return "";
  const diff = Math.max(0, Date.now() - at) / 1000;
  if (diff < 45) return "now";
  if (diff < 3600) return `${Math.floor(diff / 60)}m`;
  if (diff < 86400) return `${Math.floor(diff / 3600)}h`;
  return new Date(at).toLocaleString(undefined, { weekday: "short", hour: "2-digit", minute: "2-digit" });
}

function Avatar({ name, size = 28 }: { name: string; size?: number }) {
  const color = avatarColor(name);
  const letter = (name.trim().replace(/^@/, "")[0] || "?").toUpperCase();
  return (
    <div
      className="avatar"
      style={{
        width: size,
        height: size,
        background: color + "38",
        color,
        fontSize: size * 0.45,
      }}
    >
      {letter}
    </div>
  );
}

function Bubble({
  msg,
  showHeader,
}: {
  msg: ChatMessage;
  showHeader: boolean;
}) {
  const mine = msg.role === "user";
  const text = mine ? stripUiFormat(msg.text) : msg.text;
  if (!text && msg.role !== "tool") return null;
  if (msg.role === "notice") {
    return (
      <div className="notice">
        <span>{msg.text}</span>
      </div>
    );
  }
  if (msg.role === "tool") {
    return (
      <div className="tool">
        ⚙ {msg.text.split("\n")[0].slice(0, 120)}
      </div>
    );
  }
  if (mine) {
    return (
      <div className={`row mine ${showHeader ? "headed" : ""}`}>
        <div className={`bubble user ${msg.pending ? "pending" : ""}`}>{text}</div>
        {(msg.pending || showHeader) && (
          <div className="meta">{msg.pending ? "sending…" : relativeTime(msg.at)}</div>
        )}
      </div>
    );
  }
  return (
    <div className={`row bot ${showHeader ? "headed" : ""}`}>
      {showHeader ? <Avatar name={msg.who} /> : <div className="avatar-spacer" />}
      <div className="col">
        {showHeader && (
          <div className="who">
            <span style={{ color: avatarColor(msg.who) }}>{msg.who}</span>
            {!!msg.at && <span className="time">{relativeTime(msg.at)}</span>}
          </div>
        )}
        <div className="bubble bot">
          <MessageBody text={text} streaming={!!msg.live} interactive={!msg.live} />
        </div>
      </div>
    </div>
  );
}

export function App() {
  const [snap, setSnap] = useState<ChatSnapshot>(getSnapshot);
  const [text, setText] = useState("");
  const [richUi, setRichUi] = useState(false);
  const [error, setError] = useState("");
  const bottomRef = useRef<HTMLDivElement>(null);
  const listRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let lastKey = "";
    const off = subscribe((s) => {
      setSnap(s);
      // Reset the Rich UI toggle only when a different chat/room opens, not on every update.
      const key = `${s.kind}:${s.title}`;
      if (key !== lastKey) {
        lastKey = key;
        setRichUi(s.richUiDefault);
      }
    });
    const offErr = subscribeErrors(setError);
    hostReady();
    return () => {
      off();
      offErr();
    };
  }, []);

  useEffect(() => {
    bottomRef.current?.scrollIntoView({ behavior: "smooth", block: "end" });
  }, [snap.messages.length, snap.streaming, snap.thinking.join(","), snap.activity, snap.reasoning.length]);

  const visible = useMemo(
    () => snap.messages.filter((m) => m.role !== "system" && (m.text || m.role === "notice")),
    [snap.messages],
  );

  function onSend() {
    const body = text.trim();
    if (!body) return;
    hostSend(body, richUi);
    setText("");
  }

  return (
    <div className="shell">
      <header className="top">
        <div className="title-row">
          {snap.kind === "chat" && snap.title ? <Avatar name={snap.title} size={26} /> : null}
          <div className="title">{snap.title || (snap.kind === "room" ? "Room" : "Chat")}</div>
          {snap.kind === "room" && snap.maxRounds != null && (
            <div className="pill">Rounds {snap.maxRounds}</div>
          )}
        </div>
        {snap.kind === "room" && (
          <div className="hint">An empty @ addresses everyone.</div>
        )}
      </header>

      {error ? <div className="banner">{error}</div> : null}

      <div className="list" ref={listRef}>
        {snap.kind === "room" && snap.messages.length === 0 && (
          <div className="notice"><span>No messages yet. Say hi — an empty @ addresses everyone.</span></div>
        )}
        {visible.map((msg, i) => {
          const prev = visible[i - 1];
          const grouped =
            !!prev &&
            prev.role === msg.role &&
            prev.who === msg.who &&
            (!msg.at || !prev.at || msg.at - prev.at < 5 * 60_000);
          return <Bubble key={msg.id} msg={msg} showHeader={!grouped} />;
        })}

        {snap.reasoning && (
          <div className="reasoning">{snap.reasoning.slice(0, 400)}{snap.reasoning.length > 400 ? "…" : ""}</div>
        )}
        {snap.activity && <div className="tool">Using {snap.activity}</div>}

        {snap.thinking.length > 0 && (
          <div className="typing">
            <Avatar name={snap.thinking[0]} size={22} />
            <span>
              {snap.thinking.length === 1
                ? `@${snap.thinking[0]} is thinking`
                : snap.thinking.length === 2
                  ? `@${snap.thinking[0]} and @${snap.thinking[1]} are thinking`
                  : `${snap.thinking.length} bots are thinking`}
            </span>
            <span className="dots"><i /><i /><i /></span>
          </div>
        )}
        {!snap.thinking.length && snap.working && !snap.streaming && (
          <div className="typing"><span>Working</span><span className="dots"><i /><i /><i /></span></div>
        )}
        <div ref={bottomRef} />
      </div>

      {snap.kind === "room" && snap.members && snap.members.length > 0 && (
        <div className="mentions">
          {snap.members.map((m) => (
            <button key={m.handle} type="button" onClick={() => setText((t) => `${t}@${m.handle} `)}>
              @{m.handle}
            </button>
          ))}
        </div>
      )}

      <footer className="composer">
        <div className="composer-row">
          <textarea
            value={text}
            onChange={(e) => setText(e.target.value)}
            placeholder={snap.kind === "room" ? "Message the room" : "Message"}
            rows={1}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                onSend();
              }
            }}
          />
          {snap.streaming || snap.working ? (
            <button type="button" className="stop" onClick={() => hostStop()}>Stop</button>
          ) : (
            <button type="button" className="send" onClick={onSend} disabled={!text.trim()}>Send</button>
          )}
        </div>
        <div className="composer-meta">
          <label className={`rich ${richUi ? "on" : ""}`}>
            <input type="checkbox" checked={richUi} onChange={(e) => setRichUi(e.target.checked)} />
            Rich UI
          </label>
          {snap.kind === "room" && (
            <span className="rich-note">Off by default here: others in the room see the prompt.</span>
          )}
        </div>
      </footer>
    </div>
  );
}
