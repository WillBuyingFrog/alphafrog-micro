from __future__ import annotations

import os
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class SandboxConfig:
    data_dir: Path
    max_concurrency: int
    execution_timeout_seconds: float
    memory_limit: str
    memswap_limit: str
    docker_backend: str
    workdir: str
    log_level: str
    sandbox_image: str


def load_config() -> SandboxConfig:
    data_dir = Path(os.getenv("AF_SANDBOX_DATA_DIR", "data/agent_datasets"))
    max_concurrency = int(os.getenv("AF_SANDBOX_MAX_CONCURRENCY", "2"))
    execution_timeout = float(os.getenv("AF_SANDBOX_EXECUTION_TIMEOUT", "5"))
    memory_limit = os.getenv("AF_SANDBOX_MEMORY", "512m")
    memswap_limit = os.getenv("AF_SANDBOX_MEMSWAP", "512m")
    docker_backend = os.getenv("AF_SANDBOX_BACKEND", "docker")
    workdir = os.getenv("AF_SANDBOX_WORKDIR", "/sandbox")
    log_level = os.getenv("AF_SANDBOX_LOG_LEVEL", "INFO")
    sandbox_image = os.getenv("AF_SANDBOX_IMAGE", "alphafrog-sandbox-runtime:latest")
    return SandboxConfig(
        data_dir=data_dir,
        max_concurrency=max_concurrency,
        execution_timeout_seconds=execution_timeout,
        memory_limit=memory_limit,
        memswap_limit=memswap_limit,
        docker_backend=docker_backend,
        workdir=workdir,
        log_level=log_level,
        sandbox_image=sandbox_image,
    )
