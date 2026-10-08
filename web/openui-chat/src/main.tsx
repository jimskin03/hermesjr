import React from "react";
import { createRoot } from "react-dom/client";
import "@openuidev/react-ui/index.css";
import { App } from "./App";
import "./app.css";
import "./shell.css";

createRoot(document.getElementById("root")!).render(
  <React.StrictMode>
    <App />
  </React.StrictMode>,
);
