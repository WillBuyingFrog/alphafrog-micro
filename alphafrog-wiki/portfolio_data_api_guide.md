### Portfolio 服务 API

通用说明:
- 统一请求头: `X-User-Id` (必填)
- 统一响应: `ResponseWrapper`，结构为 `{ code, message, data, timestamp }`
- `page/size` 分页默认 `page=1`、`size=20`

ResponseWrapper 响应示例:
```json
{
    "code": "0",
    "message": "success",
    "data": { "id": 1 },
    "timestamp": 1737000000000
}
```

---

### 组合（Portfolio）相关接口

#### 创建组合

请求方法:POST

请求地址:`{{baseUrl}}/api/portfolios`

请求参数(JSON Body):
- `name`: 组合名称
- `visibility`: `private`/`shared`，默认 `private`
- `tags`: 标签数组，可选
- `portfolioType`: `REAL`/`STRATEGY`/`MODEL`，默认 `REAL`
- `baseCurrency`: 货币，如 `CNY`/`USD`，默认 `CNY`
- `benchmarkSymbol`: 基准代码，可选
- `timezone`: 时区，默认 `Asia/Shanghai`

返回内容解释:
创建组合并返回组合详情。`portfolioType/baseCurrency/benchmarkSymbol` 会写入组合主表。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": {
        "id": 1001,
        "userId": "u_001",
        "name": "实盘组合A",
        "visibility": "private",
        "tags": ["demo"],
        "portfolioType": "REAL",
        "baseCurrency": "CNY",
        "benchmarkSymbol": "000300.SH",
        "status": "active",
        "timezone": "Asia/Shanghai",
        "createdAt": "2026-01-16T01:10:00+08:00",
        "updatedAt": "2026-01-16T01:10:00+08:00"
    },
    "timestamp": 1737000000000
}
```

---

#### 查询组合列表

请求方法:GET

请求地址:`{{baseUrl}}/api/portfolios`

请求参数:
- `status`: `active`/`archived`，可选
- `keyword`: 名称关键字模糊搜索，可选
- `page`: 页码
- `size`: 每页条数

返回内容解释:
分页返回组合列表。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": {
        "items": [
            {
                "id": 1001,
                "userId": "u_001",
                "name": "实盘组合A",
                "visibility": "private",
                "tags": ["demo"],
                "portfolioType": "REAL",
                "baseCurrency": "CNY",
                "benchmarkSymbol": "000300.SH",
                "status": "active",
                "timezone": "Asia/Shanghai",
                "createdAt": "2026-01-16T01:10:00+08:00",
                "updatedAt": "2026-01-16T01:10:00+08:00"
            }
        ],
        "total": 1,
        "page": 1,
        "size": 20
    },
    "timestamp": 1737000000000
}
```

---

#### 查询组合详情

请求方法:GET

请求地址:`{{baseUrl}}/api/portfolios/{id}`

请求参数:
- `id`: 组合 ID

返回内容解释:
返回指定组合详情。

---

#### 更新组合

请求方法:PATCH

请求地址:`{{baseUrl}}/api/portfolios/{id}`

请求参数(JSON Body):
- `name`: 新名称，可选
- `visibility`: `private`/`shared`，可选
- `tags`: 标签数组，可选
- `portfolioType`: `REAL`/`STRATEGY`/`MODEL`，可选
- `baseCurrency`: `CNY`/`USD` 等，可选
- `benchmarkSymbol`: 基准代码，可选
- `status`: `active`/`archived`，可选

返回内容解释:
更新组合并返回最新详情。

---

#### 归档组合

请求方法:DELETE

请求地址:`{{baseUrl}}/api/portfolios/{id}`

返回内容解释:
将组合状态置为 `archived`，不会物理删除。

---

### 持仓（Holdings）接口

#### 持仓批量覆盖

请求方法:POST

请求地址:`{{baseUrl}}/api/portfolios/{id}/holdings:bulk-upsert`

请求参数(JSON Body):
- `items`: 持仓数组
  - `symbol`: 证券代码
  - `symbolType`: `stock/etf/index/fund`
  - `exchange`: 交易所，可选
  - `positionSide`: `LONG/SHORT`，默认 `LONG`
  - `quantity`: 数量（最多两位小数）
  - `avgCost`: 成本（最多两位小数）

