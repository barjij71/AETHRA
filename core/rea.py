"""REA integration - Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ø¥Ø¬Ø±Ø§Ø¡Ø§Øª.

Ø§Ù„Ù…Ø¨Ø¯Ø£:
Action â†’ Observation â†’ Expected Effect â†’ Evidence â†’ Verification â†’ Postcondition â†’ Completed

Ù…Ø±Ø¬Ø¹ REA Ù„Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ù†ØªØ§Ø¦Ø¬ Ø§Ù„ÙˆÙƒÙ„Ø§Ø¡ ÙˆØ§Ù„Ø£Ø¯ÙˆØ§Øª Ù…Ø¹ reproducibility ÙˆØ§Ù„evidence.
"""

from __future__ import annotations
import enum
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class REAStep(enum.Enum):
    """Ø®Ø·ÙˆØ§Øª REA."""
    ACTION = "ACTION"
    OBSERVATION = "OBSERVATION"
    EXPECTED_EFFECT = "EXPECTED_EFFECT"
    EVIDENCE = "EVIDENCE"
    VERIFICATION = "VERIFICATION"
    POSTCONDITION = "POSTCONDITION"
    COMPLETED = "COMPLETED"


@dataclass
class REAEvidence:
    """Ø¯Ù„ÙŠÙ„ Ø¹Ù„Ù‰ ØªÙ†ÙÙŠØ° Ø¥Ø¬Ø±Ø§Ø¡."""
    id: str
    action_id: str
    observation: Any
    expected_effect: Any
    matched: bool
    timestamp: float = field(default_factory=time.time)
    details: Dict[str, Any] = field(default_factory=dict)


@dataclass
class REARecord:
    """Ø³Ø¬Ù„ Ø¥Ø¬Ø±Ø§Ø¡ ÙƒØ§Ù…Ù„ Ù…Ø¹ Ø®Ø·ÙˆØ§Øª REA."""
    id: str
    action: str
    actor: str
    current_step: REAStep = REAStep.ACTION
    observation: Optional[Any] = None
    expected_effect: Optional[Any] = None
    evidence: Optional[REAEvidence] = None
    verification_passed: bool = False
    postcondition_met: bool = False
    completed: bool = False
    created_at: float = field(default_factory=time.time)
    completed_at: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class REAEngine:
    """Ù…Ø­Ø±Ùƒ REA Ù„Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ø¥Ø¬Ø±Ø§Ø¡Ø§Øª."""

    def __init__(self) -> None:
        self._records: Dict[str, REARecord] = {}
        self._verifiers: Dict[str, Callable[[Any, Any], bool]] = {}
        self._lock = __import__("threading").RLock()

    def register_verifier(self, action_type: str,
                          verifier: Callable[[Any, Any], bool]) -> None:
        """ÙŠØ³Ø¬Ù„ Ù…Ø¯Ù‚Ù‚ Ù„Ù†ÙˆØ¹ Ø¥Ø¬Ø±Ø§Ø¡."""
        with self._lock:
            self._verifiers[action_type] = verifier

    def start_action(self, action: str, actor: str,
                     expected_effect: Any = None,
                     metadata: Optional[Dict[str, Any]] = None) -> str:
        """ÙŠØ¨Ø¯Ø£ Ø¥Ø¬Ø±Ø§Ø¡ Ø¬Ø¯ÙŠØ¯ ÙÙŠ Ø³Ø¬Ù„ REA."""
        record_id = "rea_" + str(uuid.uuid4())[:12]
        record = REARecord(
            id=record_id,
            action=action,
            actor=actor,
            current_step=REAStep.ACTION,
            expected_effect=expected_effect,
            metadata=metadata or {},
        )
        with self._lock:
            self._records[record_id] = record
        return record_id

    def observe(self, record_id: str, observation: Any) -> bool:
        """ØªØ³Ø¬ÙŠÙ„ Ø§Ù„Ù…Ù„Ø§Ø­Ø¸Ø© (Observation)."""
        with self._lock:
            record = self._records.get(record_id)
            if record is None:
                return False
            record.observation = observation
            record.current_step = REAStep.OBSERVATION
            return True

    def verify(self, record_id: str,
               verifier_type: str = "default") -> bool:
        """Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ø£Ø«Ø± Ø§Ù„Ù…ØªÙˆÙ‚Ø¹ Ù…Ù‚Ø§Ø¨Ù„ Ø§Ù„Ù…Ù„Ø§Ø­Ø¸Ø©."""
        with self._lock:
            record = self._records.get(record_id)
            if record is None:
                return False

            verifier = self._verifiers.get(verifier_type)
            if verifier is None:
                # Ù…Ù‚Ø§Ø±Ù†Ø© Ù…Ø¨Ø§Ø´Ø±Ø© Ø§ÙØªØ±Ø§Ø¶ÙŠØ§Ù‹
                matched = record.observation == record.expected_effect
            else:
                matched = verifier(record.observation, record.expected_effect)

            evidence = REAEvidence(
                id="ev_" + str(uuid.uuid4())[:12],
                action_id=record_id,
                observation=record.observation,
                expected_effect=record.expected_effect,
                matched=matched,
                details={"verifier": verifier_type},
            )
            record.evidence = evidence
            record.verification_passed = matched
            record.current_step = REAStep.VERIFICATION
            return matched

    def check_postcondition(self, record_id: str,
                            postcondition_met: bool = True) -> bool:
        """Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ø¨Ø¹Ø¯ÙŠØ© Ø§Ù„Ø¨Ø¹Ø¯ÙŠØ© (Postcondition)."""
        with self._lock:
            record = self._records.get(record_id)
            if record is None:
                return False
            record.postcondition_met = postcondition_met
            record.current_step = REAStep.POSTCONDITION
            return postcondition_met

    def complete(self, record_id: str) -> bool:
        """Ø¥ÙƒÙ…Ø§Ù„ Ø§Ù„Ø¥Ø¬Ø±Ø§Ø¡."""
        with self._lock:
            record = self._records.get(record_id)
            if record is None:
                return False
            if not record.verification_passed or not record.postcondition_met:
                return False
            record.completed = True
            record.completed_at = time.time()
            record.current_step = REAStep.COMPLETED
            return True

    def get_record(self, record_id: str) -> Optional[REARecord]:
        with self._lock:
            return self._records.get(record_id)

    def list_records(self) -> List[REARecord]:
        with self._lock:
            return list(self._records.values())

    def get_completed_records(self) -> List[REARecord]:
        with self._lock:
            return [r for r in self._records.values() if r.completed]

    def get_pending_records(self) -> List[REARecord]:
        with self._lock:
            return [r for r in self._records.values() if not r.completed]


# instance Ø¹Ø§Ù…Ø©
rea_engine = REAEngine()
