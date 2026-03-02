实际上的作者：Qwen2.5-Max（❤️Qwen Chat）


### AlphaFrog-Micro 部署指南
#### 第一部分：构建境内数据服务

本部分将指导您完成 **AlphaFrog-Micro** 项目中境内数据服务的构建流程。境内数据服务包括以下模块：
- 境内股票服务 (`domesticStockService`)
- 境内基金服务 (`domesticFundService`)
- 境内指数服务 (`domesticIndexService`)
- 境内数据爬取服务 (`domesticFetchService`)
- 接受请求的服务端 (`frontend`)

以下是详细的打包步骤，请确保您已经安装并配置好 Maven 环境，并且具备对项目的访问权限。

---

#### **1. 准备工作**
在开始打包之前，请确保以下条件已满足：
- 您已经克隆了项目代码到本地，并切换到 `<项目根目录>`。
- 您的 Maven 环境已正确配置，且能够正常执行 `mvn` 命令。
- 您具备对 `<common>` 和 `<interface>` 模块的访问权限，因为这些模块是所有服务的公共依赖。

---

#### **2. 打包流程**

##### **2.1 清理项目**
在项目根目录下执行以下命令，清理之前的编译和打包结果：
```bash
mvn clean
```
该命令会删除 `target` 目录以及所有生成的临时文件，确保后续操作在一个干净的环境中进行。

---

##### **2.2 编译模块**
接下来，针对每个境内数据服务模块，分别执行编译操作。以下是各模块的编译命令：

###### **2.2.1 境内股票服务 (domesticStockService)**
```bash
mvn compile -pl domesticStockService,common,interface
```
- `-pl` 参数指定了需要编译的模块，这里包括 `domesticStockService`、`common` 和 `interface`。
- `common` 和 `interface` 是所有服务的公共依赖模块，必须包含在编译命令中。

###### **2.2.2 境内基金服务 (domesticFundService)**
```bash
mvn compile -pl domesticFundService,common,interface
```

###### **2.2.3 境内指数服务 (domesticIndexService)**
```bash
mvn compile -pl domesticIndexService,common,interface
```

###### **2.2.4 境内数据爬取服务 (domesticFetchService)**
```bash
mvn compile -pl domesticFetchService,common,interface
```

###### **2.2.5 接受请求的服务端 (frontend)**
```bash
mvn compile -pl frontend,common,interface
```

---

##### **2.3 安装模块**
编译完成后，需要将模块安装到本地 Maven 仓库，以便后续使用。以下是各模块的安装命令：

###### **2.3.1 境内股票服务 (domesticStockService)**
```bash
mvn install -pl domesticStockService,common,interface
```

###### **2.3.2 境内基金服务 (domesticFundService)**
```bash
mvn install -pl domesticFundService,common,interface
```

###### **2.3.3 境内指数服务 (domesticIndexService)**
```bash
mvn install -pl domesticIndexService,common,interface
```

###### **2.3.4 境内数据爬取服务 (domesticFetchService)**
```bash
mvn install -pl domesticFetchService,common,interface
```

###### **2.3.5 接受请求的服务端 (frontend)**
```bash
mvn install -pl frontend,common,interface
```

---

#### **3. 验证打包结果**
执行完上述步骤后，您可以通过以下方式验证打包是否成功：
1. 检查本地 Maven 仓库（通常位于 `~/.m2/repository`）中是否存在对应的 `.jar` 文件。
2. 如果需要进一步验证，可以尝试运行单元测试或启动服务以确认功能正常。

---

#### **4. 注意事项**
- 在执行 `mvn compile` 或 `mvn install` 时，如果遇到依赖问题，请检查 `<common>` 和 `<interface>` 模块是否正确安装。
- 如果某些模块的代码未公开或需要额外授权，请确保您已获取相关权限。
- 若项目中存在敏感信息（如 `<数据库连接字符串>` 或 `<API密钥>`），请确保这些信息已正确配置在环境变量或配置文件中。

---

通过以上步骤，您已完成 **AlphaFrog-Micro** 境内数据服务的构建工作。


#### 第二部分：境内数据服务打包为 Docker 镜像

