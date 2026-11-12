"""Ù†Ø¸Ø§Ù… Ø§Ù„Ø£Ù…Ø§Ù† ÙˆØ§Ù„Ø³ÙŠØ§Ø³Ø§Øª Ø§Ù„Ù…ØªÙ‚Ø¯Ù…Ø©.

Ù…Ø³ØªÙˆÙŠØ§Øª Ø§Ù„Ù…Ø®Ø§Ø·Ø±Ø©: SAFE, LOW, SENSITIVE, DESTRUCTIVE.
ÙƒÙ„ Ø¹Ù…Ù„ÙŠØ© Ø­Ø³Ø§Ø³Ø© ØªÙ…Ø± Ø¹Ø¨Ø±:
Request Capability â†’ Policy â†’ Permission â†’ Approval (Ø¹Ù†Ø¯ Ø§Ù„Ø­Ø§Ø¬Ø©) â†’ Execution â†’ Verification â†’ Audit.
"""

from __future__ import annotations
import enum
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class RiskLevel(enum.Enum):
    SAFE = "SAFE"
    LOW = "LOW"
    SENSITIVE = "SENSITIVE"
    DESTRUCTIVE = "DESTRUCTIVE"


class Decision(enum.Enum):
    ALLOW = "ALLOW"
    DENY = "DENY"
    REQUIRES_APPROVAL = "REQUIRES_APPROVAL"


class AuditEventType(enum.Enum):
    TASK_CREATED = "task_created"
    TASK_STARTED = "task_started"
    TASK_COMPLETED = "task_completed"
    TASK_FAILED = "task_failed"
    TASK_CANCELLED = "task_cancelled"
    TOOL_EXECUTE = "tool_execute"
    TOOL_APPROVAL_REQUESTED = "tool_approval_requested"
    TOOL_APPROVAL_GRANTED = "tool_approval_granted"
    TOOL_APPROVAL_DENIED = "tool_approval_denied"
    PERMISSION_CHECK = "permission_check"
    PROVIDER_EXECUTE = "provider_execute"
    PROVIDER_FALLBACK = "provider_fallback"
    DEVICE_COMMAND = "device_command"
    SECURITY_VIOLATION = "security_violation"


@dataclass
class AuditRecord:
    """Ø³Ø¬Ù„ ØªØ¯Ù‚ÙŠÙ‚ ÙˆØ§Ø­Ø¯."""
    id: str
    event_type: AuditEventType
    actor: str
    resource: str
    detail: Dict[str, Any]
    timestamp: float = field(default_factory=time.time)
    risk_level: RiskLevel = RiskLevel.SAFE
    decision: Optional[Decision] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class PolicyRule:
    """Ù‚Ø§Ø¹Ø¯Ø© Ø³ÙŠØ§Ø³Ø©: capability + risk_level â†’ decision."""
    id: str
    name: str
    capability: str
    risk_level: RiskLevel
    decision: Decision
    condition: Optional[Callable[[Dict[str, Any]], bool]] = None
    requires_approval: bool = False


@dataclass
class SecurityContext:
    """Ø³ÙŠØ§Ù‚ Ø£Ù…Ø§Ù† Ù„Ø¹Ù…Ù„ÙŠØ© Ù…Ø§."""
    actor: str
    capability: str
    resource: str
    risk_level: RiskLevel
    context: Dict[str, Any] = field(default_factory=dict)
    approved: bool = False
    decision: Optional[Decision] = None
    decided_at: Optional[float] = None
    decided_by: Optional[str] = None


