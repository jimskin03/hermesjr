// Reproducible build of the OpenUI chat page into the Android assets folder.
// Usage: cd web/openui-chat && npm ci && npm run build
import { spawnSync } from "node:child_process";
import { cpSync, mkdirSync, rmSync, readdirSync, statSync, writeFileSync } from "node:fs";
import { dirname, join, relative } from "node:path";
import { fileURLToPath } from "node:url";
import { createHash } from "node:crypto";
import { readFileSync } from "node:fs";

const root = join(dirname(fileURLToPath(import.meta.url)), "..");
const assets = join(root, "..", "..", "app", "src", "main", "assets", "openui");

function run(cmd, args) {
  const r = spawnSync(cmd, args, { cwd: root, stdio: "inherit" });
  if (r.status !== 0) process.exit(r.status ?? 1);
}

run("node", ["scripts/gen-prompt.mjs"]);
run("npx", ["vite", "build"]);

rmSync(assets, { recursive: true, force: true });
mkdirSync(assets, { recursive: true });
cpSync(join(root, "dist"), assets, { recursive: true });
cpSync(join(root, "dist-prompt", "ui-format.txt"), join(assets, "ui-format.txt"));

function walk(dir) {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name);
    return statSync(full).isDirectory() ? walk(full) : [full];
  });
}
const files = walk(assets).filter((f) => !f.endsWith("BUILD.txt")).sort();
const lines = files.map((f) => {
  const bytes = readFileSync(f);
  const sha = createHash("sha256").update(bytes).digest("hex").slice(0, 16);
  return `${relative(assets, f)}  ${bytes.length}  sha256:${sha}`;
});
const pkg = JSON.parse(readFileSync(join(root, "package.json"), "utf8"));
writeFileSync(
  join(assets, "BUILD.txt"),
  [
    "Hermes Jr OpenUI chat bundle (generated; do not edit by hand)",
    `source: web/openui-chat  (@openuidev/react-ui ${pkg.dependencies["@openuidev/react-ui"]}, @openuidev/react-lang ${pkg.dependencies["@openuidev/react-lang"]})`,
    "rebuild: cd web/openui-chat && npm ci && npm run build",
    "",
    ...lines,
    "",
  ].join("\n"),
);
console.log(`Installed ${files.length} files into ${relative(join(root, "..", ".."), assets)}`);
