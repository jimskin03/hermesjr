import React from "react";
import { createRoot } from "react-dom/client";
import { ThemeProvider } from "@openuidev/react-ui";
import "@openuidev/react-ui/index.css";
import { App } from "./App";
import { hermesDarkTheme } from "./theme";
import "./app.css";
import "./shell.css";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <ThemeProvider mode="dark" darkTheme={hermesDarkTheme} lightTheme={hermesDarkTheme}>
      <App />
    </ThemeProvider>
  </React.StrictMode>,
);
