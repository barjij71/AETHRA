"""Ø³Ø¬Ù„ Ø§Ù„Ø£Ø¯ÙˆØ§Øª Ø§Ù„Ù…ÙˆØ­Ø¯."""

from __future__ import annotations
import threading
from typing import Any, Dict, List, Optional

from tools.tool import Tool, ToolCategory, ToolRisk


class ToolRegistry:
    """Ø³Ø¬Ù„ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ø£Ø¯ÙˆØ§Øª Ø§Ù„Ù…ØªØ§Ø­Ø© ÙÙŠ AETHRA."""

    def __init__(self) -> None:
        self._tools: Dict[str, Tool] = {}
        self._lock = threading.RLock()

    def register(self, tool: Tool) -> None:
        with self._lock:
            self._tools[tool.id] = tool

    def get(self, tool_id: str) -> Optional[Tool]:
        with self._lock:
            return self._tools.get(tool_id)

    def list(self) -> List[Tool]:
        with self._lock:
            return list(self._tools.values())

    def list_by_category(self, category: ToolCategory) -> List[Tool]:
        with self._lock:
            return [t for t in self._tools.values() if t.category == category]

    def list_enabled(self) -> List[Tool]:
        with self._lock:
            return [t for t in self._tools.values() if t.enabled]

    def enable(self, tool_id: str) -> bool:
        with self._lock:
            tool = self._tools.get(tool_id)
            if tool:
                tool.enabled = True
                return True
            return False

    def disable(self, tool_id: str) -> bool:
        with self._lock:
            tool = self._tools.get(tool_id)
            if tool:
                tool.enabled = False
                return True
            return False

    def remove(self, tool_id: str) -> bool:
        with self._lock:
            if tool_id in self._tools:
                del self._tools[tool_id]
                return True
            return False
