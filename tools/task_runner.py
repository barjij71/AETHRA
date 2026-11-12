"""TaskRunner - ÙŠØ±Ø¨Ø· ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯ÙˆØ§Øª Ø¨Ù€ TaskEngine.

ÙŠØ­ÙˆÙ„ ÙƒÙ„ Task Ø¥Ù„Ù‰ Ø§Ø³ØªØ¯Ø¹Ø§Ø¡ Ø£Ø¯Ø§Ø© Ø¹Ø¨Ø± ToolDispatcher Ù…Ø¹ Ø§Ù„Ù…Ø±ÙˆØ± Ø¹Ø¨Ø± Pipeline
Policy -> Permission -> Approval -> Execution -> Event -> Audit.
"""

from __future__ import annotations
import time
import threading
from typing import Any, Callable, Dict, Optional, Union

from core.task import Task, TaskEngine, TaskStatus
from core.eventbus import event_bus, Event
from core.policy import PermissionBroker
from tools.dispatcher import ToolDispatcher
from tools.registry import ToolRegistry
from tools.tool import Tool, ToolResult


class TaskRunner:
    """ÙŠÙ†ÙØ° Ø§Ù„Ù…Ù‡Ø§Ù… Ø¹Ø¨Ø± Ø£Ø¯Ø§Ø© Ù…Ø³Ø¬Ù„Ø© ÙÙŠ ToolDispatcher."""

    def __init__(
        self,
        task_engine: TaskEngine,
        tool_registry: ToolRegistry,
        tool_dispatcher: ToolDispatcher,
        permission_broker: PermissionBroker,
    ) -> None:
        self.task_engine = task_engine
        self.tool_registry = tool_registry
        self.tool_dispatcher = tool_dispatcher
        self.permission_broker = permission_broker
        self._task_tool_map: Dict[str, str] = {}
        self._lock = threading.RLock()

    def _resolve_task(self, task: Union[str, Task]) -> Task:
        """ÙŠØ­Ù„ Ù…Ø¹Ø±Ù Ø§Ù„Ù…Ù‡Ù…Ø© Ø£Ùˆ Ø§Ù„ÙƒØ§Ø¦Ù† Ø¥Ù„Ù‰ ÙƒØ§Ø¦Ù† Task."""
        if isinstance(task, str):
            return self.task_engine.get_task(task)
        return task

    def map_task_to_tool(self, task_id: str, tool_id: str) -> None:
        """ÙŠØ±Ø¨Ø· Ù…Ù‡Ù…Ø© Ø¨Ø£Ø¯Ø§Ø© Ù…Ø­Ø¯Ø¯Ø©."""
        with self._lock:
            self._task_tool_map[task_id] = tool_id

    def run_task(self, task: Union[str, Task], gate_id: Optional[str] = None) -> Any:
        """ÙŠÙ†ÙØ° Ù…Ù‡Ù…Ø© Ø¹Ø¨Ø± ToolDispatcher ÙˆÙŠØ±Ø¬Ø¹ Ø§Ù„Ù†ØªÙŠØ¬Ø©.

        Ø¥Ø°Ø§ ØªÙ… ØªÙ…Ø±ÙŠØ± gate_idØŒ ÙŠØªÙ… Ø§Ø³ØªØ®Ø¯Ø§Ù…Ù‡ Ø¨Ø¯Ù„Ø§Ù‹ Ù…Ù† Ø¥Ù†Ø´Ø§Ø¡ Ø¨ÙˆØ§Ø¨Ø© Ø¬Ø¯ÙŠØ¯Ø©.
        ÙŠÙ‚ÙˆÙ… Ø£ÙŠØ¶Ø§Ù‹ Ø¨ØªØ­Ø¯ÙŠØ« Ø­Ø§Ù„Ø© Ø§Ù„Ù…Ù‡Ù…Ø© ÙÙŠ TaskEngine.
        """
        task_obj = self._resolve_task(task)
        task_id = task_obj.id

        tool_id = self._task_tool_map.get(task_id)
        if tool_id is None:
            tool_id = task_obj.metadata.get("tool_id", "shell.exec")

        tool = self.tool_registry.get(tool_id)
        if tool is None:
            task_obj.mark_failed("Ø§Ù„Ø£Ø¯Ø§Ø© ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯Ø©: " + tool_id)
            raise RuntimeError("Ø§Ù„Ø£Ø¯Ø§Ø© Ø§Ù„Ù…Ø±ØªØ¨Ø·Ø© Ø¨Ø§Ù„Ù…Ù‡Ù…Ø© ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯Ø©: " + tool_id)

        # Ø¨Ù†Ø§Ø¡ params Ù…Ù† metadata
        params = dict(task_obj.metadata.get("params", {}))
        for key, value in task_obj.metadata.items():
            if key not in ("tool_id", "actor", "params"):
                params[key] = value

        actor = task_obj.metadata.get("actor", "system")

        # Ø¥Ø°Ø§ ØªÙ… ØªÙ…Ø±ÙŠØ± gate_idØŒ Ù†Ø³ØªØ®Ø¯Ù…Ù‡ Ù…Ø¨Ø§Ø´Ø±Ø©
        if gate_id is not None:
            gate = type("Gate", (), {"tool_id": gate_id, "approved": True})()
        else:
            gate = self.tool_dispatcher.request_execution(
                tool_id=tool_id,
                params=params,
                actor=actor,
            )

        if tool.requires_approval and not gate.approved:
            task_obj.mark_failed("Ø§Ù„Ø£Ø¯Ø§Ø© ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø©: " + tool_id)
            raise PermissionError("Ø§Ù„Ø£Ø¯Ø§Ø© ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø© Ù‚Ø¨Ù„ Ø§Ù„ØªÙ†ÙÙŠØ°: " + tool_id)

        task_obj.mark_started()
        result = self.tool_dispatcher.execute(
            tool_id=tool_id,
            params=params,
            actor=actor,
            gate_id=gate_id if tool.requires_approval else None,
        )

        event = Event(
            type="task.tool.execution.completed",
            payload={
                "task_id": task_id,
                "tool_id": tool_id,
                "success": result.success,
                "output": result.output,
                "error": result.error,
            },
            source="tools.task_runner",
        )
        event_bus.publish(event)

        if not result.success:
            task_obj.mark_failed(result.error or "ÙØ´Ù„ ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯Ø§Ø©: " + tool_id)
            raise RuntimeError(result.error or "ÙØ´Ù„ ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯Ø§Ø©: " + tool_id)

        task_obj.mark_completed(result.output)
        return result.output

    def register_handler_for_tool(self, tool_id: str) -> Callable[[Task], Any]:
        """ÙŠØ¹ÙŠØ¯ Handler ÙŠÙØ³ØªØ®Ø¯Ù… Ù…Ø¹ TaskEngine Ù„ØªÙ†ÙÙŠØ° Ù…Ù‡Ù…Ø© Ù…Ø±ØªØ¨Ø·Ø© Ø¨Ù‡Ø°Ù‡ Ø§Ù„Ø£Ø¯Ø§Ø©."""
        def handler(task: Task) -> Any:
            return self.run_task(task)
        return handler

    def get_tool_for_task(self, task_id: str) -> Optional[str]:
        with self._lock:
            return self._task_tool_map.get(task_id)
