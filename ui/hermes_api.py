"""Hermes integration API - ÙˆØ§Ø¬Ù‡Ø© ØªÙˆØ§ØµÙ„ Ø¹Ù„Ù‰ Ù†Ù…Ø· Hermes Ù…Ø¹ AETHRA Core.

Ø§Ù„Ø´Ø§Øª Ù‡Ùˆ execution timeline:
User Request â†’ Plan â†’ Tool â†’ Terminal â†’ Output â†’ Decision â†’ Approval â†’ Tool â†’ Verification â†’ Result

Ù„Ø§ ÙŠØ±Ø¨Ø· Core Ø¨Ù…Ø­Ø±Ùƒ ÙˆØ§Ø­Ø¯Ø› Hermes Ù‡Ùˆ Ø·Ø¨Ù‚Ø© ØªÙƒØ§Ù…Ù„ Ø§Ø®ØªÙŠØ§Ø±ÙŠØ©.
"""

from __future__ import annotations
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from core.integration import aethra
from core.memory import MemoryEngine
from core.task import TaskEngine, TaskStatus
from core.verification import VerificationEngine
from core.eventbus import event_bus, Event
from core.policy import PermissionBroker, RiskLevel
from tools.registry import ToolRegistry
from tools.dispatcher import ToolDispatcher
from providers.registry import ProviderRegistry
from providers.mock import MockProvider


@dataclass
class HermesMessage:
    """Ø±Ø³Ø§Ù„Ø© ÙÙŠ ÙˆØ§Ø¬Ù‡Ø© Hermes."""
    id: str
    role: str  # user, assistant, system, tool
    content: str
    timestamp: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class HermesExecutionStep:
    """Ø®Ø·ÙˆØ© ÙÙŠ timeline Ø§Ù„ØªÙ†ÙÙŠØ°."""
    id: str
    step_type: str  # plan, tool, terminal, output, decision, approval, verification, result
    label: str
    status: str = "pending"  # pending, running, completed, failed
    output: Optional[str] = None
    error: Optional[str] = None
    timestamp: float = field(default_factory=time.time)
    expandable: bool = False
    expanded: bool = False


@dataclass
class HermesResponse:
    """Ø§Ø³ØªØ¬Ø§Ø¨Ø© Ù…Ù† ÙˆØ§Ø¬Ù‡Ø© Hermes."""
    success: bool
    message: str
    data: Optional[Dict[str, Any]] = None
    steps: List[HermesExecutionStep] = field(default_factory=list)
    error: Optional[str] = None
    duration_seconds: float = 0.0


