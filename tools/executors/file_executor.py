"""Ù…Ù†ÙØ° Ø¹Ù…Ù„ÙŠØ§Øª Ø§Ù„Ù…Ù„ÙØ§Øª Ø§Ù„Ø­Ù‚ÙŠÙ‚ÙŠØ©."""

from __future__ import annotations
import os
import time
import shutil
from typing import Any, Dict

from tools.tool import Tool, ToolResult


class FileExecutor:
    """ÙŠÙ†ÙØ° Ø¹Ù…Ù„ÙŠØ§Øª Ø§Ù„Ù…Ù„ÙØ§Øª: Ù‚Ø±Ø§Ø¡Ø©ØŒ ÙƒØªØ§Ø¨Ø©ØŒ Ù†Ù‚Ù„ØŒ Ø­Ø°ÙØŒ Ù‚Ø§Ø¦Ù…Ø©."""

    def execute(self, tool: Tool, params: Dict[str, Any]) -> ToolResult:
        operation = params.get("operation", "read")
        path = params.get("path", "")
        start = time.time()

        if not path:
            return ToolResult(success=False, error="Ù„Ù… ÙŠØªÙ… ØªØ­Ø¯ÙŠØ¯ Ù…Ø³Ø§Ø± Ø§Ù„Ù…Ù„Ù")

        try:
            if operation == "read":
                if not os.path.exists(path):
                    return ToolResult(success=False, error="Ø§Ù„Ù…Ù„Ù ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯", duration_seconds=time.time() - start)
                with open(path, "r", encoding="utf-8") as f:
                    content = f.read()
                return ToolResult(
                    success=True,
                    output=content,
                    duration_seconds=time.time() - start,
                    evidence={"operation": "read", "path": path, "size": len(content)},
                )

            elif operation == "write":
                content_str = params.get("content", "")
                os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
                with open(path, "w", encoding="utf-8") as f:
                    f.write(content_str)
                return ToolResult(
                    success=True,
                    output="ØªÙ… Ø§Ù„ÙƒØªØ§Ø¨Ø© Ø¥Ù„Ù‰ " + path,
                    duration_seconds=time.time() - start,
                    evidence={"operation": "write", "path": path, "bytes": len(content_str.encode())},
                )

            elif operation == "list":
                if not os.path.isdir(path):
                    return ToolResult(success=False, error="Ø§Ù„Ù…Ø³Ø§Ø± Ù„ÙŠØ³ Ù…Ø¬Ù„Ø¯Ù‹Ø§", duration_seconds=time.time() - start)
                entries = os.listdir(path)
                return ToolResult(
                    success=True,
                    output=entries,
                    duration_seconds=time.time() - start,
                    evidence={"operation": "list", "path": path, "count": len(entries)},
                )

            elif operation == "delete":
                if not os.path.exists(path):
                    return ToolResult(success=False, error="Ø§Ù„Ù…Ù„Ù ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯ Ù„Ù„Ø­Ø°Ù", duration_seconds=time.time() - start)
                if os.path.isdir(path):
                    shutil.rmtree(path)
                else:
                    os.remove(path)
                return ToolResult(
                    success=True,
                    output="ØªÙ… Ø§Ù„Ø­Ø°Ù: " + path,
                    duration_seconds=time.time() - start,
                    evidence={"operation": "delete", "path": path},
                    reversible=False,
                )

            elif operation == "move":
                dest = params.get("dest", "")
                if not dest:
                    return ToolResult(success=False, error="Ù„Ù… ÙŠØªÙ… ØªØ­Ø¯ÙŠØ¯ Ø§Ù„ÙˆØ¬Ù‡Ø©", duration_seconds=time.time() - start)
                shutil.move(path, dest)
                return ToolResult(
                    success=True,
                    output="ØªÙ… Ø§Ù„Ù†Ù‚Ù„ Ù…Ù† " + path + " Ø¥Ù„Ù‰ " + dest,
                    duration_seconds=time.time() - start,
                    evidence={"operation": "move", "source": path, "dest": dest},
                )

            else:
                return ToolResult(success=False, error="Ø¹Ù…Ù„ÙŠØ© ØºÙŠØ± Ù…Ø¯Ø¹ÙˆÙ…Ø©: " + operation, duration_seconds=time.time() - start)

        except Exception as exc:
            return ToolResult(success=False, error=str(exc), duration_seconds=time.time() - start)
