/** Shape of one chat line pushed from Kotlin. */
export type ChatMessage = {
  id: string;
  role: "user" | "bot" | "notice" | "tool" | "system";
  who: string;
  text: string;
  pending?: boolean;
  at?: number;
  /** Bot reply that is still streaming. */
  live?: boolean;
};

export type ChatSnapshot = {
  kind: "chat" | "room";
  title: string;
  messages: ChatMessage[];
  streaming: boolean;
  thinking: string[];
  activity: string;
  reasoning: string;
  richUiDefault: boolean;
  /** Room only. */
  members?: { handle: string; profile: string }[];
  maxRounds?: number;
  working?: boolean;
};

type HostApi = {
  send: (text: string, richUi: boolean) => void;
  stop: () => void;
  action: (payloadJson: string) => void;
  openLink: (url: string) => void;
  ready: () => void;
};

declare global {
  interface Window {
    /** Injected by Android (@JavascriptInterface). */
    HermesJrHost?: HostApi;
    /** Called by Kotlin via evaluateJavascript. */
    HermesJrChat?: {
      applySnapshot: (json: string) => void;
      applyDelta: (json: string) => void;
      setError: (message: string) => void;
    };
  }
}

type Listener = (snap: ChatSnapshot) => void;
type ErrListener = (msg: string) => void;

let snapshot: ChatSnapshot = {
  kind: "chat",
  title: "",
  messages: [],
  streaming: false,
  thinking: [],
  activity: "",
  reasoning: "",
  richUiDefault: false,
};
const listeners = new Set<Listener>();
const errListeners = new Set<ErrListener>();

export function subscribe(fn: Listener): () => void {
  listeners.add(fn);
  fn(snapshot);
  return () => listeners.delete(fn);
}

export function subscribeErrors(fn: ErrListener): () => void {
  errListeners.add(fn);
  return () => errListeners.delete(fn);
}

export function getSnapshot(): ChatSnapshot {
  return snapshot;
}

function publish() {
  for (const fn of listeners) fn(snapshot);
}

function parseSnap(json: string): ChatSnapshot {
  const raw = JSON.parse(json) as ChatSnapshot;
  return {
    kind: raw.kind === "room" ? "room" : "chat",
    title: raw.title || "",
    messages: Array.isArray(raw.messages) ? raw.messages : [],
    streaming: !!raw.streaming,
    thinking: Array.isArray(raw.thinking) ? raw.thinking : [],
    activity: raw.activity || "",
    reasoning: raw.reasoning || "",
    richUiDefault: !!raw.richUiDefault,
    members: raw.members,
    maxRounds: raw.maxRounds,
    working: !!raw.working,
  };
}

window.HermesJrChat = {
  applySnapshot(json: string) {
    try {
      snapshot = parseSnap(json);
      publish();
    } catch (e) {
      console.warn("bad snapshot", e);
    }
  },
  applyDelta(json: string) {
    // Full snapshots are the source of truth; a delta is just another snapshot.
    this.applySnapshot(json);
  },
  setError(message: string) {
    for (const fn of errListeners) fn(message || "");
  },
};

export function hostSend(text: string, richUi: boolean) {
  window.HermesJrHost?.send(text, richUi);
}

export function hostStop() {
  window.HermesJrHost?.stop();
}

export function hostAction(payload: unknown) {
  window.HermesJrHost?.action(JSON.stringify(payload));
}

export function hostOpenLink(url: string) {
  window.HermesJrHost?.openLink(url);
}

export function hostReady() {
  window.HermesJrHost?.ready();
}