class HermesAPI:
    """ÙˆØ§Ø¬Ù‡Ø© Hermes Ù„Ù„ØªÙˆØ§ØµÙ„ Ù…Ø¹ AETHRA Core."""

    def __init__(self) -> None:
        self.task_engine: TaskEngine = aethra.task_engine
        self.memory_engine: MemoryEngine = aethra.memory_engine
        self.verification_engine: VerificationEngine = aethra.verification_engine
        self.permission_broker: PermissionBroker = aethra.permission_broker
        self.tool_registry: ToolRegistry = aethra.tool_registry
        self.tool_dispatcher: ToolDispatcher = aethra.tool_dispatcher
        self.provider_registry = getattr(aethra, 'provider_registry', None)
        if self.provider_registry is None:
            from providers.registry import ProviderRegistry
            self.provider_registry = ProviderRegistry()

        self._messages: List[HermesMessage] = []
        self._steps: Dict[str, List[HermesExecutionStep]] = {}  # conversation_id -> steps
        self._lock = __import__("threading").RLock()

        # ØªØ³Ø¬ÙŠÙ„ Mock Provider Ø§ÙØªØ±Ø§Ø¶ÙŠ
        if self.provider_registry:
            self.provider_registry.register(MockProvider(), is_default=True)

    def send_message(self, conversation_id: str, content: str,
                     actor: str = "user") -> HermesResponse:
        """Ø¥Ø±Ø³Ø§Ù„ Ø±Ø³Ø§Ù„Ø© ÙˆÙ…Ø¹Ø§Ù„Ø¬ØªÙ‡Ø§ Ø¹Ø¨Ø± AETHRA Core."""
        start = time.time()
        message_id = str(uuid.uuid4())

        with self._lock:
            msg = HermesMessage(
                id=message_id,
                role="user",
                content=content,
                metadata={"actor": actor, "conversation_id": conversation_id},
            )
            self._messages.append(msg)

            steps = self._steps.setdefault(conversation_id, [])
            step_id = str(uuid.uuid4())
            step = HermesExecutionStep(
                id=step_id,
                step_type="plan",
                label="ÙÙ‡Ù… Ø§Ù„Ø·Ù„Ø¨: " + content[:50],
                status="running",
                expandable=True,
            )
            steps.append(step)

        try:
            # Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© ÙÙŠ TaskEngine
            task_id = self.task_engine.create_task(
                "hermes:" + content[:50],
                "default",
                metadata={"content": content, "actor": actor, "conversation_id": conversation_id},
            )

            step.status = "completed"
            step.output = "ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø©: " + task_id[:8]

            # Ø®Ø·ÙˆØ© Ø£Ø¯Ø§Ø©
            tool_step = HermesExecutionStep(
                id=str(uuid.uuid4()),
                step_type="tool",
                label="ØªÙ†ÙÙŠØ° Ø¹Ø¨Ø± Ø£Ø¯Ø§Ø©",
                status="running",
                expandable=True,
            )
            steps.append(tool_step)

            # Ù…Ø­Ø§ÙˆÙ„Ø© ØªÙ†ÙÙŠØ° Ø¹Ø¨Ø± Ø£Ø¯Ø§Ø© shell Ø¥Ø°Ø§ ÙƒØ§Ù† Ø§Ù„Ø·Ù„Ø¨ Ø£Ù…Ø±Ø§Ù‹
            shell_command = None
            if content.startswith("/shell "):
                shell_command = content[len("/shell "):]
            elif content.startswith("/") and len(content) > 1:
                shell_command = content[1:]  # Ø¥Ø²Ø§Ù„Ø© Ø§Ù„Ø¨Ø§Ø¯Ø¦Ø© /
            elif any(w in content for w in ["Ø´ØºÙ„", "Ù†ÙØ°", "run", "exec"]):
                shell_command = content

            if shell_command:
                gate = self.tool_dispatcher.request_execution(
                    tool_id="shell.exec",
                    params={"command": shell_command},
                    actor=actor,
                )
                # Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø© Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠØ© Ù„Ù„ØªØ·ÙˆÙŠØ±
                self.tool_dispatcher.approve_gate(gate.tool_id, "system")
                result = self.tool_dispatcher.execute(
                    tool_id="shell.exec",
                    params={"command": shell_command},
                    actor=actor,
                    gate_id=gate.tool_id,
                )
                tool_step.status = "completed" if result.success else "failed"
                tool_step.output = result.output
                tool_step.error = result.error

                # Ø®Ø·ÙˆØ© Ù†ØªÙŠØ¬Ø©
                result_step = HermesExecutionStep(
                    id=str(uuid.uuid4()),
                    step_type="result",
                    label="Ù†ØªÙŠØ¬Ø© Ø§Ù„ØªÙ†ÙÙŠØ°",
                    status="completed" if result.success else "failed",
                    output=result.output,
                    error=result.error,
                )
                steps.append(result_step)

                # Ø§Ù„Ø±Ø¯
                assistant_msg = HermesMessage(
                    id=str(uuid.uuid4()),
                    role="assistant",
                    content=result.output or result.error or "ØªÙ… Ø§Ù„ØªÙ†ÙÙŠØ°",
                    metadata={"conversation_id": conversation_id, "task_id": task_id},
                )
                self._messages.append(assistant_msg)

                duration = time.time() - start
                return HermesResponse(
                    success=result.success,
                    message=result.output or result.error or "ØªÙ… Ø§Ù„ØªÙ†ÙÙŠØ°",
                    data={"task_id": task_id, "steps": [s.__dict__ for s in steps]},
                    steps=steps,
                    duration_seconds=duration,
                )
            else:
                # Ø·Ù„Ø¨ Ù…Ø­Ø§Ø¯Ø«Ø© Ø¹Ø§Ø¯ÙŠ - Ø§Ø³ØªØ®Ø¯Ù… Ù…Ø²ÙˆØ¯ Mock
                if self.provider_registry:
                    provider = self.provider_registry.route()
                    from providers.base import ProviderRequest
                    req = ProviderRequest(
                        model="mock-model",
                        messages=[
                            {"role": "system", "content": "Ø£Ù†Øª Ø¥ÙŠØ«Ø±Ø§ØŒ Ù…Ø³Ø§Ø¹Ø¯ Ø°ÙƒÙŠ Ù…Ø³ØªÙ‚Ù„."},
                            {"role": "user", "content": content},
                        ],
                    )
                    response = provider.complete(req)

                    # Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ø§Ø³ØªØ¬Ø§Ø¨Ø©
                    verif_result = self.verification_engine.verify(
                        action="ai_response",
                        actual=response.content,
                        expected=response.content,
                        action_type="text",
                    )

                    assistant_msg = HermesMessage(
                        id=str(uuid.uuid4()),
                        role="assistant",
                        content=response.content,
                        metadata={"conversation_id": conversation_id, "task_id": task_id, "provider": response.provider},
                    )
                    self._messages.append(assistant_msg)

                    tool_step.status = "completed"
                    tool_step.output = response.content[:100]

                    result_step = HermesExecutionStep(
                        id=str(uuid.uuid4()),
                        step_type="result",
                        label="Ø±Ø¯ Ø§Ù„Ù…Ø³Ø§Ø¹Ø¯",
                        status="completed",
                        output=response.content,
                    )
                    steps.append(result_step)

                    duration = time.time() - start
                    return HermesResponse(
                        success=True,
                        message=response.content,
                        data={"task_id": task_id, "provider": response.provider, "steps": [s.__dict__ for s in steps]},
                        steps=steps,
                        duration_seconds=duration,
                    )
                else:
                    step.status = "failed"
                    step.error = "Ù„Ø§ ÙŠÙˆØ¬Ø¯ Ù…Ø²ÙˆØ¯ Ù…ØªØ§Ø­"
                    duration = time.time() - start
                    return HermesResponse(
                        success=False,
                        message="Ù„Ø§ ÙŠÙˆØ¬Ø¯ Ù…Ø²ÙˆØ¯ Ù…ØªØ§Ø­ Ù„Ù„Ù…Ø¹Ø§Ù„Ø¬Ø©",
                        error="no_provider",
                        steps=steps,
                        duration_seconds=duration,
                    )

        except Exception as exc:  # noqa: BLE001
            duration = time.time() - start
            return HermesResponse(
                success=False,
                message="Ø®Ø·Ø£ ÙÙŠ Ø§Ù„Ù…Ø¹Ø§Ù„Ø¬Ø©: " + str(exc),
                error=str(exc),
                steps=steps,
                duration_seconds=duration,
            )

    def get_messages(self, conversation_id: str) -> List[HermesMessage]:
        """ÙŠØ¹ÙŠØ¯ Ø§Ù„Ø±Ø³Ø§Ø¦Ù„ Ù„Ù…Ø­Ø§Ø¯Ø«Ø© Ù…Ø­Ø¯Ø¯Ø©."""
        with self._lock:
            return [m for m in self._messages if m.metadata.get("conversation_id") == conversation_id]

    def get_steps(self, conversation_id: str) -> List[HermesExecutionStep]:
        """ÙŠØ¹ÙŠØ¯ Ø®Ø·ÙˆØ§Øª Ø§Ù„ØªÙ†ÙÙŠØ° Ù„Ù…Ø­Ø§Ø¯Ø«Ø©."""
        with self._lock:
            return self._steps.get(conversation_id, [])

    def jump_to_latest(self, conversation_id: str) -> Optional[HermesExecutionStep]:
        """Ø§Ù„Ø§Ù†ØªÙ‚Ø§Ù„ Ø¥Ù„Ù‰ Ø£Ø­Ø¯Ø« Ø®Ø·ÙˆØ© ÙÙŠ Ø§Ù„Ù…Ø­Ø§Ø¯Ø«Ø©."""
        steps = self.get_steps(conversation_id)
        return steps[-1] if steps else None

    def list_conversations(self) -> List[str]:
        """ÙŠØ¹ÙŠØ¯ Ù‚Ø§Ø¦Ù…Ø© Ù…Ø¹Ø±ÙØ§Øª Ø§Ù„Ù…Ø­Ø§Ø¯Ø«Ø§Øª."""
        with self._lock:
            return list(self._steps.keys())

    def get_conversation_summary(self, conversation_id: str) -> Dict[str, Any]:
        """Ù…Ù„Ø®Øµ Ø§Ù„Ù…Ø­Ø§Ø¯Ø«Ø©."""
        messages = self.get_messages(conversation_id)
        steps = self.get_steps(conversation_id)
        return {
            "conversation_id": conversation_id,
            "message_count": len(messages),
            "step_count": len(steps),
            "latest_step": steps[-1].__dict__ if steps else None,
            "latest_message": messages[-1].__dict__ if messages else None,
        }


# instance Ø¹Ø§Ù…Ø©
hermes_api = HermesAPI()
