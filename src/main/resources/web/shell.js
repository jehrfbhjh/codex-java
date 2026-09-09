import {elements} from "./ui.js";

export function openSidebar() {
  elements.sidebar.classList.add("open");
  elements.body.classList.add("panel-open");
}

export function toggleDetails() {
  const open = !elements.details.classList.contains("open");
  closePanels();
  elements.details.classList.toggle("open", open);
  elements.details.setAttribute("aria-hidden", String(!open));
  document.querySelector("#details-toggle").setAttribute("aria-expanded", String(open));
  elements.body.classList.toggle("panel-open", open);
}

export function closePanels() {
  elements.sidebar.classList.remove("open");
  elements.details.classList.remove("open");
  elements.details.setAttribute("aria-hidden", "true");
  document.querySelector("#details-toggle").setAttribute("aria-expanded", "false");
  elements.body.classList.remove("panel-open");
}

export function updateComposerState(busy) {
  elements.prompt.disabled = busy;
  elements.send.disabled = busy || !elements.prompt.value.trim();
}

export function resizePrompt() {
  elements.prompt.style.height = "auto";
  elements.prompt.style.height = `${Math.min(elements.prompt.scrollHeight, 180)}px`;
}

export function scrollConversation() {
  elements.conversation.scrollTop = elements.conversation.scrollHeight;
}

export function restoreTheme() {
  const saved = localStorage.getItem("codex-java-theme");
  const dark = saved ? saved === "dark" : window.matchMedia("(prefers-color-scheme: dark)").matches;
  applyTheme(dark);
}

export function toggleTheme() {
  const dark = !elements.body.classList.contains("dark");
  applyTheme(dark);
  localStorage.setItem("codex-java-theme", dark ? "dark" : "light");
}

function applyTheme(dark) {
  elements.body.classList.toggle("dark", dark);
  const button = document.querySelector("#theme-toggle");
  button.setAttribute("aria-pressed", String(dark));
  button.setAttribute("aria-label", dark ? "切换浅色模式" : "切换深色模式");
}

let toastTimer;
export function showToast(message) {
  elements.toast.textContent = message;
  elements.toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => elements.toast.classList.remove("visible"), 2800);
}

export function handleViewportChange() {
  if (window.innerWidth > 760 && elements.sidebar.classList.contains("open")) {
    closePanels();
  }
}
