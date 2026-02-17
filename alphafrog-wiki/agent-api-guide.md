# Agent API Guide

> 说明：本文件描述当前通过 frontend 暴露的 Agent 相关 HTTP 接口（基于 v0.4 代码）。

## 统一响应结构
所有接口返回 `ResponseWrapper<T>`：

- code: string，业务状态码（如 200/400/401/500 等）
- message: string，响应消息
- data: T，业务数据
- timestamp: long，服务端时间戳
- requestId: string，可选链路追踪 ID

常见 code：
- 200 成功
- 400 参数错误
- 401 未授权
- 500 系统内部错误
- 520 外部服务调用错误

## 认证说明
- 所有 `/api/agent/**` 接口要求登录态（Spring Security）。
- 未登录统一返回 401。

## 接口列表

### 1) GET /api/agent/tools
**说明**：列出当前可用 Agent 工具。

**响应 data: AgentToolResponse[]**
- name: 工具名
- description: 工具描述
- parametersJson: 工具参数 JSON Schema 字符串

---

### 2) POST /api/agent/runs
**说明**：创建一个 Agent Run。

**请求体: AgentRunCreateRequest**
- message: string，用户问题（必填）
- context: object，可选上下文
- idempotencyKey: string，可选幂等键
- modelName: string，可选模型名
- endpointName: string，可选端点名

**响应 data: AgentRunResponse**
- id: runId
- status: 运行状态（RECEIVED/EXECUTING/COMPLETED/...）
- currentStep: 当前步数
- maxSteps: 最大步数
- planJson: 计划 JSON
- snapshotJson: 快照 JSON（执行完成后填充）
- lastError: 失败时错误信息
- ttlExpiresAt: 过期时间
- startedAt / updatedAt / completedAt: 时间戳
- ext: 扩展 JSON（内部元信息）

---

### 3) GET /api/agent/runs/{runId}
**说明**：查询 run 基本信息。

**响应 data: AgentRunResponse**
- 字段同上

---

### 4) GET /api/agent/runs
**说明**：分页查询当前用户历史 run 列表。

**查询参数**
- limit: int，默认 20，最大 100
- offset: int，默认 0
- status: string，可选（RECEIVED/PLANNING/EXECUTING/WAITING/SUMMARIZING/COMPLETED/FAILED/CANCELED）
- days: int，可选（最近 N 天；未传或 <=0 时使用后端默认值）

**响应 data: AgentRunListResponse**
- items: AgentRunListItemResponse[]
  - id: runId
  - message: 用户原始目标（来自 ext.user_goal）
  - status: 状态
  - createdAt: 创建时间
  - completedAt: 完成时间（可空）
  - hasArtifacts: 是否有产物（当前实现固定 false）
- total: 总条数
- hasMore: 是否还有下一页

---

### 5) GET /api/agent/runs/{runId}/status
**说明**：查询 run 当前状态与最近事件信息。

**响应 data: AgentRunStatusResponse**
- id: runId
- status: 运行状态
- phase: 细分阶段（如 PLANNING / EXECUTING / EXECUTING_TOOL / SUMMARIZING / PAUSED / COMPLETED）
- currentTool: 最近事件为 TOOL_CALL_STARTED 时解析出的 tool_name
- lastEventType: 最近事件类型
- lastEventAt: 最近事件时间
- lastEventPayloadJson: 最近事件 payload JSON
- planJson: 当前计划 JSON（优先来自运行态缓存）
- progressJson: 当前进度 JSON

---

### 6) GET /api/agent/runs/{runId}/result
**说明**：获取 run 最终结果。

**响应 data: AgentRunResultResponse**
- id: runId
- status: COMPLETED / FAILED / ...
- answer: 最终回答（文本）
- payloadJson: 额外结果 JSON（可为空）

**注意**：
- 若 run 未完成，返回 HTTP 202，message 为“任务未完成”。

---

### 7) GET /api/agent/runs/{runId}/events
**说明**：按序列获取事件流。

**查询参数**
- after_seq: int，起始序号（默认 0）
- limit: int，返回条数（默认 200，1~500）

**响应 data: AgentRunEventsPageResponse**
- items: AgentRunEventResponse[]
  - id: 事件 ID
  - runId: runId
  - seq: 序号（同一 run 内按 Redis 原子递增生成）
  - eventType: 事件类型
  - payloadJson: 事件 payload JSON
  - createdAt: 创建时间
- nextAfterSeq: 下一页游标
- hasMore: 是否还有更多

---

### 8) GET /api/agent/runs/{runId}/artifacts
**说明**：获取 run 产物列表（按用户类型返回不同范围）。

**响应 data: AgentArtifactResponse[]**
- artifactId: 产物 ID
- type: 产物类型（文本/图片/文件等）
- name: 名称
- contentType: MIME 类型
- url: 下载地址
- metaJson: 额外元信息 JSON
- createdAt: 创建时间
- expiresAtMillis: 过期毫秒时间戳

当前行为：
- 普通用户：返回最终一次 Python 执行脚本 + 对应数据文件（csv/meta）。
- 管理员：返回全部中间脚本与数据文件。
- 过期产物不返回（按 retentionDays 策略）。

---

