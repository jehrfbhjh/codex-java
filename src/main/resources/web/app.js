import {
  addEventLog,
  appendUserMessage,
  beginAssistantMessage,
  completeTrace,
  elements,
  renderConfig,
  renderSessions,
  resetConversation,
  setStatus,
  shortId,
  shortPath,
  showResumeMessage,
  startTrace,
  updateTrace,
  updateUsage
} from "./ui.js";
import {
  closePanels,
  handleViewportChange,
  openSidebar,
  resizePrompt,
  restoreTheme,
  scrollConversation,
  showToast,
  toggleDetails,
  toggleTheme,
  updateComposerState
} from "./shell.js";

const state = {
  config: null,
  threadId: null,
  busy: false,
  assistantText: "",
  assistantNode: null,
  traceList: null,
  traces: new Map(),
  sessions: []
};

async function initialize() {
  bindEvents();
  restoreTheme();
  updateSendState();
  const [configResult, sessionsResult] = await Promise.allSettled([
    fetchJson("/api/config"),
    fetchJson("/api/sessions")
  ]);
  if (configResult.status === "fulfilled") {
    state.config = configResult.value;
    renderConfig(state.config);
  }
  if (sessionsResult.status === "fulfilled") {
    state.sessions = sessionsResult.value.sessions || [];
  }
  renderSessionList();
  if (configResult.status === "rejected") {
    setStatus("error");
    showToast(configResult.reason.message);
  } else {
    setStatus("ready");
  }
}

function bindEvents() {
  elements.composer.addEventListener("submit", event => {
    event.preventDefault();
    submitPrompt();
  });
  elements.prompt.addEventListener("input", () => {
    resizePrompt();
    updateSendState();
  });
  elements.prompt.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey && !event.isComposing) {
      event.preventDefault();
      submitPrompt();
    }
  });
  document.querySelector("#new-thread").addEventListener("click", resetThread);
  document.querySelector("#theme-toggle").addEventListener("click", toggleTheme);
  document.querySelector("#attachment-button").addEventListener("click", () => {
    showToast("附件功能尚未接入，当前可直接在任务中填写文件路径");
  });
  document.querySelector("#menu-button").addEventListener("click", openSidebar);
  document.querySelector("#sidebar-close").addEventListener("click", closePanels);
  document.querySelector("#details-toggle").addEventListener("click", toggleDetails);
  document.querySelector("#details-close").addEventListener("click", closePanels);
  elements.scrim.addEventListener("click", closePanels);
  document.querySelectorAll("[data-prompt]").forEach(button => {
    button.addEventListener("click", () => {
      elements.prompt.value = button.dataset.prompt;
      resizePrompt();
      updateSendState();
      elements.prompt.focus();
    });
  });
  document.addEventListener("keydown", event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "n") {
      event.preventDefault();
      resetThread();
    } else if (event.key === "Escape") {
      closePanels();
    }
  });
  window.addEventListener("resize", handleViewportChange);
}

async function submitPrompt() {
  const prompt = elements.prompt.value.trim();
  if (!prompt || state.busy) return;
  setBusy(true);
  elements.welcome.classList.add("hidden");
  appendUserMessage(prompt);
  const assistant = beginAssistantMessage();
  state.assistantText = "";
  state.assistantNode = assistant.content;
  state.traceList = assistant.traceList;
  state.traces.clear();
  elements.prompt.value = "";
  resizePrompt();
  elements.title.textContent = prompt.length > 42 ? `${prompt.slice(0, 42)}…` : prompt;

  try {
    if (!state.threadId) await createThread();
    const response = await fetch(`/api/threads/${encodeURIComponent(state.threadId)}/turn`, {
      method: "POST",
      headers: {"Content-Type": "application/json"},
      body: JSON.stringify({prompt})
    });
    if (!response.ok) {
      const payload = await response.json().catch(() => ({}));
      throw new Error(payload.error || `请求失败：HTTP ${response.status}`);
    }
    await consumeNdjson(response.body);
  } catch (error) {
    appendError(error.message);
    setStatus("error");
  } finally {
    setBusy(false);
  }
}

