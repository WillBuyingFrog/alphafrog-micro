# AlphaFrog-Micro

> 一站式 A 股数据微服务平台 —— 股票、基金、指数数据的采集、存储与分析

## 项目简介

AlphaFrog-Micro 是一个基于 **Java Spring Boot + Apache Dubbo + RabbitMQ** 的微服务架构项目，旨在提供国内 A 股市场的股票、基金、指数等金融数据的采集、存储、查询与分析能力。=

**目前使用的技术栈**：
- Java 微服务：Spring Boot 3.x + Apache Dubbo 3.x + gRPC/Proto
- 消息队列：RabbitMQ
- 数据存储：PostgreSQL + Redis
- 服务注册：Nacos


---

## 功能模块

### 核心数据服务

| 模块 | 说明 | 主要功能 |
|------|------|----------|
| **domesticStockService** | 境内股票服务 | 股票基本信息查询、关键词搜索、日线行情查询 |
| **domesticFundService** | 境内基金服务 | 基金信息查询、净值查询、持仓查询、关键词搜索 |
| **domesticIndexService** | 境内指数服务 | 指数信息查询、日线行情、成分股权重查询 |
| **domesticFetchService** | 数据爬取服务 | 支持同步/异步爬取股票、基金、指数数据，基于 RabbitMQ 的任务调度 |
| **portfolioService** | 投资组合服务 | 组合管理、持仓 CRUD、交易记录管理 |
| **frontend** | API 网关 | 统一对外暴露 RESTful API，路由请求至各微服务 |
| **agentService** | Agent 服务 | 自然语言任务执行、工具调用、事件流与结果管理 |
| **pythonSandboxService** | Python 沙箱服务 | 安全执行 Python 计算任务，返回标准输出与文件产物 |
| **pythonSandboxGatewayService** | 沙箱网关服务 | Dubbo → HTTP 转发，屏蔽协议差异 |

---

## v0.4 版本功能（当前版本）

### Agent 能力增强
- **多轮对话支持**: 基于消息历史的追问能力，支持上下文压缩（滑动窗口策略，默认保留最近5轮）
- **会话重命名**: 支持修改 Run 会话标题 (`PUT /api/agent/runs/{runId}`)
- **模型配置重构**: 支持 endpoint 级模型配置，返回 validProviders 列表
- **Run Config 执行层生效**: `webSearch`/`codeInterpreter`/`smartRetrieval` 配置真正影响执行行为
- **Prompt 外置与热加载**: Prompts 拆分到独立文件，支持运行时热更新
- **事件模型信息**: 关键事件记录 endpoint 和 model，支持前端展示"由 XXX 模型生成"

### 管理后台 (Admin)
- **Agent 运行监控**: 查询/详情/强制停止运行中的 Agent Run
- **系统配置管理**: 动态调整全局配置参数
- **用户额度调整**: 管理员手动调整用户信用额度，带审计日志
- **Magic Password 外部化**: 安全配置移至 `.env` 文件

### 工具与搜索优化
- **指数搜索相关性排序**: 按精确/前缀匹配排序，避免基础指数被截断
- **Python 精修增强**: 展示完整失败历史，倒序排列，提升调试效率
- **Artifacts 下载路径**: 改为 run 维度 (`/runs/{runId}/artifacts/{id}`)，兼容旧路由

### 额度与审批系统
- **完整额度链路**: 申请 → 审批 → 消耗 → 台账
- **审计日志**: 审批操作全量记录，支持追溯
- **乐观锁与幂等**: 审批并发控制与幂等机制

## v0.3-phase2 版本功能

### Agent 能力
- **Agent Run**: 创建/查询/取消/续做/状态/事件流/结果
- **工具调用**: Search + MarketData + PythonSandbox
- **数据集落盘**: 日线数据落盘并通过 dataset_id 传递
- **Python 沙箱**: 安全运行计算脚本并返回结果
- **多数据集挂载**: 支持 dataset_ids 同步挂载
- **并行/图编排执行**: 支持并行任务规划、依赖编排与 sub_agent fan-out 执行
- **子代理步骤级事件**: 支持子代理计划/步骤开始/步骤完成/失败等事件观测
- **Python 代码执行自修复**: executePython 失败后可基于反馈自动重试与修正
- **Prompt 本地配置化**: 支持通过 `agent-llm.local.json` 配置模型提示词与字段说明（简体中文主体）

## v0.2 版本功能（数据服务基础能力）