返回内容解释:
**先删除旧持仓再批量写入**，因此是“全量覆盖”。返回最新持仓列表。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": [
        {
            "id": 1,
            "portfolioId": 1001,
            "symbol": "510300.SH",
            "symbolType": "etf",
            "exchange": "SSE",
            "positionSide": "LONG",
            "quantity": 100.00,
            "avgCost": 3.99,
            "updatedAt": "2026-01-16T01:15:00+08:00"
        }
    ],
    "timestamp": 1737000000000
}
```

---

#### 查询持仓列表

请求方法:GET

请求地址:`{{baseUrl}}/api/portfolios/{id}/holdings`

返回内容解释:
返回当前组合的持仓快照。

---

### 交易（Trades）接口

#### 新增交易流水

请求方法:POST

请求地址:`{{baseUrl}}/api/portfolios/{id}/trades`

请求参数(JSON Body):
- `items`: 交易数组
  - `symbol`: 证券代码
  - `eventType`: `BUY/SELL/DIVIDEND_CASH/DIVIDEND_STOCK/SPLIT/FEE/CASH_IN/CASH_OUT`
  - `quantity`: 数量（最多两位小数）
  - `price`: 成交价（最多两位小数，可为空）
  - `fee`: 手续费（最多两位小数，可为空）
  - `slippage`: 滑点，可为空
  - `tradeTime`: 成交时间（ISO 8601）
  - `settleDate`: 结算时间（ISO 8601，可为空）
  - `note`: 备注，可为空
  - `payloadJson`: 扩展信息 JSON 字符串，可为空

返回内容解释:
交易流水是事件账本，接口只追加记录，不会自动改写持仓；持仓可由交易流水重算。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": null,
    "timestamp": 1737000000000
}
```

---

#### 查询交易流水

请求方法:GET

请求地址:`{{baseUrl}}/api/portfolios/{id}/trades`

请求参数:
- `from`: 起始时间（ISO 8601），可选
- `to`: 结束时间（ISO 8601），可选
- `event_type`: 事件类型过滤，可选
- `page`: 页码
- `size`: 每页条数

返回内容解释:
按时间与事件类型分页返回交易流水。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": {
        "items": [
            {
                "id": 9001,
                "portfolioId": 1001,
                "symbol": "510300.SH",
                "eventType": "BUY",
                "quantity": 100.00,
                "price": 3.99,
                "fee": 0.20,
                "slippage": 0.01,
                "tradeTime": "2026-01-03T09:35:00+08:00",
                "settleDate": null,
                "note": "首次建仓"
            }
        ],
        "total": 1,
        "page": 1,
        "size": 20
    },
    "timestamp": 1737000000000
}
```

---

### 估值与指标接口

#### 估值（占位）

请求方法:GET

请求地址:`{{baseUrl}}/api/portfolios/{id}/valuation`

返回内容解释:
当前实现为 **mock**，仅根据持仓构造示例估值数据，后续会接入行情真实计算。

---

#### 指标（占位）

请求方法:GET

请求地址:`{{baseUrl}}/api/portfolios/{id}/metrics`

请求参数:
- `from`: 起始日期字符串
- `to`: 结束日期字符串

返回内容解释:
当前实现为占位返回（收益/波动/回撤全为 0）。

---

### 策略组合（Strategy）相关接口

#### 创建策略

请求方法:POST

请求地址:`{{baseUrl}}/api/strategies`

请求参数(JSON Body):
- `name`: 策略名称
- `description`: 描述，可选
- `ruleJson`: 策略规则 JSON 字符串，可选
- `rebalanceRule`: 调仓规则描述，可选
- `capitalBase`: 初始资金，可选
- `startDate`: 起始日期（YYYY-MM-DD），可选
- `endDate`: 结束日期（YYYY-MM-DD），可选
- `baseCurrency`: `CNY`/`USD` 等，可选
- `benchmarkSymbol`: 基准代码，可选

返回内容解释:
创建策略时会**同步创建一条组合记录**，并将 `portfolioType=STRATEGY`、`baseCurrency/benchmarkSymbol` 写入组合表。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": {
        "id": 2001,
        "portfolioId": 1002,
        "userId": "u_001",
        "name": "月度50/50",
        "description": "每月首个交易日 50% 买沪深300, 50% 买工商银行",
        "ruleJson": "{\"type\":\"monthly_rebalance\",\"rule\":\"first_trading_day\"}",
        "rebalanceRule": "monthly_first_trading_day",
        "capitalBase": 100000,
        "startDate": "2021-01-01",
        "endDate": "2025-01-01",
        "status": "active",
        "baseCurrency": "CNY",
        "benchmarkSymbol": "000300.SH",
        "createdAt": "2026-01-16T01:20:00+08:00",
        "updatedAt": "2026-01-16T01:20:00+08:00"
    },
    "timestamp": 1737000000000
}
```

