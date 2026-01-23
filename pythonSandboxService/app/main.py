from __future__ import annotations

import asyncio
import logging
import uuid
from contextlib import asynccontextmanager
from datetime import datetime
from typing import Dict

from fastapi import FastAPI, HTTPException

from llm_sandbox.exceptions import SandboxTimeoutError

from .config import load_config
from .models import CreateTaskResponse, ExecuteRequest, ExecuteResult, Task, TaskStatus
from .sandbox_runner import run_in_sandbox

# Setup logging
logging.basicConfig(level=logging.INFO)
logger = logging.getLogger(__name__)

config = load_config()

# In-memory storage
tasks: Dict[str, Task] = {}
task_queue: asyncio.Queue = asyncio.Queue()


async def worker():
    logger.info("Worker started")
    while True:
        try:
            task_id = await task_queue.get()
            task = tasks.get(task_id)
            if task and task.status == TaskStatus.QUEUED:
                await process_task(task)
            task_queue.task_done()
        except asyncio.CancelledError:
            logger.info("Worker cancelled")
            break
        except Exception as e:
            logger.error(f"Worker error: {e}")


async def process_task(task: Task):
    task.status = TaskStatus.RUNNING
    task.started_at = datetime.utcnow()
    logger.info(f"Processing task {task.task_id}")

    try:
        # Run synchronous sandbox runner in thread pool
        result_dict = await asyncio.to_thread(
            run_in_sandbox,
            config,
            task.request.dataset_id,
            task.request.code,
            task.request.files,
            task.request.libraries,
            task.request.timeout_seconds,
        )
        task.result = ExecuteResult(
            exit_code=result_dict["exit_code"],
            stdout=result_dict["stdout"],
            stderr=result_dict["stderr"],
            dataset_dir=result_dict["dataset_dir"],
        )
        task.status = TaskStatus.SUCCEEDED
    except Exception as e:
        logger.error(f"Task {task.task_id} failed: {e}")
        task.error = str(e)
        task.status = TaskStatus.FAILED
    finally:
        task.finished_at = datetime.utcnow()


@asynccontextmanager
async def lifespan(app: FastAPI):
    # Start worker
    worker_task = asyncio.create_task(worker())
    yield
    # Cleanup
    worker_task.cancel()
    try:
        await worker_task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="alphafrog-python-sandbox", version="0.2.0", lifespan=lifespan)


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/tasks", response_model=CreateTaskResponse)
async def create_task(request: ExecuteRequest):
    task_id = str(uuid.uuid4())
    task = Task(task_id=task_id, status=TaskStatus.QUEUED, request=request)
    tasks[task_id] = task
    await task_queue.put(task_id)
    return CreateTaskResponse(task_id=task_id, status=task.status)


@app.get("/tasks/{task_id}", response_model=Task)
async def get_task(task_id: str):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    return tasks[task_id]


@app.get("/tasks/{task_id}/result", response_model=ExecuteResult)
async def get_task_result(task_id: str):
    if task_id not in tasks:
        raise HTTPException(status_code=404, detail="Task not found")
    task = tasks[task_id]
    if task.status != TaskStatus.SUCCEEDED:
        if task.status == TaskStatus.FAILED:
            raise HTTPException(status_code=400, detail=f"Task failed: {task.error}")
        raise HTTPException(status_code=409, detail=f"Task not finished. Status: {task.status}")
    return task.result