### 数据服务
- **股票**: 股票信息查询、关键词搜索、日线行情数据
- **基金**: 基金信息查询、净值数据、持仓数据、关键词搜索
- **指数**: 指数信息查询、日线行情、成分股权重数据
- **交易日历**: 交易日查询、交易日数量统计

### 数据采集
- **同步爬取**: 通过 API 即时爬取并返回数据
- **异步任务**: 通过 RabbitMQ 消息队列的任务调度，支持批量历史数据爬取
- **任务管理**: 支持任务 UUID 查询、状态追踪、结果回传

### 投资组合服务 (v0.2-portfolio 新增)
- **投资组合管理**: 创建、查询、更新、归档投资组合；支持多种组合类型（实盘、策略、模型）
- **持仓管理**: 批量更新持仓、查询持仓明细；支持多市场、多空方向
- **交易记录**: 记录买入/卖出/分红/费用等多种交易类型，支持分页查询和时间段筛选
- **估值与指标**: 实时估值查询、组合业绩指标计算（收益、波动率、最大回撤等）
- **策略投资组合**: 策略定义管理、目标权重配置、回测运行管理、策略净值跟踪（20260116新增）
- **完整 API**: 提供 RESTful HTTP API 和 Dubbo gRPC 双接口
- **权限控制**: 基于用户 ID 的数据隔离与访问控制

### 基础设施
- **Docker 部署**: 支持 Docker Compose 一键部署
- **数据完整性检查**: 指数数据完整性校验 + Redis 缓存
- **Debug 模式**: 可配置的详细日志输出

---

## 便捷部署

### 从 v0.3 迁移

若您已部署 v0.3 版本，按以下步骤升级至 v0.4：

#### 1. 备份数据
```bash
# 备份 PostgreSQL 数据库
pg_dump -h your_host -U your_user -d alphafrog > alphafrog_v0.3_backup_$(date +%Y%m%d).sql

# 备份现有配置
cp agentService/config/agent-llm.local.json agentService/config/agent-llm.local.json.v0.3.backup
```

#### 2. 更新代码
```bash
git fetch origin
git checkout v0.4
# 或 git checkout tags/v0.4.0
```

#### 3. 执行数据库迁移（按时间顺序）
```bash
# 连接 PostgreSQL 后依次执行以下迁移脚本
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260122_agent.sql
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260210_agent_expired.sql
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260212_auth_invite_reset.sql
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260213_agent_credit.sql
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260213_admin_credit_governance.sql
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260213_agent_runs_perf.sql
psql -h your_host -U your_user -d alphafrog -f portfolio_schema_migration_20260216_multi_turn_message.sql
```

#### 4. 更新配置文件

**agent-llm.local.json** - 配置结构升级：
```json
{
  "defaultEndpoint": "openrouter",
  "defaultModel": "moonshotai/kimi-k2.5",
  "endpoints": {
    "openrouter": {
      "baseUrl": "https://openrouter.ai/api/v1",
      "apiKey": "your-api-key",
      "models": {
        "moonshotai/kimi-k2.5": {
          "displayName": "Kimi K2.5",
          "baseRate": 0.3,
          "validProviders": ["moonshotai/int4", "fireworks"]
        }
      }
    }
  }
}
```

**环境变量** - 新增 Magic Password：
```bash
# 在 .env 文件中添加
ADMIN_MAGIC_PASSWORD=your_secure_password_here
```

#### 5. 部署 Prompt 文件
```bash
# 将 prompts 目录部署到配置目录
rsync -av agentService/config/prompts/ /path/to/config/prompts/
```

#### 6. 构建并重启服务
```bash
# 构建镜像
bash build_all_images.sh

# 滚动重启服务（推荐顺序）
docker-compose up -d --no-deps --build agent-service
docker-compose up -d --no-deps --build admin-service
docker-compose up -d --no-deps --build frontend
```

---

### 全新部署

若您是首次部署 AlphaFrog，请按以下步骤进行：

#### 1. 环境准备
```bash
# 克隆代码
git clone <repository-url>
cd alphafrog-micro

# 创建环境文件
cp .env.example .env
# 编辑 .env，填写必要配置（数据库、Redis、API Keys 等）
```

#### 2. 数据库初始化
```bash
# 创建数据库（若不存在）
createdb -h your_host -U your_user alphafrog

# 执行完整 Schema（v0.4 已包含所有历史变更）
psql -h your_host -U your_user -d alphafrog -f alphafrog_schema_full.sql
# 或按顺序执行 portfolio_schema.sql + 各 migration 文件
```

#### 3. 配置 LLM
```bash
# 复制示例配置
cp agentService/config/agent-llm.local.example.json agentService/config/agent-llm.local.json

# 编辑配置，填入您的 API Keys
vim agentService/config/agent-llm.local.json
```

