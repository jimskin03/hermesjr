/**
 * Generates Hermes Jr's on-demand Rich UI instruction block from the real OpenUI chat
 * library (so component signatures can never drift from the renderer in the APK).
 *
 * The full chat-library prompt is ~13k tokens; this keeps a small allowed set
 * (Card, Table, List, Chart, Form, Button, Image + the pieces they need) and checks the
 * embedded example parses against the same library. Output: dist-prompt/ui-format.txt
 */
import { mkdirSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { createParser } from "@openuidev/react-lang";
import { openuiChatLibrary, openuiChatPromptOptions } from "@openuidev/react-ui/genui-lib";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const outDir = join(root, "dist-prompt");

const ALLOWED = [
  "Card", "CardHeader", "TextContent", "Callout",
  "Table", "Col",
  "ListBlock", "ListItem",
  "BarChart", "PieChart", "Series",
  "Form", "FormControl", "Input", "Select", "SelectItem",
  "Buttons", "Button",
  "Image", "FollowUpBlock", "FollowUpItem",
];

/** Drop rarely needed optional args so the block stays near the token budget. */
function simplify(sig) {
  return sig
    .replace(/, rules\?: \{[^}]*\}/g, "")
    .replace(/, value\?: \$binding<[^>]*>/g, "")
    .replace(/, visible\?: \$binding<[^>]*>/g, "")
    .replace(/, size\?: "[^)]*"(?=\))/g, "")
    .replace(/, type\?: "normal" \| "destructive"/g, "")
    .replace(/, (xLabel|yLabel)\?: string/g, "")
    .replace(/, height\?: number/g, "")
    .replace(/, appearance\?: "[^)]*"(?=\))/g, "")
    .replace(/, image\?: \{src: string, alt: string\}/g, "")
    .replace(/input: Input \| TextArea \| Select \| [^,]*/g, "input: Input | Select");
}

const full = openuiChatLibrary.prompt(openuiChatPromptOptions);
const lines = full.split("\n");

function signature(name) {
  const line = lines.find((l) => l.startsWith(`${name}(`));
  if (!line) throw new Error(`signature for ${name} not found in the chat library prompt`);
  // Card lists every child type; shorten it to the allowed set.
  let sig = line.split(" — ")[0];
  if (name === "Card") sig = "Card(children: (CardHeader | TextContent | Callout | Table | ListBlock | BarChart | PieChart | Form | Buttons | Image | FollowUpBlock)[])";
  return simplify(sig);
}

const example = [
  'root = Card([header, body, table, actions])',
  'header = CardHeader("Disk usage", "This computer")',
  'body = TextContent("Two volumes are above **80%**.")',
  'table = Table([c1, c2])',
  'c1 = Col("Volume", ["/", "/home"])',
  'c2 = Col("Used", ["84%", "91%"], "string")',
  'actions = FollowUpBlock([f1])',
  'f1 = FollowUpItem("How do I free space on /home?")',
].join("\n");

const parser = createParser(openuiChatLibrary.toJSONSchema(), "Card");
const parsed = parser.parse(example);
const errors = (parsed?.meta?.errors ?? parsed?.errors ?? []).filter(Boolean);
if (!parsed?.root || errors.length) {
  console.error("Example does not parse:", JSON.stringify(errors).slice(0, 800));
  process.exit(1);
}

const body = [
  "If a visual answer helps, reply ONLY in openui-lang (no markdown fences); otherwise use normal markdown. Never mix.",
  "Rules: one `name = Expression` per line; start with `root = Card([...])`; positional args only; double-quoted strings; every name except root must be used by a parent.",
  "",
  "Components (use only these):",
  ...ALLOWED.map((n) => `- ${signature(n)}`),
  "",
  'Buttons and FollowUpItem send their text back to you as the next user message. Links: Button("Open", Action([@OpenUrl("https://...")])).',
  "",
  "Example:",
  example,
].join("\n");

mkdirSync(outDir, { recursive: true });
writeFileSync(join(outDir, "ui-format.txt"), body + "\n");
const approxTokens = Math.ceil(body.length / 4);
console.log(`ui-format.txt: ${body.length} chars, ~${approxTokens} tokens (budget ~400-600)`);
