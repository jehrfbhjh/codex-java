# Compatibility

基线：OpenAI Codex 仓库提交 `ea2046f`。

## 已实现

- Responses API HTTP/SSE
- 增量文本显示
- reasoning summary 增量事件
- Codex `exec --json` 风格 JSONL：thread、turn、item、error
- 命令、补丁、协作工具的 started/completed 生命周期事件
- turn 完成时的 token usage 汇总
- 函数和 custom tool call
- 工具输出重新注入上下文
- 多轮工具循环和最大轮数保护
- Shell 命令执行、超时、输出上限
- Codex Patch 的 Add File / Update File
- 工作区路径约束
- 高风险命令审批
- 根目录到 cwd 的 `AGENTS.md` 叠加
- JSONL 会话创建、恢复最近会话和列表
- 交互 CLI 与非交互 `exec`
- MultiAgent V2 的 `spawn_agent`、`send_message`、`followup_task`、`wait_agent`、
  `interrupt_agent`、`list_agents`
- `/root/...` AgentPath、任务树、状态 JSON、独立子会话和父级完成通知
- `fork_turns = none|all|N`、queue-only 邮箱、follow-up 唤醒和 V2 并发槽

## 部分兼容

- 配置：只覆盖核心顶层字段，尚未实现 profile、managed config、provider map。
- 鉴权：支持 API key；尚未移植 ChatGPT OAuth、device code 与 keyring。
- 沙箱：实现用户态路径和审批策略；尚无 OS 内核级隔离。
- `apply_patch`：支持新增与基于上下文的修改；删除、重命名、复杂歧义消解待补。
- 流协议：支持文本、reasoning summary、工具生命周期和 usage；rate limit、重试、
  context compaction、完整 typed item 和 provider-specific 元数据尚未覆盖。
- 会话：Java 自有 JSONL 格式遵循 ResponseItem 语义，但不保证 Rust SQLite/thread-store 双向兼容。
- MultiAgent V2：核心工具协议与内存态执行逻辑按提交 `ea2046f` 移植；尚未实现
  resident Agent 的 LRU 卸载/跨进程冷加载、完整 parent/root turn metadata、
  agent role 配置、service tier、telemetry 和 Tokio cancellation 的逐项等价。
- 历史 fork：支持当前 Java ResponseItem 历史中的用户 turn 边界；Rust rollout 的
  rollback marker、legacy inter-agent envelope 和 compacted rollout 边界尚未移植。

## 尚未实现

- Ratatui 对等 TUI
- App Server v2 JSON-RPC
- MCP client/server
- Skills、plugins、apps/connectors
- WebSocket/realtime
- compact、memory、guardian
- cloud tasks、remote control
- 图像、音频和 computer use
- exec server 和远程环境
- telemetry/OTel

这些能力应按独立里程碑移植，而不是继续堆叠到单一 core 模块。
