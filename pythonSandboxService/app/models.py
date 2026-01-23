from __future__ import annotations

from typing import List, Optional

from pydantic import BaseModel, Field


class ExecuteRequest(BaseModel):
    dataset_id: str = Field(..., description="Dataset identifier")
    code: str = Field(..., description="Python code to execute")
    files: Optional[List[str]] = Field(
        default=None, description="Files under dataset_id to copy into sandbox"
    )
    libraries: Optional[List[str]] = Field(
        default=None, description="Python libraries to install (e.g. numpy)"
    )
    timeout_seconds: Optional[float] = Field(
        default=None, description="Execution timeout override"
    )


class ExecuteResponse(BaseModel):
    ok: bool
    exit_code: int
    stdout: str
    stderr: str
    dataset_dir: str
    artifacts: Optional[dict] = None
    error: Optional[str] = None
