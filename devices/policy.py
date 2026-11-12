"""DevicePolicy - regles de securite pour le Device Mesh."""

from __future__ import annotations
from dataclasses import dataclass
from typing import List, Callable, Optional
import enum


class DeviceRiskLevel(enum.Enum):
    SAFE = "SAFE"
    LOW = "LOW"
    SENSITIVE = "SENSITIVE"
    DESTRUCTIVE = "DESTRUCTIVE"


@dataclass
class DevicePolicyRule:
    """Regle : capability + risk_level -> decision."""
    capability: str
    risk_level: DeviceRiskLevel
    decision: str  # ALLOW, DENY, REQUIRES_APPROVAL
    condition: Optional[Callable[[dict], bool]] = None


class DevicePolicy:
    """Evalue les demandes de commande d appareil contre les regles."""

    def __init__(self) -> None:
        self._rules: List[DevicePolicyRule] = []

    def add_rule(self, rule: DevicePolicyRule) -> None:
        self._rules.append(rule)

    def evaluate(self, capability: str, risk_level: DeviceRiskLevel,
                 context: Optional[dict] = None) -> str:
        for rule in self._rules:
            if rule.capability == capability and rule.risk_level == risk_level:
                if rule.condition is None or rule.condition(context or {}):
                    return rule.decision
        if risk_level in (DeviceRiskLevel.SAFE, DeviceRiskLevel.LOW):
            return "ALLOW"
        return "REQUIRES_APPROVAL"
