# AlphaFrog-Micro

> 一站式 A 股数据微服务平台 —— 股票、基金、指数数据的采集、存储与分析

## 项目简介

AlphaFrog-Micro 是一个基于 **Java Spring Boot + Apache Dubbo + Kafka** 的微服务架构项目，旨在提供国内 A 股市场的股票、基金、指数等金融数据的采集、存储、查询与分析能力。=

**目前使用的技术栈**：
- Java 微服务：Spring Boot 3.x + Apache Dubbo 3.x + gRPC/Proto
- 消息队列：Apache Kafka (KRaft 模式)
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
| **domesticFetchService** | 数据爬取服务 | 支持同步/异步爬取股票、基金、指数数据，基于 Kafka 的任务调度 |
| **portfolioService** | 投资组合服务 | 组合管理、持仓 CRUD、交易记录管理 |
| **frontend** | API 网关 | 统一对外暴露 RESTful API，路由请求至各微服务 |
| **agentService** | Agent 服务 | 自然语言任务执行、工具调用、事件流与结果管理 |
| **pythonSandboxService** | Python 沙箱服务 | 安全执行 Python 计算任务，返回标准输出与文件产物 |
| **pythonSandboxGatewayService** | 沙箱网关服务 | Dubbo → HTTP 转发，屏蔽协议差异 |

---

## v0.3-phase2 版本功能（当前版本）

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
- **异步任务**: 通过 Kafka 消息队列的任务调度，支持批量历史数据爬取
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
- **Docker 部署**: 支持 Docker Compose 一键部署 (Kafka KRaft 模式)
- **数据完整性检查**: 指数数据完整性校验 + Redis 缓存
- **Debug 模式**: 可配置的详细日志输出

---

## v0.4 TODO（规划）

- 断点恢复能力完善
- 中间结果缓存策略完善
- 工具搜索能力完善
- 上下文压缩策略
- 指标库与预定义分析能力


---

## 快速开始

### 环境要求
- JDK 17+
- Maven 3.8+
- Docker & Docker Compose
- PostgreSQL 14+
- Redis 6+
- Nacos 2.x
- Apache Kafka 3.x (KRaft 模式)

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