本部分将指导您完成 **AlphaFrog-Micro** 项目中境内数据服务的 Docker 镜像打包流程。通过将服务打包为 Docker 镜像，您可以更方便地进行容器化部署和管理。

---

#### **1. 准备工作**

在开始打包之前，请确保以下条件已满足：
- 您已经完成了第一部分的境内数据服务打包，并生成了对应的 `.jar` 文件。
- 您已经安装并配置好 Docker 环境，并且能够正常执行 `docker` 命令。
- 确保proxy已正确配置。

---

#### **2. 配置代理**

由于某些基础镜像可能需要从外部仓库拉取，建议您提前配置好魔法以避免网络问题。

---

#### **3. 打包 Docker 镜像**

##### **3.1 运行打包脚本**
在项目根目录下，运行以下命令以打包所有境内数据服务的 Docker 镜像：
```bash
bash build_all_images.sh
```

该脚本会依次调用每个模块的 `docker_build.sh` 脚本，完成镜像的构建。以下是 `build_all_images.sh` 的具体内容：
```bash
bash domesticFetchService/docker_build.sh
bash domesticFundService/docker_build.sh
bash domesticIndexService/docker_build.sh
bash domesticStockService/docker_build.sh
bash frontend/docker_build.sh
```

---

##### **3.2 单个模块的打包流程**
以 `frontend` 模块为例，以下是其 `docker_build.sh` 脚本的内容及说明：
```bash
export https_proxy=http://127.0.0.1:7890 http_proxy=http://127.0.0.1:7890 all_proxy=socks5://127.0.0.1:7890

docker build \
  --build-arg http_proxy=http://127.0.0.1:7890 \
  --build-arg https_proxy=http://127.0.0.1:7890 \
  -t alphafrog-micro-frontend:latest \
  ./frontend
```

###### **3.2.1 参数说明**
- `--build-arg`：传递代理参数到 Docker 构建过程中，用于解决基础镜像拉取时的网络问题。
- `-t`：指定生成的镜像名称和标签。例如，`alphafrog-micro-frontend:latest` 表示镜像名为 `alphafrog-micro-frontend`，标签为 `latest`。
- `./frontend`：指定 Dockerfile 所在的路径。

其他模块（如 `domesticFetchService`、`domesticFundService` 等）的打包流程与此类似，只是镜像名称和路径不同。

---

#### **4. 验证镜像**

执行完上述步骤后，您可以通过以下方式验证镜像是否成功生成：

1. 查看本地 Docker 镜像列表：
   ```bash
   docker images
   ```
   您应该能够看到类似以下输出：
   ```
   REPOSITORY                     TAG       IMAGE ID       CREATED          SIZE
   alphafrog-micro-frontend       latest    abcdef123456   10 minutes ago   500MB
   alphafrog-micro-domesticStock  latest    ghijkl789012   10 minutes ago   500MB
   ...
   ```



---

通过以上步骤，您已完成 **AlphaFrog-Micro** 境内数据服务的 Docker 镜像打包工作。


#### 第三部分：境内数据服务上线

本部分将指导您完成 **AlphaFrog-Micro** 境内数据服务的上线流程。通过容器化部署，您可以快速启动并运行所有服务。

---

#### **1. 准备工作**

