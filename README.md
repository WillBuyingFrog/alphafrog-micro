# AlphaFrog-Micro

> 一站式 A 股数据微服务平台 —— 股票、基金、指数数据的采集、存储与分析

## 项目简介

AlphaFrog-Micro 是一个基于 **Java Spring Boot + Apache Dubbo + RabbitMQ** 的微服务架构项目，旨在提供国内 A 股市场的股票、基金、指数等金融数据的采集、存储、查询与分析能力。

**技术栈**：
- Java 微服务：Spring Boot 3.x + Apache Dubbo 3.x + gRPC/Proto
- 消息队列：RabbitMQ
- 数据存储：PostgreSQL + Redis
- 搜索引擎：MeiliSearch
- 服务注册：Nacos

---

## 功能概览

### 基础数据服务（传统 CRUD）

- **股票服务**：股票信息查询、关键词搜索、日线行情
- **基金服务**：基金信息查询、净值查询、持仓查询、关键词搜索
- **指数服务**：指数信息查询、日线行情、成分股权重查询
- **数据爬取**：同步/异步爬取股票、基金、指数数据，基于 RabbitMQ 的任务调度
- **投资组合**：组合管理、持仓管理、交易记录、策略投资、估值与业绩指标计算

### Agent 智能服务（核心功能）

- **自然语言任务执行**：通过自然语言描述目标，Agent 自动规划并执行数据查询、分析、Python 计算等任务
- **多轮对话与上下文管理**：支持基于消息历史的追问、上下文压缩（滑动窗口策略，默认保留最近 5 轮）
- **工具调用能力**：
  - 数据查询：股票/基金/指数实时数据
  - 搜索能力：MeiliSearch 本地搜索 + Perplexity/Exa 外部市场新闻搜索
  - Python 沙箱：安全执行 Python 计算任务，支持代码自修复与静态预检
- **可观测性体系**：
  - LLM 调用全链路追踪，原始请求/响应捕获
  - 跨工作流/子 Agent 上下文传播，traceId 支持
  - 性能指标采集（各环节耗时、LLM 调用时间戳）
  - 费用追踪（OpenRouter Spending、缓存命中监控）
- **任务执行可靠性**：
  - JSON Schema 结构化规划输出，支持步骤间数据传递
  - 四级重试预算（static/runtime/semantic/total）+ 失败分类感知恢复
  - Python 静态代码预检 + LLM 语义判断验证
- **会话管理**：会话重命名、运行监控、强制停止

### 基础设施

- **API 网关**：统一 RESTful API，路由请求至各微服务
- **管理后台**：Agent 运行监控、系统配置管理、用户额度调整（带审计日志）
- **额度与审批系统**：申请 → 审批 → 消耗 → 台账完整链路，支持乐观锁与幂等

---

## 本地协作提示

- 如果使用 `slock` 启动本项目相关 agent，且本机访问外网需要走代理，请显式开启 Node 环境代理：`NODE_USE_ENV_PROXY=1 npx @slock-ai/daemon --server-url https://api.slock.ai --api-key <your-key>`。

---

## 快速部署

### 全新部署（最简单方式）

#### 1. 克隆代码并配置环境变量

```bash
git clone <repository-url>
cd alphafrog-micro
cp .env.example .env
# 编辑 .env，填写数据库、Redis、API Keys 等必要配置
```

#### 2. 初始化数据库

```bash
# 创建数据库
createdb -h your_host -U your_user alphafrog

# 导入完整 Schema
psql -h your_host -U your_user -d alphafrog -f alphafrog_schema_full.sql
```

#### 3. 配置 LLM

```bash
# 复制并编辑 Agent LLM 配置
cp agentService/config/agent-llm.local.example.json agentService/config/agent-llm.local.json
vim agentService/config/agent-llm.local.json

# 复制并编辑 Search LLM 配置（市场新闻功能需要）
cp agentService/config/search-llm.local.example.json agentService/config/search-llm.local.json
vim agentService/config/search-llm.local.json
```

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
```

### 版本迁移

AlphaFrog 提供自动化的版本迁移工具，支持从任意旧版本迁移到最新版本。

#### 迁移前准备

```bash
# 备份数据库
pg_dump -h your_host -U your_user -d alphafrog > alphafrog_backup_$(date +%Y%m%d).sql

# 备份配置文件
cp .env .env.backup
cp agentService/config/agent-llm.local.json agentService/config/agent-llm.local.json.backup
```

#### 使用迁移工具

```bash
# 安装依赖
pip install psycopg2-binary pyyaml

# 配置迁移（二选一）
# 方式1：如果已有 .env 文件，直接使用（自动检测 AF_DB_MAIN_* 变量）
# 方式2：创建 YAML 配置文件
cp migrate/migrate_config.example.yml migrate/migrate_config.yml
vim migrate/migrate_config.yml

# 查看当前版本和待执行迁移
python migrate/migrate.py status

# 自动检测当前版本并迁移到最新发布版本
python migrate/migrate.py migrate --auto

# 指定版本范围迁移
python migrate/migrate.py migrate --from v0.2 --to v0.5

# 强制执行，跳过确认
python migrate/migrate.py migrate --auto --force
```

迁移工具会执行 SQL 脚本（数据库 DDL/DML 变更）和 Python 脚本（配置检查），每个版本的迁移脚本位于 `db/migrations/upgrades/<版本号>/` 目录下。

---

## 文档导航

| 文档 | 说明 |
|------|------|
| [deploy_guide.md](./deploy_guide.md) | 完整部署指南（构建、Docker 打包、服务上线） |
| [migrate/MIGRATION_DESIGN.md](./migrate/MIGRATION_DESIGN.md) | 迁移工具设计与使用说明 |
| [alphafrog-wiki/agent-api-guide.md](./alphafrog-wiki/agent-api-guide.md) | Agent 对外 API 文档 |

---

## 项目结构

```
alphafrog-micro/
├── common/                    # 公共模块 (DAO, DTO, Utils)
├── interface/                 # Dubbo 接口定义 (Proto)
├── domesticStockService/      # 股票服务
├── domesticFundService/       # 基金服务
├── domesticIndexService/      # 指数服务
├── domesticFetchService/      # 数据爬取服务
├── portfolioService/          # 投资组合服务
├── agentService/              # Agent 服务（核心）
├── externalInfoService/         # 外部信息服务
├── pythonSandboxService/      # Python 沙箱服务
├── frontend/                  # API 网关
└── docker-compose.yml         # Docker Compose 配置
```

---

## License

本项目仅供学习交流使用。

---

一切从相信开始。2019.11.27
