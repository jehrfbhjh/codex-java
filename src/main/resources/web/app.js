const state = {
  config: null,
  threadId: null,
  busy: false,
  assistantText: "",
  assistantNode: null,
  traces: new Map(),
  sessions: []
};

const elements = {
  body: document.body,
  sidebar: document.querySelector("#sidebar"),
  details: document.querySelector("#details"),
  conversation: document.querySelector("#conversation"),
  welcome: document.querySelector("#welcome"),
  messages: document.querySelector("#messages"),
  composer: document.querySelector("#composer"),
  prompt: document.querySelector("#prompt"),
  send: document.querySelector("#send-button"),
  threadList: document.querySelector("#thread-list"),
  title: document.querySelector("#thread-title"),
  subtitle: document.querySelector("#thread-subtitle"),
  statusPill: document.querySelector(".status-pill"),
  statusText: document.querySelector("#status-text"),
  eventLog: document.querySelector("#event-log"),
  toast: document.querySelector("#toast")
};

const icon = (kind) => {
  const paths = {
    agent: '<path d="M7.4 3.8 12 1.2l4.6 2.6 4.5 2.6v11.2l-4.5 2.6L12 22.8l-4.6-2.6-4.5-2.6V6.4l4.5-2.6Z"></path><path d="m8.1 8.2 3.9-2.3 3.9 2.3v4.6L12 15.1l-3.9-2.3V8.2Z"></path>',
    terminal: '<path d="m5 7 4 4-4 4M11 16h8"></path>',
    patch: '<path d="M4 6h16M4 12h10M4 18h13"></path>',
    team: '<circle cx="9" cy="9" r="3"></circle><circle cx="17" cy="10" r="2"></circle><path d="M3.5 19c.7-3 2.5-4.5 5.5-4.5s4.8 1.5 5.5 4.5M15 15c2.7 0 4.4 1.3 5 4"></path>',
    thinking: '<path d="M8 9h8M8 13h5"></path><path d="M5 4h14v13H9l-4 3V4Z"></path>'
  };
  return `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[kind] || paths.thinking}</svg>`;
};

async function initialize() {
  bindEvents();
  restoreTheme();
  try {
    const [configResponse, sessionsResponse] = await Promise.all([
      fetch("/api/config"),
      fetch("/api/sessions")
    ]);
    if (!configResponse.ok || !sessionsResponse.ok) {
      throw new Error("服务初始化失败");
    }
    state.config = await configResponse.json();
    state.sessions = (await sessionsResponse.json()).sessions || [];
    renderConfig();
    renderSessions();
    setStatus("ready");
  } catch (error) {
    setStatus("error");
    showToast(error.message);
  }
}

function bindEvents() {
  elements.composer.addEventListener("submit", event => {
    event.preventDefault();
    submitPrompt();
  });
  elements.prompt.addEventListener("input", resizePrompt);
  elements.prompt.addEventListener("keydown", event => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      submitPrompt();
    }
  });
  document.querySelector("#new-thread").addEventListener("click", resetThread);
  document.querySelector("#theme-toggle").addEventListener("click", toggleTheme);
  document.querySelector("#menu-button").addEventListener("click", () => elements.sidebar.classList.add("open"));
  document.querySelector("#sidebar-close").addEventListener("click", () => elements.sidebar.classList.remove("open"));
  document.querySelector("#details-toggle").addEventListener("click", () => elements.details.classList.toggle("open"));
  document.querySelector("#details-close").addEventListener("click", () => elements.details.classList.remove("open"));
  document.querySelectorAll("[data-prompt]").forEach(button => {
    button.addEventListener("click", () => {
      elements.prompt.value = button.dataset.prompt;
      resizePrompt();
      elements.prompt.focus();
    });
  });
  document.addEventListener("keydown", event => {
    if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === "n") {
      event.preventDefault();
      resetThread();
    }
  });
}

function renderConfig() {
  const cwd = state.config.cwd || "";
  document.querySelector("#workspace-name").textContent = cwd.split("/").filter(Boolean).pop() || "Workspace";
  document.querySelector("#workspace-path").textContent = cwd;
  document.querySelector("#model-label").textContent = state.config.model;
  document.querySelector("#detail-model").textContent = state.config.model;
  document.querySelector("#detail-sandbox").textContent = state.config.sandbox;
  document.querySelector("#detail-approval").textContent = state.config.approval;
  document.querySelector("#detail-multi-agent").textContent = state.config.multi_agent ? "enabled" : "disabled";
}

