# Portfolio Service 功能接口文档

本文档描述 `portfolioService` 提供的对外服务接口，包括 HTTP REST API 和 Dubbo gRPC 接口。

## 1. HTTP REST API

基础路径: `/api/portfolios`

### 1.1 组合管理

| 方法 | 路径 | 描述 | 请求参数 (Body/Query) | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| **POST** | `/` | 创建组合 | `PortfolioCreateRequest` (name, visibility, tags, timezone) | `PortfolioResponse` |
| **GET** | `/` | 组合列表查询 | `status`, `keyword`, `page`, `size` | `PageResult<PortfolioResponse>` |
| **GET** | `/{id}` | 获取组合详情 | Path: `id` | `PortfolioResponse` |
| **PATCH** | `/{id}` | 更新组合 | Path: `id`, Body: `PortfolioUpdateRequest` | `PortfolioResponse` |
| **DELETE** | `/{id}` | 归档组合 | Path: `id` | `Void` |

### 1.2 持仓管理 (Holdings)

| 方法 | 路径 | 描述 | 请求参数 | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| **POST** | `/{id}/holdings:bulk-upsert` | 批量更新持仓 | Path: `id`, Body: `HoldingUpsertRequest` | `List<HoldingResponse>` |
| **GET** | `/{id}/holdings` | 获取持仓列表 | Path: `id` | `List<HoldingResponse>` |

### 1.3 交易记录 (Trades)

| 方法 | 路径 | 描述 | 请求参数 | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| **POST** | `/{id}/trades` | 创建交易记录 | Path: `id`, Body: `TradeCreateRequest` | `Void` |
| **GET** | `/{id}/trades` | 查询交易列表 | Path: `id`, Query: `from`, `to`, `event_type`, `page`, `size` | `PageResult<TradeResponse>` |

### 1.4 分析与指标

| 方法 | 路径 | 描述 | 请求参数 | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| **GET** | `/{id}/valuation` | 获取估值信息 | Path: `id` | `ValuationResponse` |
| **GET** | `/{id}/metrics` | 获取组合指标 | Path: `id`, Query: `from`, `to` | `MetricsResponse` (目前为占位实现) |

### 1.5 系统

| 方法 | 路径 | 描述 | 请求参数 | 响应 |
| :--- | :--- | :--- | :--- | :--- |
| **GET** | `/health` | 健康检查 | - | "ok" |

---

## 2. Dubbo gRPC 接口

接口定义: `world.willfrog.alphafrogmicro.portfolio.idl.DubboPortfolioDubboServiceTriple`

该服务面向微服务内部调用（如 Frontend BFF 层）。

### 2.1 组合操作

*   **createPortfolio**: 创建组合
    *   Input: `CreatePortfolioRequest`
    *   Output: `PortfolioMessage`
*   **updatePortfolio**: 更新组合
    *   Input: `UpdatePortfolioRequest`
    *   Output: `PortfolioMessage`
*   **archivePortfolio**: 归档组合
    *   Input: `ArchivePortfolioRequest`
    *   Output: `PortfolioEmpty`
*   **getPortfolio**: 获取单个组合详情
    *   Input: `GetPortfolioRequest`
    *   Output: `PortfolioMessage`
*   **listPortfolio**: 获取组合列表
    *   Input: `ListPortfolioRequest`
    *   Output: `ListPortfolioResponse`

### 2.2 持仓操作

*   **holdingsBulkUpsert**: 批量更新持仓
    *   Input: `HoldingsBulkUpsertRequest`
    *   Output: `HoldingsListResponse`
*   **holdingsList**: 获取持仓列表
    *   Input: `HoldingsListRequest`
    *   Output: `HoldingsListResponse`

### 2.3 交易操作

*   **tradesCreate**: 创建交易
    *   Input: `TradesCreateRequest`
    *   Output: `PortfolioEmpty`
*   **tradesList**: 获取交易列表
    *   Input: `TradesListRequest`
    *   Output: `TradesListResponse`

### 2.4 分析操作

*   **valuation**: 获取估值
    *   Input: `ValuationRequest`
    *   Output: `ValuationResponse`
*   **metrics**: 获取指标
    *   Input: `MetricsRequest`
    *   Output: `MetricsResponse`

## 3. 数据模型说明

### 3.1 核心状态 (Status)
*   `ACTIVE`: 活跃
*   `ARCHIVED`: 已归档

### 3.2 交易类型 (EventType)
*   `BUY`: 买入
*   `SELL`: 卖出
*   `DEPOSIT`: 入金
*   `WITHDRAW`: 出金
*   `DIVIDEND`: 分红
*   `FEE`: 费用

### 3.3 补充说明
*   `X-User-Id` Header 是必须的，用于标识操作用户。
*   `Metrics` 接口目前仅返回占位数据，后续将集成 AnalysisService 进行实时计算。
