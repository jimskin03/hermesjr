/** Unwrap a reply that is one fenced code block (```openui / ```openui-lang / ```) around openui-lang. */
export function unwrapFence(text: string): string {
  const m = text.trim().match(/^```[a-zA-Z-]*\s*\n([\s\S]*?)\n```$/);
  return m ? m[1] : text;
}

/** Heuristic: openui-lang starts with assignments like `root = Card(...)`. Plain prose is markdown. */
export function looksLikeOpenUi(text: string): boolean {
  const t = unwrapFence(text).trim();
  if (t.length < 8) return false;
  if (/^root\s*=\s*[A-Z][A-Za-z0-9_]*\s*\(/m.test(t)) return true;
  const assigns = t.match(/^[A-Za-z_]\w*\s*=\s*[A-Z][A-Za-z0-9_]*\s*\(/gm);
  return !!assigns && assigns.length >= 2 && /^[A-Za-z_]\w*\s*=/.test(t);
}

/** Strip the client-attached prompt block from displayed user text (rooms echo it back from the server). */
export function stripUiFormat(text: string): string {
  return text
    .replace(/\n*<ui-format>[\s\S]*?<\/ui-format>\s*$/i, "")
    .replace(/\n*<ui-format>[\s\S]*$/i, "")
    .trimEnd();
}

/** Summarize submitted form fields for the follow-up user message. */
export function describeForm(formState: Record<string, unknown> | undefined): string {
  if (!formState) return "";
  const parts: string[] = [];
  for (const [form, fields] of Object.entries(formState)) {
    if (form.startsWith("$") || !fields || typeof fields !== "object") continue;
    for (const [name, field] of Object.entries(fields as Record<string, unknown>)) {
      const value = field && typeof field === "object" && "value" in (field as object) ? (field as { value: unknown }).value : field;
      if (value === undefined || value === null || value === "") continue;
      parts.push(`${name}: ${typeof value === "string" ? value : JSON.stringify(value)}`);
    }
  }
  return parts.join("; ");
}