---

#### 更新策略

请求方法:PATCH

请求地址:`{{baseUrl}}/api/strategies/{id}`

请求参数(JSON Body):
- `name/description/ruleJson/rebalanceRule/capitalBase/startDate/endDate/status`
- `baseCurrency/benchmarkSymbol`（会同步更新组合主表）

返回内容解释:
策略更新会同步更新组合名称/状态/基准等字段。

---

#### 查询策略列表

请求方法:GET

请求地址:`{{baseUrl}}/api/strategies`

请求参数:
- `status`: `active`/`archived`
- `keyword`: 名称关键字
- `page`: 页码
- `size`: 每页条数

---

#### 查询策略详情

请求方法:GET

请求地址:`{{baseUrl}}/api/strategies/{id}`

---

#### 归档策略

请求方法:DELETE

请求地址:`{{baseUrl}}/api/strategies/{id}`

返回内容解释:
会将策略与对应组合一起归档。

---

### 策略目标权重（Targets）相关接口

#### 目标权重批量覆盖

请求方法:POST

请求地址:`{{baseUrl}}/api/strategies/{id}/targets:bulk-upsert`

请求参数(JSON Body):
- `items`: 目标权重数组
  - `symbol`: 证券代码
  - `symbolType`: `stock/etf/index/fund`
  - `targetWeight`: 目标权重（最多六位小数）
  - `effectiveDate`: 生效日期（YYYY-MM-DD，可选）

返回内容解释:
与持仓类似，先清空旧目标再批量写入。

---

#### 查询目标权重列表

请求方法:GET

请求地址:`{{baseUrl}}/api/strategies/{id}/targets`

---

### 回测执行与净值接口

#### 创建回测任务

请求方法:POST

请求地址:`{{baseUrl}}/api/strategies/{id}/backtests`

请求参数(JSON Body):
- `startDate`: 起始日期（YYYY-MM-DD）
- `endDate`: 结束日期（YYYY-MM-DD）
- `paramsJson`: 回测参数 JSON 字符串，可选

返回内容解释:
返回一个 `pending` 状态的回测任务记录。实际回测结果由后续任务写入净值表。

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": {
        "id": 3001,
        "strategyId": 2001,
        "runTime": "2026-01-16T01:25:00+08:00",
        "startDate": "2021-01-01",
        "endDate": "2025-01-01",
        "status": "pending"
    },
    "timestamp": 1737000000000
}
```

---

#### 查询回测任务列表

请求方法:GET

请求地址:`{{baseUrl}}/api/strategies/{id}/backtests`

请求参数:
- `status`: 任务状态，可选
- `page`: 页码
- `size`: 每页条数

---

#### 查询回测净值曲线

请求方法:GET

请求地址:`{{baseUrl}}/api/strategies/{id}/backtests/{runId}/nav`

请求参数:
- `from`: 起始日期（YYYY-MM-DD，可选）
- `to`: 结束日期（YYYY-MM-DD，可选）
- `page`: 页码
- `size`: 每页条数

返回内容解释:
返回回测净值时间序列。`nav/returnPct/drawdown` 的含义：
- `nav`: 组合净值（累计因子）
- `returnPct`: 相对初始资金的累计收益率（通常为 `nav - 1`）
- `drawdown`: 回撤（通常为 `(当前净值 - 历史最高净值) / 历史最高净值`）
- `benchmarkNav`: 基准净值（如有）

返回内容示例:
```json
{
    "code": "0",
    "message": "success",
    "data": {
        "items": [
            {
                "id": 1,
                "runId": 3001,
                "tradeDate": "2024-01-02",
                "nav": 1.238500,
                "returnPct": 0.238500,
                "benchmarkNav": 1.180200,
                "drawdown": -0.052300
            }
        ],
        "total": 252,
        "page": 1,
        "size": 200
    },
    "timestamp": 1737000000000
}
```
