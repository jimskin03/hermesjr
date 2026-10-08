import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// Output index.html + hashed JS/CSS files. The APK serves them from
// https://appassets.androidplatform.net/assets/openui/ via WebViewAssetLoader,
// so the CSP can allow script-src 'self' without 'unsafe-inline' / 'unsafe-eval'.
export default defineConfig({
  plugins: [react()],
  base: "./",
  build: {
    outDir: "dist",
    emptyOutDir: true,
    assetsDir: "static",
    cssCodeSplit: false,
    sourcemap: false,
    modulePreload: { polyfill: false },
    rollupOptions: {
      output: {
        inlineDynamicImports: true,
        entryFileNames: "static/app-[hash].js",
        assetFileNames: "static/[name]-[hash][extname]",
      },
    },
  },
});
