# Hermes Jr.

Hermes Jr. is a dark Android companion for one computer that is already running [Hermes](https://github.com/NousResearch/hermes-agent). The phone does not run the agent. It signs in to `hermes serve` and uses that gateway.

Application id: `com.nousresearch.hermes.jr`. Current version: 0.1.0. Android 8.0 (API 26) or newer.

## What the phone can do

Four tabs:

- **Chats.** Talk to one bot, or to a group room on the computer. Attach a file from the phone. Desktop group rooms that are only saved locally are shown read-only.
- **Bots.** Create and delete bots on the computer. Open MCP servers from a bot row: list, add, remove, enable, and test.
- **Screen.** Watch the computer's desktop. On Linux this is the Bot Desktop over a loopback picture. Take over is a separate button. On other systems, screenshots still show up in the bot chat.
- **More.** The saved computer address, sign-out, and the setup notes below.

While a bot is working, or the computer is waiting on you, a foreground notice stays up. Approval, sudo, secret, clarify, and vault prompts are cards. A swipe does not approve them.

## What it leaves out

No agent on the phone. No Hermes Cloud, SSH, fleet of gateways, skills hub, cron, messaging-channel admin, voice, artifacts, kanban, or light theme.

## Keep the gateway up

On the computer:

```bash
hermes serve --host 0.0.0.0 --port 9119
```

There is no `--bg` flag. A process started in a terminal dies when you log out. To survive reboot, supervise it.

**Linux.** A systemd user unit. The app's Connect screen has the full unit to copy. The shape is:

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

## Connect from the phone

Paste the computer's address, for example `http://192.168.100.57:9119`, and tap Connect, then Sign in.

The app refuses a few addresses before it will talk:

- `localhost`, `127.0.0.1`, `::1`, or `0.0.0.0`. That address is the phone.
- Plain HTTP to a public address.
- A gateway that is not asking anyone to sign in.
- A gateway without native sign-in. Update Hermes on the computer.
- A password sent to a public address. Use Tailscale, or OAuth, for anything off your own network.

Sign-in uses the system browser and a loopback redirect on the phone. Tokens stay in the Android Keystore. Sign out on the phone deletes those tokens only. There is no remote sign-out for one Jr. device. If the phone is lost and the computer uses a password, replace `HERMES_DASHBOARD_BASIC_AUTH_SECRET` and restart the supervised gateway. That ends every password session for the install, including the desktop. A password change alone does not.

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

Kotlin and Jetpack Compose. AGP 9.4.1, Kotlin 2.4.10, Compose BOM 2026.09.00. The gateway speaks JSON-RPC over a ticketed WebSocket. The screen picture is noVNC 1.5.0, vendored under `app/src/main/assets/novnc/` (MPL-2.0). The rest of this repository is MIT; see `LICENSE`.
