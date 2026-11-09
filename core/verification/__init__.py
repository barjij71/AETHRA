"""Verification - verification des resultes avec evidence et postconditions.

Modele REA : Action -> Observation -> Expected Effect -> Evidence ->
Verification -> Postcondition -> Completed.
"""

from __future__ import annotations

import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


@dataclass
class Evidence:
    """Preuve qu une action a ete verifiee."""

    id: str
    action: str
    observation: Any
    expected_effect: Any
    matched: bool
    timestamp: float = field(default_factory=time.time)
    details: Dict[str, Any] = field(default_factory=dict)


@dataclass
class VerificationResult:
    """Resultat d une verification."""

    action: str
    passed: bool
    evidence: Evidence
    postcondition_met: bool
    message: str


class VerificationEngine:
    """Verifie que le resultat d une action correspond a l attendu."""

    def __init__(self) -> None:
        self._verifiers: Dict[str, Callable[[Any, Any], bool]] = {}
        self._evidence_log: List[Evidence] = []

    def register_verifier(self, action_type: str, verifier: Callable[[Any, Any], bool]) -> None:
        """Enregistre un verifieur pour un type d action.
        verifier(actual, expected) -> bool
        """
        self._verifiers[action_type] = verifier

    def verify(
        self,
        action: str,
        actual: Any,
        expected: Any,
        action_type: str = "default",
        details: Optional[Dict[str, Any]] = None,
    ) -> VerificationResult:
        verifier = self._verifiers.get(action_type)
        if verifier is None:
            # Par defaut : comparaison directe
            matched = actual == expected
        else:
            matched = verifier(actual, expected)

        evidence = Evidence(
            id=f"ev_{int(time.time() * 1000)}",
            action=action,
            observation=actual,
            expected_effect=expected,
            matched=matched,
            details=details or {},
        )
        self._evidence_log.append(evidence)

        return VerificationResult(
            action=action,
            passed=matched,
            evidence=evidence,
            postcondition_met=matched,
            message="Verifie" if matched else "Echec de verification",
        )

    def get_evidence_log(self, limit: int = 100) -> List[Evidence]:
        return self._evidence_log[-limit:]
