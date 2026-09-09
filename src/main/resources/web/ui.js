export const elements = {
  body: document.body,
  sidebar: document.querySelector("#sidebar"),
  details: document.querySelector("#details"),
  scrim: document.querySelector("#scrim"),
  conversation: document.querySelector("#conversation"),
  welcome: document.querySelector("#welcome"),
  messages: document.querySelector("#messages"),
  composer: document.querySelector("#composer"),
  prompt: document.querySelector("#prompt"),
  send: document.querySelector("#send-button"),
  threadList: document.querySelector("#thread-list"),
  title: document.querySelector("#thread-title"),
  subtitle: document.querySelector("#thread-subtitle"),
  status: document.querySelector("#status"),
  statusText: document.querySelector("#status-text"),
  eventLog: document.querySelector("#event-log"),
  toast: document.querySelector("#toast")
};

const icon = kind => {
  const paths = {
    agent: '<path d="M7.4 3.8 12 1.2l4.6 2.6 4.5 2.6v11.2l-4.5 2.6L12 22.8l-4.6-2.6-4.5-2.6V6.4l4.5-2.6Z"></path><path d="m8.1 8.2 3.9-2.3 3.9 2.3v4.6L12 15.1l-3.9-2.3V8.2Z"></path>',
    terminal: '<path d="m5 7 4 4-4 4M11 16h8"></path>',
    patch: '<path d="M4 6h16M4 12h10M4 18h13"></path>',
    team: '<circle cx="9" cy="9" r="3"></circle><circle cx="17" cy="10" r="2"></circle><path d="M3.5 19c.7-3 2.5-4.5 5.5-4.5s4.8 1.5 5.5 4.5M15 15c2.7 0 4.4 1.3 5 4"></path>',
    thinking: '<path d="M8 9h8M8 13h5"></path><path d="M5 4h14v13H9l-4 3V4Z"></path>',
    chevron: '<path d="m8 10 4 4 4-4"></path>'
  };
  return `<svg viewBox="0 0 24 24" aria-hidden="true">${paths[kind] || paths.thinking}</svg>`;
};

export function renderConfig(config) {
  const cwd = config.cwd || "";
  document.querySelector("#workspace-name").textContent = cwd.split("/").filter(Boolean).pop() || "Workspace";
  document.querySelector("#workspace-path").textContent = cwd || "未配置工作目录";
  document.querySelector("#model-label").textContent = config.model || "默认模型";
  document.querySelector("#detail-model").textContent = config.model || "—";
  document.querySelector("#detail-sandbox").textContent = config.sandbox || "—";
  document.querySelector("#detail-approval").textContent = config.approval || "—";
  document.querySelector("#detail-multi-agent").textContent = config.multi_agent ? "enabled" : "disabled";
}

export function renderSessions(sessions, activeId, onSelect) {
  elements.threadList.replaceChildren();
  document.querySelector("#session-count").textContent = sessions.length ? String(sessions.length) : "";
  if (!sessions.length) {
    const empty = document.createElement("div");
    empty.className = "empty-log";
    empty.textContent = "还没有历史任务";
    elements.threadList.append(empty);
    return;
  }
  sessions.forEach(session => {
    const row = document.createElement("button");
    row.className = `thread-item${session.id === activeId ? " active" : ""}`;
    row.type = "button";
    row.dataset.threadId = session.id;
    row.setAttribute("aria-pressed", String(session.id === activeId));
    const title = document.createElement("strong");
    title.textContent = session.title || shortPath(session.cwd);
    const meta = document.createElement("span");
    meta.textContent = `${formatTime(session.timestamp)} · ${session.model || "默认模型"}`;
    row.append(title, meta);
    row.addEventListener("click", () => onSelect(session));
    elements.threadList.append(row);
  });
}

export function appendUserMessage(text) {
  const wrapper = document.createElement("article");
  wrapper.className = "message user";
  const body = document.createElement("div");
  body.className = "message-body";
  body.textContent = text;
  wrapper.append(body);
  elements.messages.append(wrapper);
}

export function beginAssistantMessage() {
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
  return {content, traceList};
}

