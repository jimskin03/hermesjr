package com.nousresearch.hermes.jr.ui

/**
 * Runs after each noVNC page load in the Screen tab. The page and its scripts come from the
 * computer's own noVNC install (see RfbBridge); `app/ui.js` arrives with Friendly's one-line hook,
 * `globalThis.__novncUI = UI`, so this script can reach the live RFB object.
 *
 * - `jrSetControl(on)` flips noVNC's viewOnly. That is only UX: hermes serve still refuses input
 *   from a viewer that does not hold the display lease.
 * - noVNC's control bar (keyboard, clipboard, extra keys) shows only while you hold control.
 * - Scaling stays on so the whole desktop fits the phone.
 */
internal const val JR_VIEWER_JS = """
(function() {
  if (!document.getElementById('jr-viewer-style')) {
    var style = document.createElement('style');
    style.id = 'jr-viewer-style';
    style.textContent = 'html, body, #noVNC_container { background: #0D1117 !important; } ' +
      'html.jr-watch #noVNC_control_bar_anchor { display: none !important; }';
    (document.head || document.documentElement).appendChild(style);
  }
  function apply() {
    var on = !!window.__jrControl;
    document.documentElement.classList.toggle('jr-watch', !on);
    var ui = window.__novncUI;
    if (!ui || !ui.rfb) return;
    try {
      if (ui.rfb.viewOnly !== !on) ui.rfb.viewOnly = !on;
      if (!ui.rfb.scaleViewport) ui.rfb.scaleViewport = true;
    } catch (e) {}
  }
  window.jrSetControl = function(on) { window.__jrControl = !!on; apply(); };
  if (!window.__jrViewerTimer) window.__jrViewerTimer = setInterval(apply, 1000);
  apply();
})();
"""

internal fun jrControlJs(on: Boolean): String = "if (window.jrSetControl) window.jrSetControl($on);"