#### 3.1 配置 Search LLM（Market News 非兼容重构）

> 自 2026-02 版本起，`search-llm.local.json` 已切换为 **feature + profiles** 结构。  
> 旧结构（如 `defaultProvider`、`marketNews.*` 顶层字段）已废弃，加载时会显式报错，不再 fallback。

```bash
# 复制示例配置
cp agentService/config/search-llm.local.example.json agentService/config/search-llm.local.json

# 编辑配置，填入您的 API Keys
vim agentService/config/search-llm.local.json
```

最小可运行示例（仅展示关键字段）：

```json
{
  "providers": {
    "exa": {
      "baseUrl": "https://api.exa.ai",
      "apiKey": "your-key",
      "searchPath": "/search",
      "authHeader": "x-api-key"
    }
  },
  "features": {
    "marketNews": {
      "defaultProvider": "exa",
      "defaultLimit": 8,
      "profiles": [
        {
          "name": "cn",
          "query": "今日A股市场行情",
          "includeDomains": ["sina.com.cn"],
          "languages": ["zh"]
        }
      ]
    }
  }
}
```

必填项：
- `features.marketNews.profiles[].name`
- `features.marketNews.profiles[].query` 或 `features.marketNews.profiles[].queries`

默认行为：
- profile 未指定 `provider` 时使用 `features.marketNews.defaultProvider`
- API 入参未指定 `limit` 时使用 `features.marketNews.defaultLimit`

#### 4. 构建并启动
```bash
# 一键构建所有镜像
bash build_all_images.sh

# 启动全部服务
docker-compose up -d

# 查看服务状态
docker-compose ps
```

#### 5. 验证部署
```bash
# 检查健康状态
curl http://localhost:8090/actuator/health

# 测试 Agent 创建
curl -X POST http://localhost:8090/api/agent/runs \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer YOUR_TOKEN" \
  -d '{"userGoal": "查询沪深300最新净值"}'
```

#### 6. 创建首个管理员（可选）
```bash
# 在数据库中设置管理员标志
psql -h your_host -U your_user -d alphafrog -c \
  "UPDATE alphafrog_user SET is_admin = true, status = 'ACTIVE' WHERE email = 'your@email.com';"
```

---

### 版本规划

- **v0.5 规划**: 可观测性增强、金融特色压测、性能优化


---

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+
- Docker & Docker Compose
- PostgreSQL 14+
- Redis 6+
- Nacos 2.x
- RabbitMQ 3.13

### 构建项目
```bash
# 清理并编译所有模块
mvn clean compile

# 安装到本地仓库
mvn install -DskipTests
```

### Docker 部署
```bash
# 构建所有服务镜像
bash build_all_images.sh

# 启动服务 (需配置 docker-compose.yml 中的环境变量)
docker-compose up -d
```

---

## 文档导航

| 文档 | 说明 |
|------|------|
| [deploy_guide.md](./deploy_guide.md) | 完整部署指南（构建、Docker 打包、服务上线） |
| [portfolio_schema.sql](./portfolio_schema.sql) | Portfolio 服务数据库 Schema |
| [alphafrog-wiki/agent-api-guide.md](./alphafrog-wiki/agent-api-guide.md) | Agent 对外 API 文档 |

> Frontend 对外接口文档将后续统一重构发布。

---

## 项目结构

```
alphafrog-micro/
├── common/                    # 公共模块 (DAO, DTO, Utils)
├── interface/                 # Dubbo 接口定义 (Proto)
├── domesticStockService/      # 股票服务
├── domesticStockApi/          # 股票服务 Proto 定义
├── domesticFundService/       # 基金服务
├── domesticFundApi/           # 基金服务 Proto 定义
├── domesticIndexService/      # 指数服务
├── domesticIndexApi/          # 指数服务 Proto 定义
├── domesticFetchService/      # 数据爬取服务
├── portfolioService/          # 投资组合服务
├── portfolioApi/              # 投资组合服务 Proto 定义
├── agentService/              # Agent 服务
├── agentApi/                  # Agent Dubbo Proto
├── pythonSandboxService/      # Python 沙箱服务
├── pythonSandboxGatewayService/ # 沙箱网关服务
├── pythonSandboxApi/          # 沙箱 Dubbo Proto
├── frontend/                  # API 网关
├── analysisService/           # 分析服务 (Python Django)
└── docker-compose.yml         # Docker Compose 配置
```

---

## License

本项目仅供学习交流使用。

---

一切从相信开始。2019.11.27