function renderSessions() {
  elements.threadList.replaceChildren();
  if (!state.sessions.length) {
    const empty = document.createElement("div");
    empty.className = "empty-log";
    empty.textContent = "还没有历史任务";
    elements.threadList.append(empty);
    return;
  }
  state.sessions.forEach(session => {
    const row = document.createElement("button");
    row.className = "thread-item";
    row.type = "button";
    const title = document.createElement("strong");
    title.textContent = shortPath(session.cwd);
    const meta = document.createElement("span");
    meta.textContent = formatTime(session.timestamp);
    row.append(title, meta);
    row.title = `会话 ${session.id} 已持久化，可通过 CLI resume 恢复`;
    elements.threadList.append(row);
  });
}

async function submitPrompt() {
  const prompt = elements.prompt.value.trim();
  if (!prompt || state.busy) {
    return;
  }
  setBusy(true);
  elements.welcome.classList.add("hidden");
  appendUserMessage(prompt);
  beginAssistantMessage();
  elements.prompt.value = "";
  resizePrompt();
  elements.title.textContent = prompt.length > 42 ? `${prompt.slice(0, 42)}…` : prompt;

  try {
    if (!state.threadId) {
      await createThread();
    }
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
  const response = await fetch("/api/threads", {method: "POST"});
  if (!response.ok) {
    throw new Error("无法创建会话");
  }
  const thread = await response.json();
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
    lines.filter(Boolean).forEach(line => handleEvent(JSON.parse(line)));
    if (done) {
      if (buffer.trim()) {
        handleEvent(JSON.parse(buffer));
      }
      break;
    }
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
    startTrace(item);
  } else if (event.type === "item.updated" && item.type !== "agent_message") {
    updateTrace(item);
  } else if (event.type === "item.completed" && item.type !== "agent_message") {
    completeTrace(item);
  } else if (event.type === "turn.completed") {
    updateUsage(event.usage || {});
    setStatus("ready");
  } else if (event.type === "turn.failed" || event.type === "error") {
    appendError(event.message || "执行失败");
    setStatus("error");
  }
  scrollConversation();
}

function appendUserMessage(text) {
  const wrapper = document.createElement("article");
  wrapper.className = "message user";
  const body = document.createElement("div");
  body.className = "message-body";
  body.textContent = text;
  wrapper.append(body);
  elements.messages.append(wrapper);
}

function beginAssistantMessage() {
  state.assistantText = "";
  state.traces.clear();
  const message = document.createElement("article");
  message.className = "message assistant";
  const traceList = document.createElement("div");
  traceList.className = "trace-list";
  const block = document.createElement("div");
  block.className = "assistant-block";
  const avatar = document.createElement("div");
  avatar.className = "assistant-avatar";
  avatar.innerHTML = icon("agent");
  const content = document.createElement("div");
  content.className = "assistant-content";
  block.append(avatar, content);
  message.append(traceList, block);
  elements.messages.append(message);
  state.assistantNode = content;
  state.traceList = traceList;
}

function startTrace(item) {
  if (!state.traceList || state.traces.has(item.id)) {
    return;
  }
  const card = document.createElement("div");
  card.className = "trace-item";
  const summary = document.createElement("div");
  summary.className = "trace-summary";
  const type = traceIcon(item.type);
  summary.innerHTML = icon(type);
  const title = document.createElement("strong");
  title.textContent = traceTitle(item);
  const status = document.createElement("span");
  status.className = "trace-status";
  status.textContent = "运行中";
  summary.append(title, status);
  const output = document.createElement("pre");
  output.className = item.type === "reasoning" ? "reasoning-text" : "trace-output";
  output.hidden = true;
  card.append(summary, output);
  state.traceList.append(card);
  state.traces.set(item.id, {card, status, output});
}

function updateTrace(item) {
  let trace = state.traces.get(item.id);
  if (!trace) {
    startTrace(item);
    trace = state.traces.get(item.id);
  }
  if (!trace) {
    return;
  }
  trace.output.hidden = false;
  trace.output.textContent += item.delta || "";
  trace.output.scrollTop = trace.output.scrollHeight;
}

