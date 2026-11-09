"""Ù…Ù†ÙØ° Ø£ÙˆØ§Ù…Ø± Shell Ø­Ù‚ÙŠÙ‚ÙŠ."""

from __future__ import annotations
import subprocess
import time
import os
from typing import Any, Dict

from tools.tool import Tool, ToolResult


class ShellExecutor:
    """ÙŠÙ†ÙØ° Ø£ÙˆØ§Ù…Ø± shell Ø­Ù‚ÙŠÙ‚ÙŠØ© Ø¹Ù„Ù‰ Ø¬Ù‡Ø§Ø² Ø§Ù„ØªÙ†ÙÙŠØ°."""

    def __init__(self, workdir: str = "/tmp") -> None:
        self.workdir = workdir
        os.makedirs(workdir, exist_ok=True)

    def execute(self, tool: Tool, params: Dict[str, Any]) -> ToolResult:
        command = params.get("command", "")
        cwd = params.get("cwd", self.workdir)
        timeout = params.get("timeout", tool.timeout_seconds)

        if not command:
            return ToolResult(success=False, error="Ù„Ø§ ÙŠÙˆØ¬Ø¯ Ø£Ù…Ø± Ù„Ù„ØªÙ†ÙÙŠØ°")

        start = time.time()
        try:
            result = subprocess.run(
                command, shell=True, capture_output=True, text=True,
                cwd=cwd, timeout=timeout,
            )
            return ToolResult(
                success=result.returncode == 0,
                output=result.stdout,
                error=result.stderr if result.returncode != 0 else None,
                exit_code=result.returncode,
                duration_seconds=time.time() - start,
                evidence={
                    "command": command,
                    "cwd": cwd,
                    "exit_code": result.returncode,
                },
            )
        except subprocess.TimeoutExpired:
            return ToolResult(
                success=False,
                error="Ø§Ù†ØªÙ‡Øª Ù…Ù‡Ù„Ø© Ø§Ù„Ø£Ù…Ø± (%ds)" % timeout,
                duration_seconds=time.time() - start,
                exit_code=-1,
                evidence={"command": command, "timeout": timeout},
            )
        except Exception as exc:
            return ToolResult(
                success=False,
                error=str(exc),
                duration_seconds=time.time() - start,
                evidence={"command": command},
            )
