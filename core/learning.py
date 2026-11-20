"""Ù†Ø¸Ø§Ù… Ø§Ù„ØªØ¹Ù„Ù… Ù…Ù† Ø§Ù„Ø®Ø¨Ø±Ø© (Experience/Learning system).

AETHRA ØªØªØ¹Ù„Ù… Ù…Ù† Ø§Ù„ØªØ¬Ø§Ø±Ø¨ ÙˆÙ„ÙƒÙ† Ù„Ø§ ØªÙØ¹Ø¯Ù„ Ø³Ù„ÙˆÙƒÙ‡Ø§ ØªÙ„Ù‚Ø§Ø¦ÙŠÙ‹Ø§ Ø¯ÙˆÙ† Ù…Ø±Ø§Ø¬Ø¹Ø©.
Ø§Ù„Ù…Ø³Ø§Ø±:
Experience â†’ Pattern â†’ Suggestion â†’ Review â†’ Approve â†’ Apply
"""

from __future__ import annotations
import enum
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class ExperienceType(enum.Enum):
    """Ø£Ù†ÙˆØ§Ø¹ Ø§Ù„ØªØ¬Ø§Ø±Ø¨."""
    TASK_SUCCESS = "task_success"
    TASK_FAILURE = "task_failure"
    TOOL_SUCCESS = "tool_success"
    TOOL_FAILURE = "tool_failure"
    PROVIDER_SUCCESS = "provider_success"
    PROVIDER_FAILURE = "provider_failure"
    USER_APPROVAL = "user_approval"
    USER_DENIAL = "user_denial"


class SuggestionStatus(enum.Enum):
    """Ø­Ø§Ù„Ø© Ø§Ù„Ø§Ù‚ØªØ±Ø§Ø­."""
    PENDING = "PENDING"
    UNDER_REVIEW = "UNDER_REVIEW"
    APPROVED = "APPROVED"
    REJECTED = "REJECTED"
    APPLIED = "APPLIED"


@dataclass
class ExperienceRecord:
    """Ø³Ø¬Ù„ ØªØ¬Ø±Ø¨Ø© ÙˆØ§Ø­Ø¯Ø©."""
    id: str
    experience_type: ExperienceType
    task_id: Optional[str]
    tool_id: Optional[str]
    provider: Optional[str]
    description: str
    outcome: str  # success, failure, partial
    context: Dict[str, Any] = field(default_factory=dict)
    metadata: Dict[str, Any] = field(default_factory=dict)
    timestamp: float = field(default_factory=time.time)
    learned: bool = False


@dataclass
class Pattern:
    """Ù†Ù…Ø· ØªÙ… Ø§Ø³ØªØ®Ø±Ø§Ø¬Ù‡ Ù…Ù† Ø§Ù„ØªØ¬Ø§Ø±Ø¨."""
    id: str
    pattern_type: str
    description: str
    frequency: int = 1
    confidence: float = 0.0
    examples: List[str] = field(default_factory=list)
    created_at: float = field(default_factory=time.time)
    updated_at: float = field(default_factory=time.time)