### 9) GET /api/agent/artifacts/{artifactId}/download
**说明**：下载指定产物内容。

**行为**
- 仅允许下载当前登录用户可见的 artifact。
- 文件过大时返回业务错误（由后端下载大小上限控制）。

---

### 10) DELETE /api/agent/runs/{runId}
**说明**：删除指定 run。

**当前行为**
- 运行中 run 不允许删除（需先停止）
- 删除后 run 查询返回“数据未找到”
- 事件表通过外键级联删除

**响应 data**
- "ok"

---

### 11) POST /api/agent/runs/{runId}:cancel
**说明**：取消 run。

**响应 data: AgentRunResponse**
- 字段同上

---

### 12) POST /api/agent/runs/{runId}:pause
**说明**：暂停 run（进入 WAITING/PAUSED 状态）。

**响应 data: AgentRunResponse**
- 字段同上

---

### 13) POST /api/agent/runs/{runId}:resume
**说明**：续做 run（支持从 FAILED/CANCELED/WAITING 继续）。

**请求体: AgentRunResumeRequest（可选）**
- planOverrideJson: string，可选计划覆盖 JSON

**响应 data: AgentRunResponse**
- 字段同上

---

### 14) POST /api/agent/runs/{runId}:export
**说明**：导出 run。

**请求体: AgentExportRequest**
- format: string，导出格式（自定义）

**响应 data: AgentExportResponse**
- exportId: 导出 ID
- status: 导出状态
- url: 下载地址（可为空）
- message: 说明信息

---

### 15) POST /api/agent/runs/{runId}/feedback
**说明**：提交反馈。

**请求体: AgentFeedbackRequest**
- rating: Integer，评分（可为空）
- comment: string，文字反馈
- tags: string[]，标签列表
- payload: object，额外信息

**响应 data**
- "ok"

---

### 16) GET /api/agent/config
**说明**：获取前端可直接消费的 Agent 配置。

**响应 data: AgentConfigResponse**
- retentionDays.normal: 普通用户产物保留天数
- retentionDays.admin: 管理员产物保留天数
- maxPollingInterval: 建议轮询间隔（秒）
- features.parallelExecution: 是否启用并行执行
- features.pauseResume: 是否支持暂停/恢复

## 事件类型与 payload（v0.4）

以下为当前代码路径中的主要事件类型（按功能分组）：

### Run 生命周期
- RUN_RECEIVED
- EXECUTION_STARTED
- COMPLETED
- FAILED
- CANCELED
- PAUSED
- RESUMED

### 规划与并行编排
- PLAN_STARTED
- PLAN_CREATED
- PLAN_INVALID
- PLAN_REUSED
- PLAN_OVERRIDE_USED
- PARALLEL_GRAPH_DECISION
- PARALLEL_EXECUTION_BLOCKED
- PARALLEL_TASK_STARTED
- PARALLEL_TASK_FINISHED
- PARALLEL_TASK_FAILED_INTERNAL
- PARALLEL_FALLBACK_TO_SERIAL

### 工具与子代理
- TOOL_CALL_STARTED
- TOOL_CALL_FINISHED
- SUB_AGENT_PLAN_CREATED
- SUB_AGENT_PLAN_RETRY
- SUB_AGENT_STEP_STARTED
- SUB_AGENT_STEP_FINISHED
- SUB_AGENT_PYTHON_REFINED
- SUB_AGENT_COMPLETED
- SUB_AGENT_FAILED

### 交互与导出
- FEEDBACK_RECEIVED
- EXPORT_REQUESTED

### 典型 payload 字段（示例）
- RUN_RECEIVED: `user_goal`, `context_json`, `idempotency_key`, `model_name`, `endpoint_name`
- PLAN_CREATED: `plan`, `strategy`
- PLAN_INVALID: `reason`, `raw_plan`
- TOOL_CALL_STARTED: `tool_name`, `parameters`
- TOOL_CALL_FINISHED: `tool_name`, `success`, `result_preview`
- PARALLEL_GRAPH_DECISION: `plan_valid`, `all_done`, `paused`, `final_answer_blank`, `handled`, `reason`
- PARALLEL_TASK_STARTED: `task_id`, `task_type`, `tool`, `depends_on`, `args`
- PARALLEL_TASK_FINISHED: `task_id`, `success`, `summary`
- SUB_AGENT_PYTHON_REFINED: `task_id`, `attempt`, `success`, `error`, `llm_snapshot`(仅失败时可能出现)
- FEEDBACK_RECEIVED: `rating`, `comment`, `tags_json`, `payload_json`
- EXPORT_REQUESTED: `export_id`, `format`

## payload 大小策略（v0.4）

为防止单条事件体积失控，`AgentEventService` 增加了 payload 归一策略：

- 配置项：
  - `agent.event.payload.max-chars`（默认 10000）
  - `agent.event.payload.preview-chars`（默认 4096）
- 行为：
  - 超过上限时，不直接写入原始 payload；改为写入截断摘要对象：
    - `truncated`
    - `event_type`
    - `original_size`
    - `max_size`
    - `payload_preview`

说明：该策略用于保护事件表体积与查询稳定性，前端应容忍少数事件 payload 为“截断摘要”形态。
