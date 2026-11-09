"""Ø§Ù„ØªÙƒØ§Ù…Ù„ Ø§Ù„ÙƒØ§Ù…Ù„ Ø¨ÙŠÙ† Core Ùˆ Tools Ùˆ TaskRunner.

ÙŠØ¬Ù…Ø¹ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ù…ÙƒÙˆÙ†Ø§Øª ÙÙŠ Ù…Ø­Ø·Ø© ÙˆØ§Ø­Ø¯Ø© ÙŠÙ…ÙƒÙ† Ø§Ø³ØªÙŠØ±Ø§Ø¯Ù‡Ø§ ÙˆØ§Ø®ØªØ¨Ø§Ø±Ù‡Ø§.
"""

from __future__ import annotations
from typing import Any, Dict, Optional

from core.registry import core_registry
from core.runtime import core_runtime
from core.task import TaskEngine
from core.eventbus import event_bus
from core.policy import PermissionBroker
from core.memory import MemoryEngine
from core.verification import VerificationEngine
from core.persistence import PersistenceManager
from core.eventbus import event_bus

from tools.registry import ToolRegistry
from tools.dispatcher import ToolDispatcher
from tools.task_runner import TaskRunner
from tools.executors.shell_executor import ShellExecutor
from tools.executors.file_executor import FileExecutor


class AETHRAIntegration:
    """Ù…Ø­Ø·Ø© Ø§Ù„ØªÙƒØ§Ù…Ù„ Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ© Ù„Ù€ AETHRA Core + Tools."""

    def __init__(self) -> None:
        self.task_engine = TaskEngine()
        self.permission_broker = PermissionBroker()
        self.event_bus = event_bus
        self.memory_engine = MemoryEngine()
        self.verification_engine = VerificationEngine()
        self.persistence_manager = PersistenceManager()

        self.tool_registry = ToolRegistry()
        self.tool_dispatcher = ToolDispatcher(
            tool_registry=self.tool_registry,
            permission_broker=self.permission_broker,
        )
        self.task_runner = TaskRunner(
            task_engine=self.task_engine,
            tool_registry=self.tool_registry,
            tool_dispatcher=self.tool_dispatcher,
            permission_broker=self.permission_broker,
        )

        self.shell_executor = ShellExecutor()
        self.file_executor = FileExecutor()

        # ØªØ³Ø¬ÙŠÙ„ Ø§Ù„Ù…Ù†ÙØ°ÙŠÙ† ØªØ­Øª Ù…Ø¹Ø±ÙØ§Øª Ø§Ù„Ø£Ø¯ÙˆØ§Øª
        self.tool_dispatcher.register_executor("shell.exec", self.shell_executor.execute)
        self.tool_dispatcher.register_executor("file.read", self.file_executor.execute)
        self.tool_dispatcher.register_executor("file.write", self.file_executor.execute)

        from tools.tool import Tool, ToolCategory, ToolRisk
        self.tool_registry.register(Tool(
            id="shell.exec",
            name="Shell Command",
            description="ÙŠÙ†ÙØ° Ø£Ù…Ø±Ø§ Ø­Ù‚ÙŠÙ‚ÙŠØ§ ÙÙŠ shell",
            category=ToolCategory.SYSTEM,
            risk=ToolRisk.SENSITIVE,
            capabilities=["shell"],
            requires_approval=True,
            timeout_seconds=120,
        ))
        self.tool_registry.register(Tool(
            id="file.read",
            name="File Read",
            description="ÙŠÙ‚Ø±Ø£ Ù…Ø­ØªÙˆÙ‰ Ù…Ù„Ù",
            category=ToolCategory.FILE,
            risk=ToolRisk.LOW,
            capabilities=["file.read"],
            requires_approval=False,
            timeout_seconds=30,
        ))
        self.tool_registry.register(Tool(
            id="file.write",
            name="File Write",
            description="ÙŠÙƒØªØ¨ Ø¥Ù„Ù‰ Ù…Ù„Ù",
            category=ToolCategory.FILE,
            risk=ToolRisk.SENSITIVE,
            capabilities=["file.write"],
            requires_approval=True,
            timeout_seconds=30,
        ))

        self.task_engine.register_handler("shell.exec", self.task_runner.register_handler_for_tool("shell.exec"))
        self.task_engine.register_handler("file.exec", self.task_runner.register_handler_for_tool("file.exec"))

        core_registry.register("integration", self)
        core_registry.register("task_engine", self.task_engine)
        core_registry.register("tool_registry", self.tool_registry)
        core_registry.register("tool_dispatcher", self.tool_dispatcher)
        core_registry.register("task_runner", self.task_runner)
        core_registry.register("permission_broker", self.permission_broker)

    def start(self) -> None:
        core_runtime.start()

    def stop(self) -> None:
        core_runtime.stop()


aethra = AETHRAIntegration()
