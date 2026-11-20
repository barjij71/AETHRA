"""Ù…ÙˆØ²Ø¹ Ø§Ù„Ø£Ø¯ÙˆØ§Øª - Pipeline Ø§Ù„ØªÙˆØ²ÙŠØ¹.

Ø§Ù„Ù…Ø³Ø§Ø±:
Tool Registry -> Policy -> Permission -> Approval -> Platform Dispatcher -> Execution -> Event -> Audit
"""

from __future__ import annotations
import time
import threading
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from core.eventbus import event_bus, Event
from core.policy import PermissionBroker, RiskLevel, Decision
from tools.tool import Tool, ToolResult, ToolRisk


@dataclass
class ApprovalGate:
    """Ø¨ÙˆØ§Ø¨Ø© Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø© Ø¹Ù„Ù‰ ØªÙ†ÙÙŠØ° Ø£Ø¯Ø§Ø© Ø­Ø³Ø§Ø³Ø©."""
    tool_id: str
    requested_by: str
    risk_level: ToolRisk
    approved: bool = False
    decided_at: Optional[float] = None
    decided_by: Optional[str] = None
    context: Dict[str, Any] = field(default_factory=dict)

    def approve(self, decided_by: str) -> None:
        self.approved = True
        self.decided_by = decided_by
        self.decided_at = time.time()

    def deny(self, decided_by: str) -> None:
        self.approved = False
        self.decided_by = decided_by
        self.decided_at = time.time()


