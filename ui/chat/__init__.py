"""ChatInterface - interface de chat avec timeline d execution.

Le chat est une execution timeline :
User Request -> Plan -> Tool -> Terminal -> Output -> Decision -> Approval -> Tool -> Verification -> Result
"""

from __future__ import annotations
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class ChatMessage:
    """Un message dans le chat."""
    id: str
    role: str  # user, assistant, system, tool
    content: str
    timestamp: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class ExecutionStep:
    """Une etape dans la timeline d execution."""
    id: str
    step_type: str  # plan, tool, terminal, output, decision, approval, verification, result
    label: str
    status: str = "pending"  # pending, running, completed, failed
    output: Optional[str] = None
    error: Optional[str] = None
    timestamp: float = field(default_factory=time.time)
    expandable: bool = False
    expanded: bool = False


class ExecutionTimeline:
    """Timeline d execution liee au chat."""

    def __init__(self) -> None:
        self._steps: List[ExecutionStep] = []
        self._lock = __import__("threading").RLock()

    def add_step(self, step_type: str, label: str, expandable: bool = False) -> str:
        step_id = str(uuid.uuid4())
        step = ExecutionStep(
            id=step_id,
            step_type=step_type,
            label=label,
            expandable=expandable,
        )
        with self._lock:
            self._steps.append(step)
        return step_id

    def update_step(self, step_id: str, status: Optional[str] = None,
                    output: Optional[str] = None, error: Optional[str] = None,
                    expanded: Optional[bool] = None) -> bool:
        with self._lock:
            for step in self._steps:
                if step.id == step_id:
                    if status is not None:
                        step.status = status
                    if output is not None:
                        step.output = output
                    if error is not None:
                        step.error = error
                    if expanded is not None:
                        step.expanded = expanded
                    return True
        return False

    def toggle_expand(self, step_id: str) -> bool:
        with self._lock:
            for step in self._steps:
                if step.id == step_id and step.expandable:
                    step.expanded = not step.expanded
                    return True
        return False

    def get_steps(self) -> List[ExecutionStep]:
        with self._lock:
            return list(self._steps)

    def get_latest_step(self) -> Optional[ExecutionStep]:
        with self._lock:
            return self._steps[-1] if self._steps else None


class ChatInterface:
    """Interface de chat principale."""

    def __init__(self) -> None:
        self._messages: List[ChatMessage] = []
        self.timeline = ExecutionTimeline()
        self._lock = __import__("threading").RLock()

    def add_user_message(self, content: str) -> str:
        msg_id = str(uuid.uuid4())
        msg = ChatMessage(id=msg_id, role="user", content=content)
        with self._lock:
            self._messages.append(msg)
        return msg_id

    def add_assistant_message(self, content: str) -> str:
        msg_id = str(uuid.uuid4())
        msg = ChatMessage(id=msg_id, role="assistant", content=content)
        with self._lock:
            self._messages.append(msg)
        return msg_id

    def add_tool_message(self, content: str, metadata: Optional[Dict[str, Any]] = None) -> str:
        msg_id = str(uuid.uuid4())
        msg = ChatMessage(id=msg_id, role="tool", content=content, metadata=metadata or {})
        with self._lock:
            self._messages.append(msg)
        return msg_id

    def get_messages(self) -> List[ChatMessage]:
        with self._lock:
            return list(self._messages)

    def jump_to_latest(self) -> Optional[ExecutionStep]:
        """Saute a la derniere etape de la timeline (comme Hermes)."""
        return self.timeline.get_latest_step()
