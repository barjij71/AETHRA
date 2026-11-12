"""PermissionBroker et modeles de politique de securite.

Niveaux de risque : SAFE, LOW, SENSITIVE, DESTRUCTIVE.
Toute operation sensible passe par : Request Capability -> Policy -> Permission ->
Approval (si necessaire) -> Execution -> Verification -> Audit.
"""

from __future__ import annotations

import enum
import threading
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


@dataclass
class PermissionRequest:
    """Demande de permission pour une operation."""

    id: str
    actor: str
    capability: str
    resource: str
    risk_level: RiskLevel
    context: Dict[str, Any] = field(default_factory=dict)
    approved: bool = False
    decided_at: Optional[float] = None
    decision: Optional[Decision] = None


@dataclass
class PolicyRule:
    """Regle de politique : condition -> decision."""

    id: str
    name: str
    capability: str
    risk_level: RiskLevel
    decision: Decision
    condition: Optional[Callable[[Dict[str, Any]], bool]] = None


class PermissionBroker:
    """Broker qui evalue les demandes de permission selon les politiques."""

    def __init__(self) -> None:
        self._rules: List[PolicyRule] = []
        self._pending: Dict[str, PermissionRequest] = {}
        self._decisions: Dict[str, Decision] = {}
        self._lock = threading.RLock()
        self._counter = 0

    def add_rule(self, rule: PolicyRule) -> None:
        with self._lock:
            self._rules.append(rule)

    def request_permission(
        self,
        actor: str,
        capability: str,
        resource: str,
        risk_level: RiskLevel,
        context: Optional[Dict[str, Any]] = None,
    ) -> PermissionRequest:
        """Cree une demande de permission et retourne sa decision initiale."""
        self._counter += 1
        req = PermissionRequest(
            id=f"perm_{self._counter}",
            actor=actor,
            capability=capability,
            resource=resource,
            risk_level=risk_level,
            context=context or {},
        )

        decision = self._evaluate(req)
        req.decision = decision
        req.decided_at = __import__("time").time()

        with self._lock:
            self._decisions[req.id] = decision
            if decision == Decision.REQUIRES_APPROVAL:
                self._pending[req.id] = req

        return req

    def approve(self, permission_id: str, approved_by: str) -> bool:
        """Approuve une demande en attente."""
        with self._lock:
            req = self._pending.get(permission_id)
            if req is None:
                return False
            req.approved = True
            req.decision = Decision.ALLOW
            req.decided_at = __import__("time").time()
            self._decisions[permission_id] = Decision.ALLOW
            del self._pending[permission_id]
            return True

    def deny(self, permission_id: str, denied_by: str) -> bool:
        """Refuse une demande en attente."""
        with self._lock:
            req = self._pending.get(permission_id)
            if req is None:
                return False
            req.approved = False
            req.decision = Decision.DENY
            req.decided_at = __import__("time").time()
            self._decisions[permission_id] = Decision.DENY
            del self._pending[permission_id]
            return True

    def is_allowed(self, permission_id: str) -> bool:
        with self._lock:
            return self._decisions.get(permission_id) == Decision.ALLOW

    def get_pending(self) -> List[PermissionRequest]:
        with self._lock:
            return list(self._pending.values())

    def _evaluate(self, req: PermissionRequest) -> Decision:
        """Evalue la demande contre les regles de politique."""
        for rule in self._rules:
            if rule.capability == req.capability and rule.risk_level == req.risk_level:
                if rule.condition is None or rule.condition(req.context):
                    return rule.decision
        # Par defaut : SAFE et LOW sont autorises, SENSITIVE et DESTRUCTIVE need approval
        if req.risk_level in (RiskLevel.SAFE, RiskLevel.LOW):
            return Decision.ALLOW
        return Decision.REQUIRES_APPROVAL
