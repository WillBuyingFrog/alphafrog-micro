from __future__ import annotations

import asyncio
from fastapi import FastAPI, HTTPException

from llm_sandbox.exceptions import SandboxTimeoutError

from .config import load_config
from .models import ExecuteRequest, ExecuteResponse
from .sandbox_runner import run_in_sandbox

config = load_config()
semaphore = asyncio.Semaphore(config.max_concurrency)

app = FastAPI(title="alphafrog-python-sandbox", version="0.1.0")


@app.get("/health")
async def health() -> dict:
    return {"status": "ok"}


@app.post("/execute", response_model=ExecuteResponse)
async def execute(request: ExecuteRequest) -> ExecuteResponse:
    async with semaphore:
        try:
            result = await asyncio.to_thread(
                run_in_sandbox,
                config,
                request.dataset_id,
                request.code,
                request.files,
                request.libraries,
                request.timeout_seconds,
            )
        except FileNotFoundError as exc:
            raise HTTPException(status_code=404, detail=str(exc)) from exc
        except ValueError as exc:
            raise HTTPException(status_code=400, detail=str(exc)) from exc
        except SandboxTimeoutError as exc:
            raise HTTPException(status_code=408, detail=\"sandbox execution timed out\") from exc
        except Exception as exc:
            raise HTTPException(status_code=500, detail=str(exc)) from exc

    return ExecuteResponse(ok=result["exit_code"] == 0, **result)
