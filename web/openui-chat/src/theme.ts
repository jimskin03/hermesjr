import { createTheme } from "@openuidev/react-ui";

/**
 * OpenUI dark theme tuned to the Hermes Jr palette (see app.css): GitHub-dark
 * surfaces, amber accent, and the same card surface as the bot bubbles.
 * Injected by ThemeProvider as --openui-* custom properties (inline <style>,
 * allowed by the CSP's style-src 'unsafe-inline'; scripts stay 'self' only).
 */
const ink = "#e6edf3";
const muted = "#9da7b3";
const faint = "#6e7681";
const accent = "#c08532";
const bubble = "#161b22";
const bg = "#0d1117";
const stroke = "#30363d";
const font = '-apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, Helvetica, Arial, sans-serif';

export const hermesDarkTheme = createTheme({
  background: bg,
  foreground: bubble,
  popoverBackground: "#1c2129",
  sunkLight: "rgba(255,255,255,0.02)",
  sunk: "rgba(255,255,255,0.03)",
  sunkDeep: "rgba(255,255,255,0.06)",
  elevatedLight: "rgba(255,255,255,0.04)",
  elevated: "rgba(255,255,255,0.07)",
  elevatedStrong: "rgba(255,255,255,0.12)",
  elevatedIntense: "rgba(255,255,255,0.24)",
  highlightSubtle: "rgba(255,255,255,0.025)",
  highlight: "rgba(255,255,255,0.05)",
  highlightStrong: "rgba(255,255,255,0.09)",
  highlightIntense: "rgba(255,255,255,0.24)",
  invertedBackground: ink,

  textNeutralPrimary: ink,
  textNeutralSecondary: muted,
  textNeutralTertiary: faint,
  textNeutralLink: accent,
  textBrand: accent,
  textAccentPrimary: bg,
  textAccentSecondary: "rgba(13,17,23,0.75)",
  textAccentTertiary: "rgba(13,17,23,0.5)",
  textWhite: "#ffffff",
  textBlack: bg,

  interactiveAccentDefault: accent,
  interactiveAccentHover: "#d0954a",
  interactiveAccentPressed: "#a8722a",
  interactiveAccentDisabled: "rgba(192,133,50,0.35)",

  borderDefault: stroke,
  borderInteractive: "#3d444d",
  borderInteractiveEmphasis: "#6e7681",
  borderInteractiveSelected: accent,
  borderAccent: "rgba(192,133,50,0.45)",
  borderAccentEmphasis: accent,
  borderAccentSelected: accent,

  chatUserResponseBg: "#3a2a12",
  chatUserResponseText: ink,

  defaultChartPalette: [accent, "#58a6ff", "#3fb950", "#bc8cff", "#ff7b72", "#39c5cf", "#d29922", "#f778ba"],

  fontBody: font,
  fontHeading: font,
  fontLabel: font,
  fontNumbers: font,
});
