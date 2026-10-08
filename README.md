# Hermes Jr.

Hermes Jr. is a dark Android companion for one computer that is already running [Hermes](https://github.com/NousResearch/hermes-agent). The phone does not run the agent. It signs in to `hermes serve` and uses that gateway.

Application id: `com.nousresearch.hermes.jr`. Current version: 0.1.0. Android 8.0 (API 26) or newer.

## What the phone can do

Four tabs:

- **Chats.** Talk to one bot, or to a group room on the computer. Attach a file from the phone. Desktop group rooms that are only saved locally are shown read-only.
- **Bots.** Create and delete bots on the computer. Open MCP servers from a bot row: list, add, remove, enable, and test.
- **Screen.** Watch the computer's desktop. On Linux this is the Bot Desktop, drawn by the noVNC viewer that your computer serves (see [Screen viewer](#screen-viewer)). Take over is a separate button. Without the viewer, the tab shows a refreshing screenshot and says what to install. On other systems, screenshots still show up in the bot chat.
- **More.** The saved computer address, the Screen viewer address, Chat UI (OpenUI / Native), Rich UI default, sign-out, and the setup notes below.

While a bot is working, or the computer is waiting on you, a foreground notice stays up. Approval, sudo, secret, clarify, and vault prompts are cards. A swipe does not approve them.

## What it leaves out

No agent on the phone. No Hermes Cloud, SSH, fleet of gateways, skills hub, cron, messaging-channel admin, voice, artifacts, kanban, or light theme.

## Keep the gateway up

On the computer:

```bash
hermes serve --host 0.0.0.0 --port 9119
```

There is no `--bg` flag. A process started in a terminal dies when you log out. To survive reboot, supervise it.

**Linux.** A systemd user unit. On the phone, paste a username, password, and secret, then tap Copy. Edit opens the filled-in script if you need to change it, then Save & copy. The secret is the output of `openssl rand -base64 32` on the computer. The unit looks like this:

```ini
[Service]
EnvironmentFile=%h/.hermes/.env
ExecStart=%h/.local/bin/hermes serve --host 0.0.0.0 --port 9119
Restart=on-failure
```

Then `systemctl --user enable --now hermes-serve.service` and `loginctl enable-linger "$USER"`.

Park it with `systemctl --user stop hermes-serve.service`. `hermes serve --stop` sends SIGTERM, which systemd treats as a clean stop, so `Restart=on-failure` does not bring it back.

**macOS.** A LaunchAgent labeled `ai.hermes.serve`, with `RunAtLoad` and `KeepAlive`. Save it as `~/Library/LaunchAgents/ai.hermes.serve.plist`, then:

```bash
launchctl bootstrap gui/$UID ~/Library/LaunchAgents/ai.hermes.serve.plist
```

Park it with `launchctl bootout gui/$UID/ai.hermes.serve`. `KeepAlive` will start the process again after `hermes serve --stop`.

**Logout only.** `tmux new-session -d -s hermes -- hermes serve --host 0.0.0.0 --port 9119` survives closing the terminal. It does not survive a reboot.

**Windows.** Start the same command at logon from Task Scheduler, or from whatever already supervises the machine.

Put the basic-auth values in the environment the unit loads, not in the shell you happened to have open:

- `HERMES_DASHBOARD_BASIC_AUTH_USERNAME`
- `HERMES_DASHBOARD_BASIC_AUTH_PASSWORD`
- `HERMES_DASHBOARD_BASIC_AUTH_SECRET` (`openssl rand -base64 32`)

On a tailnet, bind `--host` to the computer's Tailscale address instead of `0.0.0.0`. An IPv6 address in the phone URL is bracketed: `http://[fd7a:115c:a1e0::1234]:9119`.

## Screen viewer

The app does not bundle noVNC. Like Friendly, it loads the viewer from the computer, served by `websockify --web=/usr/share/novnc`. The picture itself still goes through `hermes serve` (`/api/display/ws`, single-use ticket, input only while you hold the lease); the websockify port only serves the noVNC files and refuses every WebSocket.

On the computer (Debian/Ubuntu):

```bash
sudo apt install novnc websockify
websockify --web=/usr/share/novnc --heartbeat=30 \
  --token-plugin=TokenFile --token-source=/dev/null \
  "$(tailscale ip -4):6080"
```

Check it from another device on the tailnet: `http://<computer>:6080/vnc.html` should load the noVNC page.

`host/serve-novnc.sh` runs the same command and checks the install first. It binds to the Tailscale IPv4 when `tailscale` is present, else `0.0.0.0`; override with `NOVNC_BIND`, `NOVNC_PORT` (default 6080), and `NOVNC_WEB_ROOT` (default `/usr/share/novnc`). To keep it up, use the systemd user unit in `host/hermes-jr-novnc.service`; the install steps are at the top of that file. If your distribution has no `novnc` package, clone [noVNC](https://github.com/novnc/noVNC) and point `NOVNC_WEB_ROOT` at it (the folder must contain `vnc.html`), and `pipx install websockify`.

On the phone, **More → Screen viewer** says where to load it from. Blank means the computer you connected to, port 6080. You can enter a port (`6081`), `host:port`, or a URL such as an `https://` Tailscale Serve address. If the Screen tab says the computer is not serving the viewer, start it there and open the tab again.

The Screen tab adds two small hooks to the page it loads, as Friendly does: `app/ui.js` gets `globalThis.__novncUI = UI;` appended, and a short script hides noVNC's control bar and keeps it view-only until you tap Take over.

## Connect from the phone

Paste the computer's address, for example `http://192.168.100.57:9119`, and tap Connect, then Sign in.

The app refuses a few addresses before it will talk:

- `localhost`, `127.0.0.1`, `::1`, or `0.0.0.0`. That address is the phone.
- Plain HTTP to a public address.
- A gateway that is not asking anyone to sign in.
- A gateway without native sign-in. Update Hermes on the computer.
- A password sent to a public address. Use Tailscale, or OAuth, for anything off your own network.

Sign-in uses the system browser and a loopback redirect on the phone. After you sign in, the browser page sends you back to Hermes Jr.; if your browser blocks that, tap Open Hermes Jr. on the page. Use `http://` for a plain `hermes serve`; if you type `https://` and the computer only answers over HTTP on a private or Tailscale address, the app switches to `http://` and says so. Tokens stay in the Android Keystore. Sign out on the phone deletes those tokens only. There is no remote sign-out for one Jr. device. If the phone is lost and the computer uses a password, replace `HERMES_DASHBOARD_BASIC_AUTH_SECRET` and restart the supervised gateway. That ends every password session for the install, including the desktop. A password change alone does not.

## OpenUI chat

1:1 chats and rooms render in a WebView that loads only the bundled assets under `app/src/main/assets/openui/` (via `WebViewAssetLoader`). A restrictive CSP blocks network fetches from the page. Kotlin stays the source of truth: it pushes full chat snapshots over a small JS bridge (`HermesJrHost` / `HermesJrChat`) and handles send, stop, and form/button actions.

**Rich UI.** When the composer checkbox is on, the phone appends a compact OpenUI instruction block (`<ui-format>…</ui-format>`, about 540 tokens) to the outgoing text. The bot can then reply in openui-lang (cards, tables, forms). The phone strips that block from what you see in history. Rooms keep Rich UI **off by default**, because other clients (desktop) would otherwise see the prompt text. **More → Rich UI default** sets the 1:1 default.

**OpenUI / Native.** **More → Chat UI** switches between the WebView chat and the older Compose screens so you can compare them. The native screens will go away after Phase 4.

**Rebuild the web bundle** (optional; the built files are already committed, so installing the APK does not need Node):

```bash
cd web/openui-chat
npm ci
npm run build
```

That regenerates `app/src/main/assets/openui/` (split JS/CSS + `ui-format.txt` + `BUILD.txt`) from `@openuidev/react-ui` / `@openuidev/react-lang`. Source is under `web/openui-chat/`.

## Build

JDK 17 and an Android SDK. From this directory:

```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/Android/Sdk
./gradlew :app:assembleDebug
```

`local.properties` may set `sdk.dir`. That file is not committed.

The debug APK is `app/build/outputs/apk/debug/app-debug.apk`. Install it with `adb install -r` on a device. There is no emulator target in this repo.

Release builds minify and shrink resources.

## Layout

Kotlin and Jetpack Compose. AGP 9.4.1, Kotlin 2.4.10, Compose BOM 2026.09.00. Chat and rooms render in a WebView (see [OpenUI chat](#openui-chat)); auth, RPC, Screen, and settings stay native. The gateway speaks JSON-RPC over a ticketed WebSocket. The screen picture is drawn by the noVNC that the computer serves (see Screen viewer); no noVNC code ships in the APK. This repository is MIT; see `LICENSE`. `host/` holds the viewer script and systemd unit. `web/openui-chat/` holds the chat UI source.