@dataclass
class Suggestion:
    """Ø§Ù‚ØªØ±Ø§Ø­ ØªØ­Ø³ÙŠÙ† Ù…Ø³ØªÙ…Ø¯ Ù…Ù† Ù†Ù…Ø·."""
    id: str
    pattern_id: str
    title: str
    description: str
    suggested_action: str
    status: SuggestionStatus = SuggestionStatus.PENDING
    created_at: float = field(default_factory=time.time)
    reviewed_at: Optional[float] = None
    reviewed_by: Optional[str] = None
    review_notes: Optional[str] = None
    applied_at: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class LearningEngine:
    """Ù…Ø­Ø±Ùƒ Ø§Ù„ØªØ¹Ù„Ù… Ù…Ù† Ø§Ù„Ø®Ø¨Ø±Ø© ÙÙŠ AETHRA."""

    def __init__(self) -> None:
        self._experiences: List[ExperienceRecord] = []
        self._patterns: Dict[str, Pattern] = {}
        self._suggestions: Dict[str, Suggestion] = {}
        self._lock = __import__("threading").RLock()
        self._auto_apply = False  # Ù„Ø§ ØªÙØ¹Ø¯Ù„ Ø§Ù„Ø³Ù„ÙˆÙƒ ØªÙ„Ù‚Ø§Ø¦ÙŠØ§Ù‹ Ø¯ÙˆÙ† Ù…Ø±Ø§Ø¬Ø¹Ø©

    def record_experience(self, experience_type: ExperienceType,
                          description: str,
                          task_id: Optional[str] = None,
                          tool_id: Optional[str] = None,
                          provider: Optional[str] = None,
                          outcome: str = "success",
                          context: Optional[Dict[str, Any]] = None,
                          metadata: Optional[Dict[str, Any]] = None) -> str:
        """ØªØ³Ø¬ÙŠÙ„ ØªØ¬Ø±Ø¨Ø© Ø¬Ø¯ÙŠØ¯Ø©."""
        record_id = "exp_" + str(uuid.uuid4())[:12]
        record = ExperienceRecord(
            id=record_id,
            experience_type=experience_type,
            task_id=task_id,
            tool_id=tool_id,
            provider=provider,
            description=description,
            outcome=outcome,
            context=context or {},
            metadata=metadata or {},
        )
        with self._lock:
            self._experiences.append(record)
        return record_id

    def get_experiences(self, limit: int = 50) -> List[ExperienceRecord]:
        with self._lock:
            return self._experiences[-limit:]

    def get_experiences_by_type(self, experience_type: ExperienceType,
                                limit: int = 50) -> List[ExperienceRecord]:
        with self._lock:
            return [e for e in self._experiences
                    if e.experience_type == experience_type][-limit:]

    def extract_patterns(self) -> List[Pattern]:
        """Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ø£Ù†Ù…Ø§Ø· Ù…Ù† Ø§Ù„ØªØ¬Ø§Ø±Ø¨ Ø§Ù„Ù…Ø³Ø¬Ù„Ø©."""
        with self._lock:
            experiences = list(self._experiences)

        # ØªØ¬Ù…ÙŠØ¹ Ø§Ù„ØªØ¬Ø§Ø±Ø¨ Ø­Ø³Ø¨ Ø§Ù„Ù†ÙˆØ¹ ÙˆØ§Ù„Ù†ØªÙŠØ¬Ø©
        groups: Dict[str, List[ExperienceRecord]] = {}
        for exp in experiences:
            key = f"{exp.experience_type.value}:{exp.outcome}"
            groups.setdefault(key, []).append(exp)

        patterns = []
        for key, exps in groups.items():
            if len(exps) < 2:
                continue
            pattern_id = "pat_" + str(uuid.uuid4())[:12]
            pattern = Pattern(
                id=pattern_id,
                pattern_type=key,
                description=f"Ù†Ù…Ø· ØªÙƒØ±Ø§Ø±ÙŠ: {key} (ØªÙƒØ±Ø± {len(exps)} Ù…Ø±Ø©)",
                frequency=len(exps),
                confidence=min(1.0, len(exps) / 10.0),
                examples=[e.id for e in exps[:5]],
            )
            patterns.append(pattern)
            with self._lock:
                self._patterns[pattern_id] = pattern

        return patterns

    def generate_suggestions(self) -> List[Suggestion]:
        """ØªÙˆÙ„ÙŠØ¯ Ø§Ù‚ØªØ±Ø§Ø­Ø§Øª ØªØ­Ø³ÙŠÙ† Ù…Ù† Ø§Ù„Ø£Ù†Ù…Ø§Ø· Ø§Ù„Ù…Ø³ØªØ®Ø±Ø¬Ø©."""
        patterns = self.extract_patterns()
        suggestions = []

        with self._lock:
            existing_pattern_ids = {s.pattern_id for s in self._suggestions.values()}

        for pattern in patterns:
            if pattern.id in existing_pattern_ids:
                continue

            suggestion_id = "sugg_" + str(uuid.uuid4())[:12]
            suggestion = Suggestion(
                id=suggestion_id,
                pattern_id=pattern.id,
                title="ØªØ­Ø³ÙŠÙ†: " + pattern.pattern_type,
                description=pattern.description,
                suggested_action=self._suggest_action(pattern),
                status=SuggestionStatus.PENDING,
            )
            suggestions.append(suggestion)
            with self._lock:
                self._suggestions[suggestion_id] = suggestion

        return suggestions

    def _suggest_action(self, pattern: Pattern) -> str:
        """Ø§Ù‚ØªØ±Ø§Ø­ Ø¥Ø¬Ø±Ø§Ø¡ Ø¨Ù†Ø§Ø¡Ù‹ Ø¹Ù„Ù‰ Ø§Ù„Ù†Ù…Ø·."""
        if "failure" in pattern.pattern_type:
            return "Ù…Ø±Ø§Ø¬Ø¹Ø© Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª Ø§Ù„Ø£Ø¯Ø§Ø© Ø£Ùˆ Ø§Ù„Ù…Ø²ÙˆØ¯ Ø§Ù„Ù…Ø±ØªØ¨Ø· Ø¨Ù‡Ø°Ø§ Ø§Ù„Ù†Ù…Ø·"
        elif "success" in pattern.pattern_type:
            return "ÙŠÙ…ÙƒÙ† ØªØ¹Ø²ÙŠØ² Ø§Ø³ØªØ®Ø¯Ø§Ù… Ù‡Ø°Ø§ Ø§Ù„Ø£Ø¯Ø§Ø©/Ø§Ù„Ù…Ø²ÙˆØ¯ ÙÙŠ Ù…Ù‡Ø§Ù… Ù…Ø´Ø§Ø¨Ù‡Ø©"
        return "Ù…Ø±Ø§Ø¬Ø¹Ø© Ø§Ù„Ù†Ù…Ø· ÙˆØ§ØªØ®Ø§Ø° Ø¥Ø¬Ø±Ø§Ø¡ Ù…Ù†Ø§Ø³Ø¨"

    def get_suggestions(self, status: Optional[SuggestionStatus] = None,
                        limit: int = 50) -> List[Suggestion]:
        with self._lock:
            suggestions = list(self._suggestions.values())
            if status:
                suggestions = [s for s in suggestions if s.status == status]
            return suggestions[-limit:]

    def review_suggestion(self, suggestion_id: str, decision: str,
                          reviewer: str, notes: Optional[str] = None) -> bool:
        """Ù…Ø±Ø§Ø¬Ø¹Ø© Ø§Ù‚ØªØ±Ø§Ø­ (Ù‚Ø¨ÙˆÙ„ Ø£Ùˆ Ø±ÙØ¶)."""
        with self._lock:
            suggestion = self._suggestions.get(suggestion_id)
            if suggestion is None:
                return False
            if suggestion.status != SuggestionStatus.PENDING:
                return False

            suggestion.reviewed_at = time.time()
            suggestion.reviewed_by = reviewer
            suggestion.review_notes = notes

            if decision == "approve":
                suggestion.status = SuggestionStatus.APPROVED
            elif decision == "reject":
                suggestion.status = SuggestionStatus.REJECTED
            else:
                return False
            return True

    def apply_suggestion(self, suggestion_id: str) -> bool:
        """ØªØ·Ø¨ÙŠÙ‚ Ø§Ù‚ØªØ±Ø§Ø­ Ù…Ø¹ØªÙ…Ø¯."""
        with self._lock:
            suggestion = self._suggestions.get(suggestion_id)
            if suggestion is None:
                return False
            if suggestion.status != SuggestionStatus.APPROVED:
                return False
            suggestion.status = SuggestionStatus.APPLIED
            suggestion.applied_at = time.time()
        return True

    def enable_auto_apply(self, enabled: bool = True) -> None:
        """ØªÙØ¹ÙŠÙ„/ØªØ¹Ø·ÙŠÙ„ Ø§Ù„ØªØ·Ø¨ÙŠÙ‚ Ø§Ù„ØªÙ„Ù‚Ø§Ø¦ÙŠ (Ø§ÙØªØ±Ø§Ø¶ÙŠØ§Ù‹ Ù…Ø¹Ø·Ù„ Ù„Ù„Ø­ÙØ§Ø¸ Ø¹Ù„Ù‰ Ø§Ù„Ø£Ù…Ø§Ù†)."""
        self._auto_apply = enabled

    def is_auto_apply(self) -> bool:
        return self._auto_apply

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            exp_by_type = {}
            for e in self._experiences:
                exp_by_type[e.experience_type.value] = exp_by_type.get(e.experience_type.value, 0) + 1
            sugg_by_status = {}
            for s in self._suggestions.values():
                sugg_by_status[s.status.value] = sugg_by_status.get(s.status.value, 0) + 1
            return {
                "total_experiences": len(self._experiences),
                "experiences_by_type": exp_by_type,
                "total_patterns": len(self._patterns),
                "total_suggestions": len(self._suggestions),
                "suggestions_by_status": sugg_by_status,
                "auto_apply": self._auto_apply,
            }


# instance Ø¹Ø§Ù…Ø©
learning_engine = LearningEngine()
