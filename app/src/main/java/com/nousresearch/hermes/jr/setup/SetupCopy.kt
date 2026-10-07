package com.nousresearch.hermes.jr.setup

object SetupCopy {
    const val LINUX = """mkdir -p ~/.config/systemd/user
install -m 600 /dev/null ~/.hermes/.env 2>/dev/null || true
cat >> ~/.hermes/.env <<'EOF'
HERMES_DASHBOARD_BASIC_AUTH_USERNAME=admin
HERMES_DASHBOARD_BASIC_AUTH_PASSWORD=choose-a-strong-password
HERMES_DASHBOARD_BASIC_AUTH_SECRET=REPLACE_WITH_openssl_rand_-base64_32
EOF
chmod 600 ~/.hermes/.env

cat > ~/.config/systemd/user/hermes-serve.service <<'EOF'
[Unit]
Description=Hermes serve (headless gateway)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
EnvironmentFile=%h/.hermes/.env
ExecStart=%h/.local/bin/hermes serve --host 0.0.0.0 --port 9119
Restart=on-failure
RestartSec=2

[Install]
WantedBy=default.target
EOF

systemctl --user daemon-reload
systemctl --user enable --now hermes-serve.service
loginctl enable-linger "${'$'}USER""""

    const val LINUX_NOTE = """Replace --host 0.0.0.0 with the computer's Tailscale address when it is on a tailnet. That address may be IPv4 CGNAT (100.x) or Tailscale IPv6 (fd7a:115c:a1e0::/48). A v6 address in the phone URL is bracketed: http://[fd7a:115c:a1e0::1234]:9119.

systemd treats the SIGTERM from hermes serve --stop as a clean stop, so Restart=on-failure does not bring it back. A SIGKILL after the 10 second grace can. Park the unit with systemctl --user stop hermes-serve.service."""

    const val MAC = """<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0">
<dict>
  <key>Label</key><string>ai.hermes.serve</string>
  <key>ProgramArguments</key>
  <array>
    <string>REPLACE_HOME/.local/bin/hermes</string>
    <string>serve</string>
    <string>--host</string><string>0.0.0.0</string>
    <string>--port</string><string>9119</string>
  </array>
  <key>EnvironmentVariables</key>
  <dict>
    <key>HERMES_DASHBOARD_BASIC_AUTH_USERNAME</key><string>admin</string>
    <key>HERMES_DASHBOARD_BASIC_AUTH_PASSWORD</key><string>choose-a-strong-password</string>
    <key>HERMES_DASHBOARD_BASIC_AUTH_SECRET</key><string>REPLACE_WITH_openssl_rand_-base64_32</string>
  </dict>
  <key>RunAtLoad</key><true/>
  <key>KeepAlive</key><true/>
</dict>
</plist>"""

    const val MAC_NOTE = """Save that as ~/Library/LaunchAgents/ai.hermes.serve.plist, then launchctl bootstrap gui/${'$'}UID ~/Library/LaunchAgents/ai.hermes.serve.plist. KeepAlive starts it again after hermes serve --stop. Park it with launchctl bootout gui/${'$'}UID/ai.hermes.serve."""

    const val TMUX = "tmux new-session -d -s hermes -- hermes serve --host 0.0.0.0 --port 9119"

    const val TMUX_NOTE = "This survives closing the terminal. It does not survive a reboot."

    const val WINDOWS = "On Windows, run hermes serve --host 0.0.0.0 --port 9119 at logon from Task Scheduler or the process manager that already supervises the machine. There is no --bg flag."

    const val LOST_PHONE = """Signing out on this phone only deletes the tokens stored here. There is no remote sign-out for one Hermes Jr. device.

If this phone is lost and the computer uses a password: replace HERMES_DASHBOARD_BASIC_AUTH_SECRET, then systemctl --user restart hermes-serve (or boot out the LaunchAgent). That ends every password session for this install, including the desktop.

If sign-in is Nous: revoke the dashboard client in the Portal, or wait for the refresh token to expire. Replaying an old Nous refresh token also ends that Portal session.

A password change alone does not invalidate tokens already issued."""
}
