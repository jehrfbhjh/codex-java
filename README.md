# Codex Java

`codex-java` 是基于本机 OpenAI Codex 源码
`/Users/bytedance/codex-source-study`（基线提交 `ea2046f`）重新实现的 Java 17
编码 Agent。它不是把 140 多个 Rust crate 逐行机械翻译，而是优先兼容 Codex 的核心运行语义：

- OpenAI Responses API 与 SSE 流式输出
- Codex 风格运行事件：turn、reasoning、工具生命周期和 token usage
- `message`、`function_call`、`custom_tool_call` 和工具输出协议
- 模型 → 工具 → 模型的多轮 Agent 循环
- `exec_command` 和原生 `apply_patch`
- `AGENTS.md` 分层指令加载
- `config.toml`、环境变量和 CLI 参数覆盖
- JSONL 会话持久化、列表与 `resume --last`
- MultiAgent V2：子 Agent 树、独立会话、邮箱、历史 fork 与协作工具
- `read-only`、`workspace-write`、`danger-full-access` 路径策略
- `on-request`、`never` 审批策略

## 构建

需要 Java 17+ 和 Maven 3.9+：

```bash
cd /Users/bytedance/codex-java
mvn test
mvn package
```

可执行文件为：

```bash
java -jar target/codex-java.jar --help
```

也可以使用包装脚本：

```bash
./codex-java --help
```

## 配置

最小配置：

```bash
export OPENAI_API_KEY='...'
export OPENAI_MODEL='gpt-5.4'
```

默认读取 `${CODEX_HOME:-~/.codex}/config.toml` 中的这些上游兼容键：

```toml
model = "gpt-5.4"
openai_base_url = "https://api.openai.com/v1"
approval_policy = "on-request"
sandbox_mode = "workspace-write"

[features.multi_agent_v2]
enabled = true
max_concurrent_threads_per_session = 4
min_wait_timeout_ms = 10000
max_wait_timeout_ms = 3600000
default_wait_timeout_ms = 30000
tool_namespace = "collaboration"
```

优先级为 CLI 参数 > 环境变量 > `config.toml` > 默认值。
与基线 Codex 一致，MultiAgent V2 默认关闭；设置
`features.multi_agent_v2.enabled = true` 后启用。

## MultiAgent V2

启用后，Responses API 会收到 `collaboration` namespace，包含六个工具：

- `spawn_agent`：按 `/root/task_name` 创建子 Agent；支持 `fork_turns = none|all|N`。
- `send_message`：把普通消息加入目标邮箱，不主动启动新 turn。
- `followup_task`：加入 `NEW_TASK`，目标空闲时立即启动新 turn。
- `wait_agent`：等待当前 Agent 的邮箱活动，遵循上游超时钳制规则。
- `interrupt_agent`：取消目标当前 turn，Agent 保留，可继续接收后续任务。
- `list_agents`：列出当前根任务树及其状态。

每个子 Agent 有独立 Responses API 循环和 JSONL 会话，共享工作目录与工具；
子 Agent 的最后一条回答会以 `FINAL_ANSWER` 自动投递给父 Agent。并发配置包含根
Agent，因此默认值 4 表示最多 3 个子 Agent turn 同时执行。

## 使用

### Web 工作台

启动本地 Codex 风格 Web 页面：

```bash
./codex-java web -C /Users/bytedance/codex-java
```

然后打开：

```text
http://127.0.0.1:8765
```

可以自定义监听地址和端口：

```bash
./codex-java web \
  --host 127.0.0.1 \
  --port 9000 \
  -C /Users/bytedance/codex-java
```

Web 工作台包含：

- 最近会话侧栏和新建任务；
- 模型回答的实时流式展示；
- reasoning summary、命令输出、补丁和 Multi Agent 调用卡片；
- 模型、工作目录、沙箱、审批模式和 token usage；
- 明暗主题与移动端响应式布局。

页面由 Java 内置 HTTP Server 直接托管，不需要安装 Node.js。API Key 只由 Java
进程从环境变量或 CLI 参数读取，不会发送给浏览器。浏览器只连接本机
`/api/threads` 接口。

> Web 模式目前没有交互式审批弹窗。使用 `on-request` 时，需要审批的危险命令会
> 被拒绝；如需无交互执行，请仅在可信工作区内显式选择合适的审批和沙箱配置。

### 终端模式

交互模式：

```bash
./codex-java -C /Users/bytedance/codex-java
```

非交互模式：

```bash
./codex-java exec -C /Users/bytedance/codex-java "检查项目并修复失败的单元测试"
```

默认 human 模式会将模型回答按 token 实时写入 stdout，并将 reasoning 摘要、命令、
补丁和协作工具状态写入 stderr，因此可以单独保存最终回答：

```bash
./codex-java exec -C /Users/bytedance/codex-java "检查项目" > answer.md
```

机器集成或调试执行轨迹时，使用 Codex 兼容风格的 JSONL 事件流：

```bash
./codex-java exec --json -C /Users/bytedance/codex-java "检查项目并运行测试" | tee trace.jsonl
```

每行都是独立 JSON，对应的核心事件包括：

- `thread.started`
- `turn.started`、`turn.completed`、`turn.failed`
- `item.started`、`item.updated`、`item.completed`
- `error`

`item.type` 当前覆盖 `agent_message`、`reasoning`、`command_execution`、
`file_change`、`collab_tool_call` 和通用 `tool_call`。`turn.completed.usage`
包含 input、cached input、output、reasoning output 与 total token 数。

这实现的是 Codex `exec` 的终端与 JSONL 流式核心语义；全屏 Ratatui TUI、
App Server v2 和完整事件全集仍属于后续里程碑。

通过 stdin 提交提示：

```bash
printf '总结这个项目的架构' | ./codex-java exec -C /Users/bytedance/codex-java
```

恢复最近会话：

```bash
./codex-java resume --last "继续完成并运行测试"
```

列出会话：

```bash
./codex-java sessions
```

## 安全边界

当前 Java 版本会验证工作目录和补丁目标路径，并对明显的高风险、网络和破坏性命令执行审批。
但 Java `ProcessBuilder` 不提供 Codex Rust 版本在 macOS Seatbelt、Linux Landlock/bubblewrap
或 Windows restricted token 上的 OS 级隔离。因此：

- `workspace-write` 是路径与审批策略，不等价于完整内核沙箱。
- 不可信仓库建议在容器、虚拟机或外部沙箱中运行。
- 非交互环境无法显示审批提示，需审批的命令会被拒绝。
- `danger-full-access` 应仅用于可信环境。

## 与 Rust Codex 的映射

| Java 模块 | 上游主要参考 |
| --- | --- |
| `api/ResponsesApiClient` | `codex-rs/codex-api`, `codex-rs/codex-client` |
| `agent/CodexAgent` | `codex-rs/core` 的 turn loop、tool orchestrator |
| `agent/SessionStore` | `codex-rs/rollout`, `codex-rs/thread-store` |
| `agent/MultiAgentManager` | `core/src/agent/control`, `agent/registry`, `agent/status`, `session/input_queue` |
| `tools/MultiAgentTools` | `core/src/tools/handlers/multi_agents_v2` |
| `tools/ExecCommandTool` | `core/src/tools/handlers/unified_exec`, `runtimes/unified_exec` |
| `tools/ApplyPatchTool` | `codex-rs/apply-patch`, `core/src/tools/handlers/apply_patch` |
| `config` | `codex-rs/config`, `core/src/config` |
| `cli` | `codex-rs/cli`, `codex-rs/exec` |

详细范围见 [`COMPATIBILITY.md`](COMPATIBILITY.md)。