在开始上线之前，请确保以下条件已满足：
- 您已经完成了第二部分的 Docker 镜像打包，并生成了所有服务的镜像。
- 您已经准备好以下基础设施：
    - **Nacos**：用于服务注册与配置管理。请参考 [Nacos 官方文档](https://nacos.io/) 进行安装和配置。
    - **PostgreSQL 数据库**：用于存储服务所需的数据。
    - **RabbitMQ**：用于消息队列通信。

此外，请确保您已拉取了 `alphafrog-micro-deploy` 仓库：
```bash
git clone https://github.com/WillBuyingFrog/alphafrog-micro-deploy
```

---

#### **2. 初始化数据库**

在 PostgreSQL 数据库中运行初始化脚本以创建所需的表结构和初始数据：

1. 进入 `alphafrog-micro-deploy` 仓库的 `ddl` 文件夹：
   ```bash
   cd alphafrog-micro-deploy/ddl
   ```

2. 执行 `sql_init.sql` 脚本：
   ```bash
   psql -h <your_db_host> -p <your_db_port> -U <your_db_user> -d <your_db_name> -f sql_init.sql
   ```
    - 替换 `<your_db_host>`、`<your_db_port>`、`<your_db_user>` 和 `<your_db_name>` 为您的实际数据库信息。
    - 如果需要密码验证，请输入对应的数据库密码。

---

#### **3. 配置服务上线脚本**

进入 `sh_example` 文件夹，根据您的环境信息填写每个服务的上线脚本。以下是具体步骤：

##### **3.1 修改单个服务的上线脚本**
以境内数据爬取服务（`domesticFetchService`）为例，以下是其上线脚本的内容及说明：

```bash
#! /bin/bash
app_name='alphafrog-micro-domestic-fetch-service'
# 检查容器是否正在运行
if [ "$(docker ps -q -f name=${app_name})" ]; then
    # 停止正在运行的容器
    docker stop ${app_name}
fi
# 检查容器是否存在
if [ "$(docker ps -a -q -f name=${app_name})" ]; then
    # 删除存在的容器
    docker rm ${app_name}
fi
docker run -d  -p 20082:20082 --name ${app_name} \
-e TZ=Asia/Shanghai \
-e AF_DB_MAIN_HOST="myhost" \
-e AF_DB_MAIN_PORT="myport" \
-e AF_DB_MAIN_USER="myuser" \
-e AF_DB_MAIN_PASSWORD="mypassword" \
-e AF_DB_MAIN_DATABASE="mydatabase" \
-e AF_RABBITMQ_HOST="myrabbitmqhost" \
-e AF_RABBITMQ_PORT="5672" \
-e AF_RABBITMQ_USER="myrabbitmquser" \
-e AF_RABBITMQ_PASS="myrabbitmqpass" \
-e NACOS_ADDRESS="myaddress" \
-e TUSHARE_TOKEN="mytoken" \
${app_name}
```

###### **3.1.1 参数说明**
- `app_name`：服务名称，必须与镜像名称一致。
- `-p 20082:20082`：映射容器端口到主机端口，`20082` 是固定值，请勿修改。
- `-e`：设置环境变量，以下是各变量的含义：
    - `TZ=Asia/Shanghai`：时区设置为上海时间。
    - `AF_DB_MAIN_*`：PostgreSQL 数据库的连接信息，请替换为您实际的数据库地址、端口、用户名、密码和数据库名。
    - `AF_RABBITMQ_HOST`：RabbitMQ 的主机地址。
    - `AF_RABBITMQ_PORT`：RabbitMQ 的端口（默认 5672）。
    - `AF_RABBITMQ_USER`：RabbitMQ 的用户名。
    - `AF_RABBITMQ_PASS`：RabbitMQ 的密码。
    - `NACOS_ADDRESS`：Nacos 的地址（如 `http://nacos-server:8848`）。
    - `TUSHARE_TOKEN`：Tushare API 的 Token（如果使用该功能）。

##### **3.2 配置其他服务**
重复上述步骤，依次修改 `sh_example` 文件夹中其他服务的上线脚本（如 `domesticStockService`、`domesticFundService` 等）。注意每个服务的 `app_name` 和端口可能不同，请根据实际情况调整。

##### **3.3 批量上线脚本说明**
- `start_all.sh`：用于批量启动所有服务。无需修改，默认会调用 `sh_example` 文件夹中的所有单个服务脚本。
- `stop_all.sh`：用于批量停止所有服务。同样无需修改。

---

#### **4. 可选配置：Docker Network**

为了提高服务间的网络通信效率，建议您为所有服务配置一个统一的 Docker 网络，以方便alphaFrog服务与Nacos等服务通信。以下是一个可能的操作步骤：

1. 创建 Docker 网络：
   ```bash
   docker network create alphafrog-network
   ```

2. 在每个服务的上线脚本中添加以下参数：
   ```bash
   --network alphafrog-network
   ```
   示例：
   ```bash
   docker run -d -p 20082:20082 --name ${app_name} \
   --network alphafrog-network \
   -e TZ=Asia/Shanghai \
   ...
   ```

通过配置 Docker 网络，可以避免服务间的连接问题（如 DNS 解析失败或网络延迟）。当然，若您已经有现成的 Docker 网络，那么直接让AlphaFrog各服务加入也可以。

---

#### **5. 启动服务**

完成所有配置后，运行以下命令以启动所有服务：
```bash
bash start_all.sh
```

该脚本会依次执行 `sh_example` 文件夹中的每个服务上线脚本，启动所有境内数据服务。

---

#### **6. 验证服务状态**

1. 查看运行中的容器：
   ```bash
   docker ps
   ```
   您应该能够看到类似以下输出：
   ```
   CONTAINER ID   IMAGE                                    COMMAND                  CREATED          STATUS          PORTS                    NAMES
   abcdef123456   alphafrog-micro-domestic-fetch-service   "java -jar app.jar"     1 minute ago     Up 1 minute     0.0.0.0:20082->20082/tcp alphafrog-micro-domestic-fetch-service
   ghijkl789012   alphafrog-micro-domestic-stock-service   "java -jar app.jar"     1 minute ago     Up 1 minute     0.0.0.0:20083->20083/tcp alphafrog-micro-domestic-stock-service
   ...
   ```

2. 访问 Nacos 控制台，确认所有服务均已成功注册。

---

#### **7. 注意事项**

- **端口冲突**：确保主机上的端口未被占用，尤其是 `20082` 等固定端口。
- **环境变量**：请仔细检查每个服务的环境变量配置，确保无误。
- **日志查看**：如果某个服务启动失败，可以通过以下命令查看日志：
  ```bash
  docker logs <container_name>
  ```
- **批量停止**：如果需要停止所有服务，可以运行：
  ```bash
  bash stop_all.sh
  ```

---

通过以上步骤，您已完成 **AlphaFrog-Micro** 境内数据服务的上线工作。


#### 第四部分：启动分析服务功能

本部分将指导您完成 **AlphaFrog-Micro** 项目中试验性 Django 分析服务的部署和启动流程。该服务允许用户发送 A 股数据分析请求，并通过 Celery 异步任务调用推理大模型（如 DeepSeek-R1）生成分析代码并执行，最终返回结果。

---

#### **1. 注意事项**

- **安全性警告**：由于该服务仍处于早期开发阶段，尚未实现对恶意输入的防护措施，因此 **强烈不建议将其部署在公网环境上**。请确保仅在受控的内网环境中使用该服务。
- **依赖环境**：该服务依赖于 Python 环境、Redis、Celery 和推理大模型的 API 密钥，请确保这些依赖已正确配置。
- **功能限制**：当前版本尚未支持结果下载功能，未来会逐步完善。

---

#### **2. 准备工作**

在开始部署之前，请确保以下条件已满足：
- 您已经安装并配置好以下工具：
    - **Python**（推荐版本 3.8+）
    - **Conda** 或 **Virtualenv**（用于管理 Python 环境）
    - **Redis**（用于 Celery 的消息队列和结果存储）
    - **Django** 和 **Celery**（可通过 `pip` 安装）
- 您已经获取了以下推理大模型的 API 密钥：
    - DeepSeek
    - OpenRouter
    - Fireworks
    - SiliconFlow

此外，请确保您已克隆了 `alphafrog-micro-deploy` 仓库：
```bash
git clone https://github.com/WillBuyingFrog/alphafrog-micro-deploy
```

---

#### **3. 配置 Redis**

Redis 是 Celery 的消息队列和结果存储的核心组件，请按照以下步骤配置 Redis：

1. 安装 Redis：
   ```bash
   sudo apt-get install redis-server
   ```

2. 启动 Redis 服务：
   ```bash
   redis-server
   ```

3. 验证 Redis 是否正常运行：
   ```bash
   redis-cli ping
   ```
   如果返回 `PONG`，说明 Redis 已成功启动。

4. 如果需要设置密码保护，请编辑 Redis 配置文件（通常位于 `/etc/redis/redis.conf`），添加或修改以下内容：
   ```conf
   requirepass mypassword
   ```
   然后重启 Redis 服务：
   ```bash
   sudo systemctl restart redis
   ```

---

#### **4. 配置分析服务**

##### **4.1 修改启动脚本**
进入 `alphafrog-micro-deploy` 仓库的 `analysis_sh_example` 文件夹，根据您的环境信息填写以下两个脚本：

###### **4.1.1 start_regular_celery.sh**
```bash
#!/bin/bash
source /path/to/conda/bin
conda activate my-alphafrog-environment

export AF_DEEPSEEK_API_KEY=mydeepseekapikey 
export AF_OPENROUTER_API_KEY=myopenrouterapikey
export AF_FIREWORKS_API_KEY=myfireworksapikey
export AF_SILICONFLOW_API_KEY=mysiliconflowapikey
export AF_CELERY_BROKER_URL=redis://:mypass@localhost:6379/0
export AF_CELERY_RESULT_BACKEND=redis://:mypass@localhost:6379/1
export AF_REASONING_OUTPUT_DIR_ROOT=/path/to/output/dir
celery -A analysisService worker -l info
```
- 替换 `/path/to/conda/bin` 为您的 Conda 安装路径。
- 替换 `my-alphafrog-environment` 为您的 Conda 环境名称。
- 替换 `mydeepseekapikey`、`myopenrouterapikey` 等为您的实际 API 密钥。
- 替换 `mypass` 为您的 Redis 密码。
- 替换 `/path/to/output/dir` 为存放分析结果的目录路径。

###### **4.1.2 start_regular.sh**
```bash
#!/bin/bash
source /path/to/conda/bin
conda activate my-alphafrog-environment
export AF_DEEPSEEK_API_KEY=mydeepseekapikey
export AF_OPENROUTER_API_KEY=myopenrouterapikey
export AF_FIREWORKS_API_KEY=myfireworksapikey
export AF_SILICONFLOW_API_KEY=mysiliconflowapikey
export AF_CELERY_BROKER_URL=redis://:mypassword@localhost:6379/0
export AF_CELERY_RESULT_BACKEND=redis://:mypassword@localhost:6379/1
export AF_REASONING_OUTPUT_DIR_ROOT=/path/to/output/dir
python manage.py runserver 0.0.0.0:8070
```
- 参数说明与 `start_regular_celery.sh` 类似，请根据实际情况替换。

##### **4.2 复制脚本到目标目录**
将上述两个脚本复制到 `alphafrog-micro` 项目的 `analysisService` 目录下：
```bash
cp analysis_sh_example/start_regular_celery.sh alphafrog-micro/analysisService/
cp analysis_sh_example/start_regular.sh alphafrog-micro/analysisService/
```

---

#### **5. 启动服务**

##### **5.1 启动 Celery Worker**
进入 `analysisService` 目录并运行以下命令以启动 Celery Worker：
```bash
cd alphafrog-micro/analysisService
bash start_regular_celery.sh
```
该命令会启动一个 Celery Worker，用于处理异步任务。

##### **5.2 启动 Django 服务**
在另一个终端窗口中，进入 `analysisService` 目录并运行以下命令以启动 Django 服务：
```bash
cd alphafrog-micro/analysisService
bash start_regular.sh
```
该命令会启动 Django 开发服务器，默认监听 `0.0.0.0:8070`。

---

#### **6. 验证服务**

1. 打开浏览器，访问以下地址以确认 Django 服务是否正常运行：
   ```
   http://localhost:8070
   ```

2. 发送测试请求（例如通过 Postman 或 cURL），观察 Celery Worker 是否接收到任务并开始处理。

---

#### **7. 注意事项**

- **API 密钥安全**：请妥善保管您的 API 密钥，避免泄露。
- **日志查看**：如果服务启动失败，可以通过以下命令查看日志：
  ```bash
  tail -f celery.log
  ```
- **临时目录清理**：定期清理 `AF_REASONING_OUTPUT_DIR_ROOT` 中的临时文件，避免占用过多磁盘空间。
- **性能优化**：当前版本为试验性功能，可能在高并发场景下表现不佳。未来会针对性能进行优化。

---

通过以上步骤，您已完成 **AlphaFrog-Micro** 分析服务的部署和启动工作。
