"""Core-owned tool dispatch with payload-bound, one-shot approval gates."""

from __future__ import annotations

import copy
import hashlib
import json
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from core.eventbus import event_bus, Event
from core.policy import PermissionBroker, RiskLevel, Decision
from tools.tool import Tool, ToolResult, ToolRisk


@dataclass
class ApprovalGate:
    id: str
    tool_id: str
    requested_by: str
    risk_level: ToolRisk
    permission_id: str
    permission_requires_approval: bool
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
    """Routes tool execution through Core policy and a single-use approval gate."""

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
        tool = self.tool_registry.get(tool_id)
        if tool is None:
            raise ValueError(f"Tool not found: {tool_id}")
        if not tool.enabled:
            raise ValueError(f"Tool is disabled: {tool_id}")

        risk_level = self._tool_risk_to_level(tool.risk)
        context = {
            "tool_id": tool_id,
            "tool_name": tool.name,
            "category": tool.category.value,
            "requires_approval": tool.requires_approval,
            "params": copy.deepcopy(params),
        }
        permission = self.permission_broker.request_permission(
            actor=actor,
            capability=f"tool.{tool_id}",
            resource=tool_id,
            risk_level=risk_level,
            context=copy.deepcopy(context),
        )
        if permission.decision == Decision.DENY:
            raise PermissionError(f"Core policy denied tool execution: {tool_id}")

        needs_approval = (
            tool.requires_approval
            or permission.decision == Decision.REQUIRES_APPROVAL
            or require_approval_override
        )
        gate = ApprovalGate(
            id=str(uuid.uuid4()),
            tool_id=tool_id,
            requested_by=actor,
            risk_level=tool.risk,
            permission_id=permission.id,
            permission_requires_approval=permission.decision == Decision.REQUIRES_APPROVAL,
            context=context,
        )

        if needs_approval:
            with self._lock:
                self._approval_gates[gate.id] = gate
            event_bus.publish(Event(
                type="tool.approval.requested",
                payload={
                    "gate_id": gate.id,
                    "tool_id": tool_id,
                    "actor": actor,
                    "risk": tool.risk.value,
                },
                source="tools.dispatcher",
            ))
        else:
            gate.approve(decided_by="policy:auto")
            with self._lock:
                self._approval_gates[gate.id] = gate

        # Never return the mutable object held by the dispatcher.
        return copy.deepcopy(gate)

    def approve_gate(self, gate_id: str, decided_by: str) -> bool:
        actor = (decided_by or "").strip()
        normalized = actor.casefold()
        if not actor or normalized in {"system", "auto", "policy:auto", "assistant", "hermes"} or normalized.startswith("system_"):
            return False

        with self._lock:
            gate = self._approval_gates.get(gate_id)
            if gate is None or gate.approved or gate.decided_at is not None:
                return False
            if gate.permission_requires_approval:
                if not self.permission_broker.approve(gate.permission_id, actor):
                    return False
            gate.approve(actor)
            return True

    def deny_gate(self, gate_id: str, decided_by: str) -> bool:
        actor = (decided_by or "").strip()
        if not actor:
            return False
        with self._lock:
            gate = self._approval_gates.get(gate_id)
            if gate is None or gate.decided_at is not None:
                return False
            if gate.permission_requires_approval:
                if not self.permission_broker.deny(gate.permission_id, actor):
                    return False
            gate.deny(actor)
            return True

    def is_approved(self, gate_id: str) -> bool:
        with self._lock:
            gate = self._approval_gates.get(gate_id)
            if gate is None or not gate.approved:
                return False
            return self.permission_broker.is_allowed(gate.permission_id)

    def _params_digest(self, params: Dict[str, Any]) -> str:
        encoded = json.dumps(
            params,
            sort_keys=True,
            separators=(",", ":"),
            ensure_ascii=False,
            default=lambda value: f"<{type(value).__name__}>",
        )
        return hashlib.sha256(encoded.encode("utf-8")).hexdigest()

    def execute(
        self,
        tool_id: str,
        params: Dict[str, Any],
        actor: str,
        gate_id: Optional[str] = None,
    ) -> ToolResult:
        tool = self.tool_registry.get(tool_id)
        if tool is None:
            return ToolResult(success=False, error=f"Tool not found: {tool_id}")
        if not tool.enabled:
            return ToolResult(success=False, error=f"Tool is disabled: {tool_id}")
        if gate_id is None:
            return ToolResult(success=False, error=f"Core execution gate required: {tool_id}")

        with self._lock:
            gate = self._approval_gates.get(gate_id)
            if gate is None:
                return ToolResult(success=False, error=f"Unknown or already consumed approval gate: {gate_id}")
            if gate.tool_id != tool_id:
                return ToolResult(success=False, error="Approval gate is bound to a different tool")
            if gate.requested_by != actor:
                return ToolResult(success=False, error="Approval gate is bound to a different actor")
            if gate.context.get("params") != params:
                return ToolResult(success=False, error="Approval gate is bound to a different payload")
            if gate.risk_level != tool.risk or gate.context.get("requires_approval") != tool.requires_approval:
                return ToolResult(success=False, error="Tool policy changed; request a new approval gate")
            if not gate.approved:
                return ToolResult(success=False, error=f"Explicit approval required: {tool_id}")
            if not self.permission_broker.is_allowed(gate.permission_id):
                return ToolResult(success=False, error=f"Core permission is not allowed: {tool_id}")

            executor = self._executors.get(tool_id)
            if executor is None:
                return ToolResult(success=False, error=f"No executor registered for tool: {tool_id}")

            # Consume the approval before invoking an executor, including on failure.
            del self._approval_gates[gate_id]

        start = time.time()
        event_bus.publish(Event(
            type="tool.execution.start",
            payload={
                "tool_id": tool_id,
                "actor": actor,
                "params_hash": self._params_digest(params),
                "param_names": sorted(str(key) for key in params),
            },
            source="tools.dispatcher",
        ))

        try:
            result = executor(tool, params)
            result.duration_seconds = time.time() - start
            event_bus.publish(Event(
                type="tool.execution.end",
                payload={"tool_id": tool_id, "success": result.success, "duration": result.duration_seconds},
                source="tools.dispatcher",
            ))
            return result
        except Exception as exc:  # noqa: BLE001
            result = ToolResult(success=False, error=str(exc), duration_seconds=time.time() - start)
            event_bus.publish(Event(
                type="tool.execution.error",
                payload={"tool_id": tool_id, "error": str(exc)},
                source="tools.dispatcher",
            ))
            return result

    def list_pending_approvals(self) -> List[ApprovalGate]:
        with self._lock:
            return [
                copy.deepcopy(gate)
                for gate in self._approval_gates.values()
                if not gate.approved and gate.decided_at is None
            ]