export function showResumeMessage() {
  elements.welcome.classList.add("hidden");
  const assistant = beginAssistantMessage();
  assistant.content.textContent = "已恢复历史任务。之前的上下文已加载，可以继续输入。";
}

export function startTrace(item, traceList, traces) {
  if (!traceList || traces.has(item.id)) return;
  const card = document.createElement("div");
  card.className = "trace-item";
  const summary = document.createElement("button");
  summary.className = "trace-summary";
  summary.type = "button";
  summary.innerHTML = icon(traceIcon(item.type));
  const title = document.createElement("strong");
  title.textContent = traceTitle(item);
  const status = document.createElement("span");
  status.className = "trace-status";
  status.textContent = "运行中";
  const chevron = document.createElement("span");
  chevron.className = "trace-chevron";
  chevron.innerHTML = icon("chevron");
  summary.append(title, status, chevron);
  const output = document.createElement("pre");
  output.className = item.type === "reasoning" ? "reasoning-text" : "trace-output";
  output.hidden = true;
  summary.addEventListener("click", () => {
    if (!output.textContent) return;
    output.hidden = !output.hidden;
    card.classList.toggle("expanded", !output.hidden);
  });
  card.append(summary, output);
  traceList.append(card);
  traces.set(item.id, {card, status, output});
}

export function updateTrace(item, traceList, traces) {
  let trace = traces.get(item.id);
  if (!trace) {
    startTrace(item, traceList, traces);
    trace = traces.get(item.id);
  }
  if (!trace) return;
  trace.output.hidden = false;
  trace.card.classList.add("expanded");
  trace.output.textContent += item.delta || "";
  trace.output.scrollTop = trace.output.scrollHeight;
}

export function completeTrace(item, traceList, traces) {
  let trace = traces.get(item.id);
  if (!trace) {
    startTrace(item, traceList, traces);
    trace = traces.get(item.id);
  }
  if (!trace) return;
  trace.status.textContent = item.status === "failed" ? "失败" : "完成";
  const output = item.text || item.output;
  if (output) {
    trace.output.hidden = false;
    trace.card.classList.add("expanded");
    trace.output.textContent = output;
  }
}

export function addEventLog(event) {
  if (elements.eventLog.querySelector(".empty-log")) elements.eventLog.replaceChildren();
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

export function resetConversation() {
  elements.messages.replaceChildren();
  elements.welcome.classList.remove("hidden");
  elements.title.textContent = "新任务";
  elements.subtitle.textContent = "选择一个任务，或从下方开始";
  document.querySelector("#detail-thread").textContent = "尚未创建";
  updateUsage({});
  elements.eventLog.innerHTML = '<div class="empty-log">发送任务后，这里会显示实时执行事件。</div>';
}

export function updateUsage(usage) {
  document.querySelector("#usage-input").textContent = `${usage.input_tokens || 0} tokens`;
  document.querySelector("#usage-output").textContent = `${usage.output_tokens || 0} tokens`;
}

export function setStatus(status) {
  elements.status.classList.toggle("busy", status === "busy");
  elements.status.classList.toggle("error", status === "error");
  elements.statusText.textContent = status === "busy" ? "运行中" : status === "error" ? "连接异常" : "本地就绪";
}

export function shortPath(path) {
  const parts = (path || "").split("/").filter(Boolean);
  return parts.slice(-2).join("/") || "Workspace";
}

export function shortId(value) {
  return value ? `${value.slice(0, 8)}…` : "—";
}

function formatTime(value) {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return "历史任务";
  return new Intl.DateTimeFormat("zh-CN", {month: "short", day: "numeric", hour: "2-digit", minute: "2-digit"}).format(date);
}

function traceTitle(item) {
  if (item.type === "reasoning") return "思考过程";
  if (item.type === "command_execution") return parseArguments(item.arguments).cmd || "运行命令";
  if (item.type === "file_change") return "应用代码修改";
  return item.tool || item.type || "工具调用";
}

function traceIcon(type) {
  if (type === "command_execution") return "terminal";
  if (type === "file_change") return "patch";
  if (type === "collab_tool_call") return "team";
  return "thinking";
}

function parseArguments(value) {
  if (!value) return {};
  if (typeof value === "object") return value;
  try {
    return JSON.parse(value);
  } catch {
    return {};
  }
}