class SecurityPolicy:
    """Ù…Ø­Ø±Ùƒ Ø§Ù„Ø³ÙŠØ§Ø³Ø§Øª Ø§Ù„Ø£Ù…Ù†ÙŠØ©."""

    def __init__(self) -> None:
        self._rules: List[PolicyRule] = []
        self._pending_approvals: Dict[str, SecurityContext] = {}
        self._audit_log: List[AuditRecord] = []
        self._lock = __import__("threading").RLock()
        self._counter = 0
        self._default_rules()

    def _default_rules(self) -> None:
        """Ù‚ÙˆØ§Ø¹Ø¯ Ø§ÙØªØ±Ø§Ø¶ÙŠØ© Ù„Ù„Ø£Ù…Ø§Ù†."""
        self.add_rule(PolicyRule(
            id="rule_safe_shell",
            name="Safe shell commands",
            capability="shell.exec",
            risk_level=RiskLevel.SAFE,
            decision=Decision.ALLOW,
        ))
        self.add_rule(PolicyRule(
            id="rule_sensitive_shell",
            name="Sensitive shell commands",
            capability="shell.exec",
            risk_level=RiskLevel.SENSITIVE,
            decision=Decision.REQUIRES_APPROVAL,
            requires_approval=True,
        ))
        self.add_rule(PolicyRule(
            id="rule_destructive_file",
            name="Destructive file operations",
            capability="file.write",
            risk_level=RiskLevel.DESTRUCTIVE,
            decision=Decision.REQUIRES_APPROVAL,
            requires_approval=True,
        ))
        self.add_rule(PolicyRule(
            id="rule_sensitive_device",
            name="Sensitive device access",
            capability="device.camera",
            risk_level=RiskLevel.SENSITIVE,
            decision=Decision.REQUIRES_APPROVAL,
            requires_approval=True,
        ))

    def add_rule(self, rule: PolicyRule) -> None:
        with self._lock:
            self._rules.append(rule)

    def evaluate(self, actor: str, capability: str, resource: str,
                 risk_level: RiskLevel,
                 context: Optional[Dict[str, Any]] = None) -> SecurityContext:
        """ØªÙ‚ÙŠÙŠÙ… Ø·Ù„Ø¨ Ø£Ù…Ø§Ù† Ø¶Ø¯ Ø§Ù„Ù‚ÙˆØ§Ø¹Ø¯."""
        self._counter += 1
        ctx = SecurityContext(
            actor=actor,
            capability=capability,
            resource=resource,
            risk_level=risk_level,
            context=context or {},
        )

        # ØªÙ‚ÙŠÙŠÙ… Ø¶Ø¯ Ø§Ù„Ù‚ÙˆØ§Ø¹Ø¯
        decision = Decision.REQUIRES_APPROVAL
        for rule in self._rules:
            if rule.capability == capability and rule.risk_level == risk_level:
                if rule.condition is None or rule.condition(context or {}):
                    decision = rule.decision
                    break

        # Ù‚Ø§Ø¹Ø¯Ø© Ø§ÙØªØ±Ø§Ø¶ÙŠØ©
        if decision == Decision.REQUIRES_APPROVAL and risk_level in (RiskLevel.SAFE, RiskLevel.LOW):
            decision = Decision.ALLOW

        ctx.decision = decision
        ctx.decided_at = time.time()
        if decision == Decision.ALLOW:
            ctx.approved = True

        if decision == Decision.REQUIRES_APPROVAL:
            self._pending_approvals[ctx.capability + ":" + str(self._counter)] = ctx

        # ØªØ³Ø¬ÙŠÙ„ ÙÙŠ Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚
        self._audit(AuditEventType.PERMISSION_CHECK, actor, resource,
                    {"capability": capability, "risk_level": risk_level.value,
                     "decision": decision.value},
                    risk_level, decision)

        return ctx

    def approve(self, context_id: str, decided_by: str) -> bool:
        """Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø© Ø¹Ù„Ù‰ Ø·Ù„Ø¨ Ø£Ù…Ø§Ù† Ù…Ø¹Ù„Ù‚."""
        with self._lock:
            ctx = self._pending_approvals.get(context_id)
            if ctx is None:
                return False
            ctx.approved = True
            ctx.decision = Decision.ALLOW
            ctx.decided_by = decided_by
            ctx.decided_at = time.time()
            del self._pending_approvals[context_id]

            self._audit(AuditEventType.TOOL_APPROVAL_GRANTED, ctx.actor, ctx.resource,
                        {"capability": ctx.capability, "decided_by": decided_by},
                        ctx.risk_level, Decision.ALLOW)
            return True

    def deny(self, context_id: str, decided_by: str) -> bool:
        """Ø±ÙØ¶ Ø·Ù„Ø¨ Ø£Ù…Ø§Ù† Ù…Ø¹Ù„Ù‚."""
        with self._lock:
            ctx = self._pending_approvals.get(context_id)
            if ctx is None:
                return False
            ctx.approved = False
            ctx.decision = Decision.DENY
            ctx.decided_by = decided_by
            ctx.decided_at = time.time()
            del self._pending_approvals[context_id]

            self._audit(AuditEventType.TOOL_APPROVAL_DENIED, ctx.actor, ctx.resource,
                        {"capability": ctx.capability, "decided_by": decided_by},
                        ctx.risk_level, Decision.DENY)
            return True

    def is_allowed(self, context: SecurityContext) -> bool:
        return context.decision == Decision.ALLOW and context.approved

    def get_pending_approvals(self) -> List[SecurityContext]:
        with self._lock:
            return list(self._pending_approvals.values())

    def _audit(self, event_type: AuditEventType, actor: str, resource: str,
               detail: Dict[str, Any], risk_level: RiskLevel,
               decision: Optional[Decision] = None) -> None:
        record = AuditRecord(
            id="audit_" + str(uuid.uuid4())[:12],
            event_type=event_type,
            actor=actor,
            resource=resource,
            detail=detail,
            risk_level=risk_level,
            decision=decision,
        )
        with self._lock:
            self._audit_log.append(record)

    def get_audit_log(self, limit: int = 100,
                      event_type: Optional[AuditEventType] = None) -> List[AuditRecord]:
        with self._lock:
            logs = self._audit_log
            if event_type:
                logs = [l for l in logs if l.event_type == event_type]
            return logs[-limit:]

    def log_security_violation(self, actor: str, resource: str,
                               detail: Dict[str, Any]) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ø§Ù†ØªÙ‡Ø§Ùƒ Ø£Ù…Ù†ÙŠ."""
        self._audit(AuditEventType.SECURITY_VIOLATION, actor, resource, detail,
                    RiskLevel.DESTRUCTIVE, Decision.DENY)

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total = len(self._audit_log)
            by_type = {}
            for r in self._audit_log:
                by_type[r.event_type.value] = by_type.get(r.event_type.value, 0) + 1
            return {
                "total_events": total,
                "pending_approvals": len(self._pending_approvals),
                "events_by_type": by_type,
                "rules_count": len(self._rules),
            }


# instance Ø¹Ø§Ù…Ø©
security_policy = SecurityPolicy()