class ToolDispatcher:
    """Ø§Ù„Ù…ÙˆØ²Ø¹ Ø§Ù„Ù…Ø³Ø¤ÙˆÙ„ Ø¹Ù† ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯ÙˆØ§Øª Ø¹Ø¨Ø±Pipelineå®‰å…¨."""

    def __init__(
        self,
        tool_registry: "ToolRegistry",
        permission_broker: PermissionBroker,
    ) -> None:
        self.tool_registry = tool_registry
        self.permission_broker = permission_broker
        self._executors: Dict[str, Callable[[Tool, Dict[str, Any]], ToolResult]] = {}
        self._approval_gates: Dict[str, ApprovalGate] = {}
        self._lock = threading.RLock()

    def register_executor(
        self,
        tool_id: str,
        executor: Callable[[Tool, Dict[str, Any]], ToolResult],
    ) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ù…Ù†ÙØ° Ø­Ù‚ÙŠÙ‚ÙŠ Ù„Ø£Ø¯Ø§Ø© (Ù…Ø«Ù„ shell executor, file executor)."""
        with self._lock:
            self._executors[tool_id] = executor

    def _tool_risk_to_level(self, risk: ToolRisk) -> RiskLevel:
        mapping = {
            ToolRisk.SAFE: RiskLevel.SAFE,
            ToolRisk.LOW: RiskLevel.LOW,
            ToolRisk.SENSITIVE: RiskLevel.SENSITIVE,
            ToolRisk.DESTRUCTIVE: RiskLevel.DESTRUCTIVE,
        }
        return mapping.get(risk, RiskLevel.SAFE)

    def request_execution(
        self,
        tool_id: str,
        params: Dict[str, Any],
        actor: str,
        require_approval_override: bool = False,
    ) -> ApprovalGate:
        """Ø·Ù„Ø¨ ØªÙ†ÙÙŠØ° Ø£Ø¯Ø§Ø© - ÙŠÙ…Ø± Ø¹Ø¨Ø± Policy/Permission/Approval."""
        tool = self.tool_registry.get(tool_id)
        if tool is None:
            raise ValueError(f"Ø£Ø¯Ø§Ø© ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯Ø©: {tool_id}")
        if not tool.enabled:
            raise ValueError(f"Ø§Ù„Ø£Ø¯Ø§Ø© Ù…Ø¹Ø·Ù„Ø©: {tool_id}")

        risk_level = self._tool_risk_to_level(tool.risk)
        context = {
            "tool_id": tool_id,
            "tool_name": tool.name,
            "category": tool.category.value,
            "params": params,
        }

        # Policy/Permission
        perm = self.permission_broker.request_permission(
            actor=actor,
            capability=f"tool.{tool_id}",
            resource=tool_id,
            risk_level=risk_level,
            context=context,
        )

        gate = ApprovalGate(
            tool_id=tool_id,
            requested_by=actor,
            risk_level=tool.risk,
            context=context,
        )

        # Ø¥Ø°Ø§ ÙƒØ§Ù†Øª Ø§Ù„Ø£Ø¯Ø§Ø© ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø© Ø£Ùˆ Ù‚Ø±Ø±Øª Ø§Ù„Ø³ÙŠØ§Ø³Ø© REQUIRE_APPROVAL
        needs_approval = tool.requires_approval or perm.decision == Decision.REQUIRES_APPROVAL
        if needs_approval or require_approval_override:
            gate.approved = False
            self._approval_gates[gate.tool_id] = gate

            event = Event(
                type="tool.approval.requested",
                payload={"gate_id": gate.tool_id, "tool_id": tool_id, "actor": actor, "risk": tool.risk.value},
                source="tools.dispatcher",
            )
            event_bus.publish(event)
        else:
            gate.approve(decided_by="auto")
            self.permission_broker.approve(perm.id, "auto")

        return gate

    def approve_gate(self, gate_id: str, decided_by: str) -> bool:
        """Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø© Ø¹Ù„Ù‰ Ø¨ÙˆØ§Ø¨Ø© ØªÙ†ÙÙŠØ°."""
        with self._lock:
            gate = self._approval_gates.get(gate_id)
            if gate is None:
                return False
            gate.approve(decided_by)
            return True

    def deny_gate(self, gate_id: str, decided_by: str) -> bool:
        """Ø±ÙØ¶ Ø¨ÙˆØ§Ø¨Ø© ØªÙ†ÙÙŠØ°."""
        with self._lock:
            gate = self._approval_gates.get(gate_id)
            if gate is None:
                return False
            gate.deny(decided_by)
            return True

    def is_approved(self, gate_id: str) -> bool:
        with self._lock:
            gate = self._approval_gates.get(gate_id)
            return gate is not None and gate.approved

    def execute(
        self,
        tool_id: str,
        params: Dict[str, Any],
        actor: str,
        gate_id: Optional[str] = None,
    ) -> ToolResult:
        """ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯Ø§Ø© Ø¨Ø¹Ø¯ Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©."""
        tool = self.tool_registry.get(tool_id)
        if tool is None:
            return ToolResult(success=False, error=f"Ø£Ø¯Ø§Ø© ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯Ø©: {tool_id}")
        if not tool.enabled:
            return ToolResult(success=False, error=f"Ø§Ù„Ø£Ø¯Ø§Ø© Ù…Ø¹Ø·Ù„Ø©: {tool_id}")

        # Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø© Ø¥Ø°Ø§ ÙƒØ§Ù†Øª Ù…Ø·Ù„ÙˆØ¨Ø©
        if tool.requires_approval:
            if gate_id is None:
                return ToolResult(success=False, error=f"Ø§Ù„Ø£Ø¯Ø§Ø© ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø©: {tool_id}")
            with self._lock:
                gate = self._approval_gates.get(gate_id)
            if gate is None or not gate.approved:
                return ToolResult(success=False, error=f"Ù„Ù… ÙŠØªÙ… Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø© Ø¹Ù„Ù‰ ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯Ø§Ø©: {tool_id}")

        executor = self._executors.get(tool_id)
        if executor is None:
            return ToolResult(success=False, error=f"Ù„Ø§ ÙŠÙˆØ¬Ø¯ Ù…Ù†ÙØ° Ù…Ø³Ø¬Ù„ Ù„Ù„Ø£Ø¯Ø§Ø©: {tool_id}")

        start = time.time()
        event = Event(
            type="tool.execution.start",
            payload={"tool_id": tool_id, "actor": actor, "params": params},
            source="tools.dispatcher",
        )
        event_bus.publish(event)

        try:
            result = executor(tool, params)
            result.duration_seconds = time.time() - start

            event = Event(
                type="tool.execution.end",
                payload={"tool_id": tool_id, "success": result.success, "duration": result.duration_seconds},
                source="tools.dispatcher",
            )
            event_bus.publish(event)
            return result
        except Exception as exc:  # noqa: BLE001
            result = ToolResult(success=False, error=str(exc), duration_seconds=time.time() - start)

            event = Event(
                type="tool.execution.error",
                payload={"tool_id": tool_id, "error": str(exc)},
                source="tools.dispatcher",
            )
            event_bus.publish(event)
            return result

    def list_pending_approvals(self) -> List[ApprovalGate]:
        with self._lock:
            return [g for g in self._approval_gates.values() if not g.approved and g.decided_at is None]
