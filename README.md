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

---

## v0.2 版本功能 (当前版本)

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
- **组合管理**: 创建、查询、更新、删除投资组合
- **持仓管理**: 添加、修改、移除持仓
- **交易记录**: 记录买入/卖出交易历史
- **Dubbo 接口**: 提供 gRPC/Proto 定义的内部调用接口

### 基础设施
- **Docker 部署**: 支持 Docker Compose 一键部署 (Kafka KRaft 模式)
- **数据完整性检查**: 指数数据完整性校验 + Redis 缓存
- **Debug 模式**: 可配置的详细日志输出

---

## v0.3 版本规划 (Agent 功能)


v0.3 将引入 **AI Agent 能力**。具体而言， Portfolio 服务将成为具备理解用户意图、主动规划任务、调用工具并等能力，并根据用户需求生成不同复杂度分析报告的智能体。

### 期望目标

- 支持高并发下的自然语言查询（"我的组合今天表现如何？"）
- 支持专业用户的复杂分析指令（"分析我的组合在过去一个月相对于沪深300的超额收益"）

### 技术选型
- **Spring AI**: 底层模型接入、统一配置管理
- **LangChain4j**: Agent 编排、ReAct Loop、任务规划器
- **向量数据库**: PostgreSQL (pgvector) 用于 RAG 知识库


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
| [api_guide.md](./api_guide.md) | API 接口文档 |
| [portfolio_schema.sql](./portfolio_schema.sql) | Portfolio 服务数据库 Schema |

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
├── frontend/                  # API 网关
├── analysisService/           # 分析服务 (Python Django)
└── docker-compose.yml         # Docker Compose 配置
```

---

## License

本项目仅供学习交流使用。

---

一切从相信开始。2019.11.27