async function createThread() {
  const thread = await fetchJson("/api/threads", {method: "POST"});
  activateThread(thread);
  state.sessions.unshift({
    id: thread.thread_id,
    timestamp: new Date().toISOString(),
    cwd: thread.cwd,
    model: thread.model,
    title: elements.title.textContent
  });
  renderSessionList();
}

async function resumeThread(session) {
  if (state.busy || session.id === state.threadId) {
    closePanels();
    return;
  }
  try {
    setStatus("busy");
    const thread = await fetchJson(`/api/threads/${encodeURIComponent(session.id)}/resume`, {method: "POST"});
    resetConversation();
    activateThread(thread);
    elements.title.textContent = session.title || shortPath(session.cwd);
    elements.subtitle.textContent = `已恢复 · ${thread.model}`;
    showResumeMessage();
    renderSessionList();
    closePanels();
    elements.prompt.focus();
    setStatus("ready");
  } catch (error) {
    setStatus("error");
    showToast(error.message);
  }
}

function activateThread(thread) {
  state.threadId = thread.thread_id;
  document.querySelector("#detail-thread").textContent = shortId(state.threadId);
  elements.subtitle.textContent = `${shortPath(thread.cwd)} · ${thread.model}`;
}

async function consumeNdjson(stream) {
  const reader = stream.getReader();
  const decoder = new TextDecoder();
  let buffer = "";
  while (true) {
    const {value, done} = await reader.read();
    buffer += decoder.decode(value || new Uint8Array(), {stream: !done});
    const lines = buffer.split("\n");
    buffer = lines.pop() || "";
    lines.filter(Boolean).forEach(parseEvent);
    if (done) {
      if (buffer.trim()) parseEvent(buffer);
      break;
    }
  }
}

function parseEvent(line) {
  try {
    handleEvent(JSON.parse(line));
  } catch {
    throw new Error("服务返回了无法解析的事件流");
  }
}

function handleEvent(event) {
  addEventLog(event);
  const item = event.item || {};
  if (event.type === "turn.started") {
    setStatus("busy");
  } else if (event.type === "item.updated" && item.type === "agent_message") {
    state.assistantText += item.delta || "";
    state.assistantNode.textContent = state.assistantText;
  } else if (event.type === "item.started" && item.type !== "agent_message") {
    startTrace(item, state.traceList, state.traces);
  } else if (event.type === "item.updated" && item.type !== "agent_message") {
    updateTrace(item, state.traceList, state.traces);
  } else if (event.type === "item.completed" && item.type !== "agent_message") {
    completeTrace(item, state.traceList, state.traces);
  } else if (event.type === "turn.completed") {
    updateUsage(event.usage || {});
    setStatus("ready");
  } else if (event.type === "turn.failed" || event.type === "error") {
    appendError(event.message || "执行失败");
    setStatus("error");
  }
  scrollConversation();
}

function appendError(message) {
  if (!state.assistantNode) {
    const assistant = beginAssistantMessage();
    state.assistantNode = assistant.content;
  }
  state.assistantNode.textContent = `执行失败：${message}`;
}

function resetThread() {
  if (state.busy) {
    showToast("当前任务仍在运行");
    return;
  }
  state.threadId = null;
  state.assistantNode = null;
  state.assistantText = "";
  state.traceList = null;
  state.traces.clear();
  resetConversation();
  renderSessionList();
  closePanels();
  elements.prompt.focus();
}

function setBusy(busy) {
  state.busy = busy;
  if (busy) setStatus("busy");
  updateSendState();
}

function updateSendState() {
  updateComposerState(state.busy);
}

function renderSessionList() {
  renderSessions(state.sessions, state.threadId, resumeThread);
}

async function fetchJson(url, options) {
  const response = await fetch(url, options);
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}));
    throw new Error(payload.error || `请求失败：HTTP ${response.status}`);
  }
  return response.json();
}

initialize();
