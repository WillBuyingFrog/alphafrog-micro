# Agent API Guide

> 说明：本文件描述当前通过 frontend 暴露的 Agent 相关 HTTP 接口，用于前端对接。

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
- planJson: 计划 JSON（当前可为空）
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

### 4) GET /api/agent/runs/{runId}/status
**说明**：查询 run 当前状态与最近事件信息。

**响应 data: AgentRunStatusResponse**
- id: runId
- status: 运行状态
- phase: 细分阶段（如 EXECUTING / EXECUTING_TOOL / SUMMARIZING 等）
- currentTool: 最近事件为 TOOL_CALL_STARTED 时解析出的 tool_name
- lastEventType: 最近事件类型
- lastEventAt: 最近事件时间
- lastEventPayloadJson: 最近事件 payload JSON

---

### 5) GET /api/agent/runs/{runId}/result
**说明**：获取 run 最终结果。

**响应 data: AgentRunResultResponse**
- id: runId
- status: COMPLETED / FAILED / ...
- answer: 最终回答（文本）
- payloadJson: 额外结果 JSON（可为空）

**注意**：
- 若 run 未完成，返回 HTTP 202，message 为“任务未完成”。

---

### 6) GET /api/agent/runs/{runId}/events
**说明**：按序列获取事件流。

**查询参数**
- after_seq: int，起始序号（默认 0）
- limit: int，返回条数（默认 200，1~500）

**响应 data: AgentRunEventsPageResponse**
- items: AgentRunEventResponse[]
  - id: 事件 ID
  - runId: runId
  - seq: 序号
  - eventType: 事件类型（RUN_RECEIVED/EXECUTION_STARTED/TOOL_CALL_STARTED/...）
  - payloadJson: 事件 payload JSON
  - createdAt: 创建时间
- nextAfterSeq: 下一页游标
- hasMore: 是否还有更多

---

### 7) GET /api/agent/runs/{runId}/artifacts
**说明**：获取 run 产物列表（如图像、文件）。

**响应 data: AgentArtifactResponse[]**
- artifactId: 产物 ID
- type: 产物类型（文本/图片/文件等）
- name: 名称
- contentType: MIME 类型
- url: 下载地址
- metaJson: 额外元信息 JSON
- createdAt: 创建时间

---

### 8) POST /api/agent/runs/{runId}:cancel
**说明**：取消 run。

**响应 data: AgentRunResponse**
- 字段同上

---

### 9) POST /api/agent/runs/{runId}:resume
**说明**：续做 run（从暂停/失败处尝试继续）。

**响应 data: AgentRunResponse**
- 字段同上

---

### 10) POST /api/agent/runs/{runId}:export
**说明**：导出 run。

**请求体: AgentExportRequest**
- format: string，导出格式（自定义）

**响应 data: AgentExportResponse**
- exportId: 导出 ID
- status: 导出状态
- url: 下载地址（可为空）
- message: 说明信息

---

### 11) POST /api/agent/runs/{runId}/feedback
**说明**：提交反馈。

**请求体: AgentFeedbackRequest**
- rating: Integer，评分（可为空）
- comment: string，文字反馈
- tags: string[]，标签列表
- payload: object，额外信息

**响应 data**
- "ok"