function completeTrace(item) {
  let trace = state.traces.get(item.id);
  if (!trace) {
    startTrace(item);
    trace = state.traces.get(item.id);
  }
  if (!trace) {
    return;
  }
  trace.status.textContent = item.status === "failed" ? "失败" : "完成";
  if (item.text) {
    trace.output.hidden = false;
    trace.output.textContent = item.text;
  }
  if (item.output && !trace.output.textContent) {
    trace.output.hidden = false;
    trace.output.textContent = item.output;
  }
}

function traceTitle(item) {
  if (item.type === "reasoning") {
    return "思考过程";
  }
  if (item.type === "command_execution") {
    return parseArguments(item.arguments).cmd || "运行命令";
  }
  if (item.type === "file_change") {
    return "应用代码修改";
  }
  return item.tool || item.type || "工具调用";
}

function traceIcon(type) {
  if (type === "command_execution") return "terminal";
  if (type === "file_change") return "patch";
  if (type === "collab_tool_call") return "team";
  return "thinking";
}

function parseArguments(argumentsValue) {
  if (!argumentsValue) return {};
  if (typeof argumentsValue === "object") return argumentsValue;
  try {
    return JSON.parse(argumentsValue);
  } catch {
    return {};
  }
}

function addEventLog(event) {
  if (elements.eventLog.querySelector(".empty-log")) {
    elements.eventLog.replaceChildren();
  }
  const row = document.createElement("div");
  row.className = `event-row ${event.type.endsWith("started") ? "live" : event.type.endsWith("completed") ? "done" : ""}`;
  const dot = document.createElement("i");
  const text = document.createElement("span");
  const itemType = event.item?.type ? ` · ${event.item.type}` : "";
  text.textContent = `${event.type}${itemType}`;
  row.append(dot, text);
  elements.eventLog.append(row);
  elements.eventLog.scrollTop = elements.eventLog.scrollHeight;
}

function appendError(message) {
  if (!state.assistantNode) {
    beginAssistantMessage();
  }
  state.assistantNode.textContent = `执行失败：${message}`;
}

function updateUsage(usage) {
  document.querySelector("#usage-input").textContent = `${usage.input_tokens || 0} tokens`;
  document.querySelector("#usage-output").textContent = `${usage.output_tokens || 0} tokens`;
}

function resetThread() {
  if (state.busy) {
    showToast("当前任务仍在运行");
    return;
  }
  state.threadId = null;
  state.assistantNode = null;
  state.assistantText = "";
  state.traces.clear();
  elements.messages.replaceChildren();
  elements.welcome.classList.remove("hidden");
  elements.title.textContent = "新任务";
  elements.subtitle.textContent = "准备就绪";
  document.querySelector("#detail-thread").textContent = "尚未创建";
  document.querySelector("#usage-input").textContent = "0 tokens";
  document.querySelector("#usage-output").textContent = "0 tokens";
  elements.eventLog.innerHTML = '<div class="empty-log">发送任务后，这里会实时显示执行事件。</div>';
  elements.sidebar.classList.remove("open");
  elements.prompt.focus();
}

function setBusy(busy) {
  state.busy = busy;
  elements.send.disabled = busy;
  elements.prompt.disabled = busy;
  if (busy) {
    setStatus("busy");
  }
}

function setStatus(status) {
  elements.statusPill.classList.toggle("busy", status === "busy");
  elements.statusText.textContent = status === "busy" ? "运行中" : status === "error" ? "异常" : "本地";
}

function resizePrompt() {
  elements.prompt.style.height = "auto";
  elements.prompt.style.height = `${Math.min(elements.prompt.scrollHeight, 180)}px`;
}

function scrollConversation() {
  elements.conversation.scrollTop = elements.conversation.scrollHeight;
}

function shortPath(path) {
  const parts = (path || "").split("/").filter(Boolean);
  return parts.slice(-2).join("/") || "Workspace";
}

function shortId(value) {
  return value ? `${value.slice(0, 8)}…` : "—";
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "";
  return new Intl.DateTimeFormat("zh-CN", {month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"}).format(date);
}

function restoreTheme() {
  if (localStorage.getItem("codex-java-theme") === "dark") {
    elements.body.classList.add("dark");
  }
}

function toggleTheme() {
  elements.body.classList.toggle("dark");
  localStorage.setItem("codex-java-theme", elements.body.classList.contains("dark") ? "dark" : "light");
}

let toastTimer;
function showToast(message) {
  elements.toast.textContent = message;
  elements.toast.classList.add("visible");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => elements.toast.classList.remove("visible"), 2600);
}

initialize();
