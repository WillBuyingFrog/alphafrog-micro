from __future__ import annotations

import re
from pathlib import Path
from typing import List

from llm_sandbox import SandboxSession
from .config import SandboxConfig

DATASET_ID_PATTERN = re.compile(r"^[a-zA-Z0-9._-]+$")


def _resolve_dataset_dir(config: SandboxConfig, dataset_id: str) -> Path:
    if not DATASET_ID_PATTERN.match(dataset_id):
        raise ValueError("dataset_id contains illegal characters")
    dataset_dir = (config.data_dir / dataset_id).resolve()
    base_dir = config.data_dir.resolve()
    if not str(dataset_dir).startswith(str(base_dir)):
        raise ValueError("dataset_id resolves outside base directory")
    if not dataset_dir.exists() or not dataset_dir.is_dir():
        raise FileNotFoundError("dataset_id directory not found")
    return dataset_dir


def _list_files(dataset_dir: Path, files: List[str] | None) -> List[Path]:
    if files:
        resolved = []
        for name in files:
            file_path = (dataset_dir / name).resolve()
            if not str(file_path).startswith(str(dataset_dir)):
                raise ValueError(f"invalid file path: {name}")
            if not file_path.exists() or not file_path.is_file():
                raise FileNotFoundError(f"file not found: {name}")
            resolved.append(file_path)
        return resolved
    return [path for path in dataset_dir.iterdir() if path.is_file()]


def _normalize_dataset_ids(primary: str, extra: List[str] | None) -> List[str]:
    ids: List[str] = []
    for ds_id in [primary, *(extra or [])]:
        if not ds_id:
            continue
        cleaned = ds_id.strip()
        if not cleaned or cleaned in ids:
            continue
        ids.append(cleaned)
    return ids


def _copy_dataset_file(
    session: SandboxSession,
    source: Path,
    dataset_mount: str,
    dataset_id: str,
    workdir: str,
) -> None:
    filename = source.name
    session.copy_to_runtime(str(source), f"{dataset_mount}/{filename}")

    # 兼容常见读取路径：/sandbox/input/<dataset_id>/data.csv(.meta.json)
    if filename == f"{dataset_id}.csv":
        session.copy_to_runtime(str(source), f"{dataset_mount}/data.csv")
        # 兼容部分模型直接 pd.read_csv('<dataset_id>') / pd.read_csv('<dataset_id>.csv')
        session.copy_to_runtime(str(source), f"{workdir}/{dataset_id}")
        session.copy_to_runtime(str(source), f"{workdir}/{dataset_id}.csv")
    elif filename == f"{dataset_id}.meta.json":
        session.copy_to_runtime(str(source), f"{dataset_mount}/data.meta.json")


def run_in_sandbox(
    config: SandboxConfig,
    dataset_id: str,
    dataset_ids: List[str] | None,
    code: str,
    files: List[str] | None,
    libraries: List[str] | None,
    timeout_seconds: float | None,
) -> dict:
    dataset_id_list = _normalize_dataset_ids(dataset_id, dataset_ids)

    runtime_configs = {
        "mem_limit": config.memory_limit,
        "memswap_limit": config.memswap_limit,
    }

    timeout = timeout_seconds or config.execution_timeout_seconds
    libraries = libraries or ["numpy"]

    primary_mount = f"{config.workdir}/input/{dataset_id}"

    with SandboxSession(
        lang="python",
        image=config.sandbox_image,
        backend=config.docker_backend,
        runtime_configs=runtime_configs,
        workdir=config.workdir,
        execution_timeout=timeout,
    ) as session:
        for ds_id in dataset_id_list:
            dataset_dir = _resolve_dataset_dir(config, ds_id)
            files_to_copy = _list_files(dataset_dir, files)
            dataset_mount = f"{config.workdir}/input/{ds_id}"
            session.execute_command(f"mkdir -p {dataset_mount}")
            for file_path in files_to_copy:
                _copy_dataset_file(session, file_path, dataset_mount, ds_id, config.workdir)

        result = session.run(code, libraries=libraries, timeout=timeout)

    return {
        "exit_code": result.exit_code,
        "stdout": result.stdout or "",
        "stderr": result.stderr or "",
        "dataset_dir": primary_mount,
    }
