#!/usr/bin/env bash
# Serve the noVNC viewer files for Hermes Jr's Screen tab, the same way Friendly's host does:
#   websockify --web=$NOVNC_WEB_ROOT
#
# Hermes Jr does not bundle noVNC. It fetches vnc.html, app/, core/ and vendor/ from this port.
# The desktop picture itself still goes through hermes serve's ticketed /api/display/ws, so this
# websockify proxies nothing: every WebSocket is refused (empty token file).
#
# Environment:
#   NOVNC_WEB_ROOT  noVNC folder that contains vnc.html (default /usr/share/novnc)
#   NOVNC_PORT      port to listen on (default 6080; Hermes Jr's default)
#   NOVNC_BIND      address to listen on (default: this computer's Tailscale IPv4, else 0.0.0.0)
set -euo pipefail

NOVNC_WEB_ROOT="${NOVNC_WEB_ROOT:-/usr/share/novnc}"
NOVNC_PORT="${NOVNC_PORT:-6080}"
NOVNC_BIND="${NOVNC_BIND:-}"

if [ ! -f "$NOVNC_WEB_ROOT/vnc.html" ]; then
  echo "noVNC not found at $NOVNC_WEB_ROOT (no vnc.html)." >&2
  echo "Install it (Debian/Ubuntu: sudo apt install novnc websockify) or set NOVNC_WEB_ROOT." >&2
  exit 1
fi
if ! command -v websockify >/dev/null 2>&1; then
  echo "websockify not found. Debian/Ubuntu: sudo apt install websockify (or: pipx install websockify)." >&2
  exit 1
fi

if [ -z "$NOVNC_BIND" ] && command -v tailscale >/dev/null 2>&1; then
  NOVNC_BIND="$(tailscale ip -4 2>/dev/null | head -n1 || true)"
fi
NOVNC_BIND="${NOVNC_BIND:-0.0.0.0}"

echo "Serving noVNC from $NOVNC_WEB_ROOT on http://$NOVNC_BIND:$NOVNC_PORT/vnc.html"
exec websockify --web="$NOVNC_WEB_ROOT" --heartbeat=30 \
  --token-plugin=TokenFile --token-source=/dev/null \
  "$NOVNC_BIND:$NOVNC_PORT"
