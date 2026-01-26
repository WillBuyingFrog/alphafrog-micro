# Python 沙箱服务（FastAPI + llm-sandbox）

## 作用
为 agent 提供 Python 代码执行能力，支持通过 `dataset_id` 将 Java 侧落盘的数据复制进容器执行环境。

## 启动
```bash
pip install -r requirements.txt
uvicorn app.main:app --host 0.0.0.0 --port 8095
```

## 环境变量
- `AF_SANDBOX_DATA_DIR`：数据集基目录（默认 `data/agent_datasets`）
- `AF_SANDBOX_MAX_CONCURRENCY`：最大并行数（默认 `2`）
- `AF_SANDBOX_EXECUTION_TIMEOUT`：执行超时秒数（默认 `5`）
- `AF_SANDBOX_MEMORY`：容器内存限制（默认 `512m`）
- `AF_SANDBOX_MEMSWAP`：内存+swap 限制（默认 `512m`）
- `AF_SANDBOX_BACKEND`：容器后端（默认 `docker`）
- `AF_SANDBOX_WORKDIR`：容器工作目录（默认 `/sandbox`）

## 数据约定
Java 侧建议将数据落盘：
```
<data_dir>/<dataset_id>/data.parquet
<data_dir>/<dataset_id>/meta.json
```

## 接口
### POST /execute
请求体：
```json
{
  "dataset_id": "ds_20240101_abc",
  "code": "print('hello')",
  "files": ["data.parquet", "meta.json"],
  "libraries": ["numpy"],
  "timeout_seconds": 5
}
```

返回：
```json
{
  "ok": true,
  "exit_code": 0,
  "stdout": "hello\n",
  "stderr": "",
  "dataset_dir": "/sandbox/input/ds_20240101_abc"
}
```

## 说明
- 采用 `copy-to-runtime`：请求时将数据文件复制到容器内的 `dataset_dir`。
- 默认会安装 numpy（可通过 `libraries` 覆盖）。
