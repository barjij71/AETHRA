"""Ù†Ù…ÙˆØ°Ø¬ Ø«Ù‚Ø© Ø§Ù„Ù…Ù‡Ø§Ø±Ø§Øª (Skill Trust Model).

Ù„ÙŠØ³Øª ÙƒÙ„ Skill Ù…ÙˆØ«ÙˆÙ‚Ø© ØªÙ„Ù‚Ø§Ø¦ÙŠÙ‹Ø§.
ÙŠØªØ­Ù‚Ù‚ Ù…Ù†:
Skill source â†’ Trust level â†’ Declared capabilities â†’ Required permissions â†’ Policy â†’ Approval.
"""

from __future__ import annotations
import enum
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class TrustLevel(enum.Enum):
    UNTRUSTED = "UNTRUSTED"
    LOW = "LOW"
    MEDIUM = "MEDIUM"
    HIGH = "HIGH"
    VERIFIED = "VERIFIED"


class SkillSource(enum.Enum):
    SYSTEM = "system"        # Ù…Ù‡Ø§Ø±Ø© Ù†Ø¸Ø§Ù…ÙŠØ© Ù…Ø¯Ù…Ø¬Ø©
    USER = "user"            # Ù…Ù‡Ø§Ø±Ø© Ù…Ù† Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…
    COMMUNITY = "community"  # Ù…Ù‡Ø§Ø±Ø© Ù…Ù† Ø§Ù„Ù…Ø¬ØªÙ…Ø¹
    EXTERNAL = "external"    # Ù…Ù‡Ø§Ø±Ø© Ù…Ù† Ù…ØµØ¯Ø± Ø®Ø§Ø±Ø¬ÙŠ
    PRIVATE = "private"      # Ù…Ù‡Ø§Ø±Ø© Ø®Ø§ØµØ©


@dataclass
class SkillSourceInfo:
    """Ù…Ø¹Ù„ÙˆÙ…Ø§Øª Ù…ØµØ¯Ø± Ø§Ù„Ù…Ù‡Ø§Ø±Ø©."""
    source_type: SkillSource
    url: Optional[str] = None
    publisher: Optional[str] = None
    publisher_verified: bool = False
    license: Optional[str] = None
    fingerprint: Optional[str] = None  # SHA256 Ù„Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ù†Ø²Ø§Ù‡Ø©


@dataclass
class TrustAssessment:
    """Ù†ØªÙŠØ¬Ø© ØªÙ‚ÙŠÙŠÙ… Ø§Ù„Ø«Ù‚Ø©."""
    skill_id: str
    trust_level: TrustLevel
    score: float  # 0.0 - 1.0
    factors: Dict[str, float] = field(default_factory=dict)
    recommendations: List[str] = field(default_factory=list)
    requires_approval: bool = False
    timestamp: float = field(default_factory=time.time)


class SkillTrustModel:
    """Ù†Ù…ÙˆØ°Ø¬ Ø«Ù‚Ø© Ø§Ù„Ù…Ù‡Ø§Ø±Ø§Øª ÙÙŠ AETHRA."""

    def __init__(self) -> None:
        self._assessments: Dict[str, TrustAssessment] = {}
        self._trusted_publishers: List[str] = []
        self._lock = __import__("threading").RLock()

        # Ø£ÙˆØ²Ø§Ù† Ø¹ÙˆØ§Ù…Ù„ Ø§Ù„Ø«Ù‚Ø©
        self._weights = {
            "source": 0.3,
            "publisher": 0.2,
            "license": 0.1,
            "capabilities": 0.2,
            "permissions": 0.2,
        }

    def add_trusted_publisher(self, publisher: str) -> None:
        """Ø¥Ø¶Ø§ÙØ© Ù†Ø§Ø´Ø± Ù…ÙˆØ«ÙˆÙ‚."""
        with self._lock:
            if publisher not in self._trusted_publishers:
                self._trusted_publishers.append(publisher)

    def assess(self, skill_id: str, source_info: SkillSourceInfo,
               capabilities: List[str], permissions: List[str]) -> TrustAssessment:
        """ØªÙ‚ÙŠÙŠÙ… Ø«Ù‚Ø© Ø§Ù„Ù…Ù‡Ø§Ø±Ø©."""
        factors = {}
        score = 0.0

        # Ø¹Ø§Ù…Ù„ Ø§Ù„Ù…ØµØ¯Ø±
        source_score = self._score_source(source_info.source_type)
        factors["source"] = source_score
        score += source_score * self._weights["source"]

        # Ø¹Ø§Ù…Ù„ Ø§Ù„Ù†Ø§Ø´Ø±
        publisher_score = self._score_publisher(source_info.publisher, source_info.publisher_verified)
        factors["publisher"] = publisher_score
        score += publisher_score * self._weights["publisher"]

        # Ø¹Ø§Ù…Ù„ Ø§Ù„ØªØ±Ø®ÙŠØµ
        license_score = 1.0 if source_info.license else 0.5
        factors["license"] = license_score
        score += license_score * self._weights["license"]

        # Ø¹Ø§Ù…Ù„ Ø§Ù„Ù‚Ø¯Ø±Ø§Øª (Ø§Ù„Ù‚Ø¯Ø±Ø§Øª Ø§Ù„Ø®Ø·ÙŠØ±Ø© ØªØ®ÙØ¶ Ø§Ù„Ø«Ù‚Ø©)
        dangerous_caps = ["root", "system.shutdown", "data.delete", "network.access", "device.camera"]
        dangerous_count = sum(1 for c in capabilities if c in dangerous_caps)
        capabilities_score = max(0.0, 1.0 - (dangerous_count * 0.2))
        factors["capabilities"] = capabilities_score
        score += capabilities_score * self._weights["capabilities"]

        # Ø¹Ø§Ù…Ù„ Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ§Øª
        dangerous_perms = ["root", "system.shutdown", "data.delete", "network.access"]
        dangerous_perm_count = sum(1 for p in permissions if p in dangerous_perms)
        permissions_score = max(0.0, 1.0 - (dangerous_perm_count * 0.25))
        factors["permissions"] = permissions_score
        score += permissions_score * self._weights["permissions"]

        # ØªØ­Ø¯ÙŠØ¯ Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ø«Ù‚Ø©
        trust_level = self._score_to_level(score)

        # ØªØ­Ø¯ÙŠØ¯ Ù…Ø§ Ø¥Ø°Ø§ ÙƒØ§Ù†Øª ØªØ­ØªØ§Ø¬ Ù…ÙˆØ§ÙÙ‚Ø©
        requires_approval = (
            trust_level in (TrustLevel.UNTRUSTED, TrustLevel.LOW) or
            score < 0.5 or
            dangerous_count > 0 or
            dangerous_perm_count > 0
        )

        recommendations = self._generate_recommendations(
            trust_level, factors, capabilities, permissions, source_info
        )

        assessment = TrustAssessment(
            skill_id=skill_id,
            trust_level=trust_level,
            score=round(score, 2),
            factors=factors,
            recommendations=recommendations,
            requires_approval=requires_approval,
        )

        with self._lock:
            self._assessments[skill_id] = assessment

        return assessment

    def _score_source(self, source_type: SkillSource) -> float:
        scores = {
            SkillSource.SYSTEM: 1.0,
            SkillSource.USER: 0.8,
            SkillSource.PRIVATE: 0.7,
            SkillSource.COMMUNITY: 0.5,
            SkillSource.EXTERNAL: 0.3,
        }
        return scores.get(source_type, 0.0)

    def _score_publisher(self, publisher: Optional[str], verified: bool) -> float:
        if verified or publisher in self._trusted_publishers:
            return 1.0
        if publisher:
            return 0.5
        return 0.0

    def _score_to_level(self, score: float) -> TrustLevel:
        if score >= 0.9:
            return TrustLevel.VERIFIED
        elif score >= 0.7:
            return TrustLevel.HIGH
        elif score >= 0.5:
            return TrustLevel.MEDIUM
        elif score >= 0.3:
            return TrustLevel.LOW
        return TrustLevel.UNTRUSTED

    def _generate_recommendations(self, trust_level: TrustLevel,
                                  factors: Dict[str, float],
                                  capabilities: List[str],
                                  permissions: List[str],
                                  source_info: SkillSourceInfo) -> List[str]:
        recs = []
        if trust_level == TrustLevel.UNTRUSTED:
            recs.append("Ù„Ø§ ØªØ«Ø¨Øª Ù‡Ø°Ù‡ Ø§Ù„Ù…Ù‡Ø§Ø±Ø© Ø¯ÙˆÙ† Ù…Ø±Ø§Ø¬Ø¹Ø© Ø£Ù…Ù†ÙŠØ© Ø¯Ù‚ÙŠÙ‚Ø©")
        if trust_level == TrustLevel.LOW:
            recs.append("Ù…Ø³ØªÙˆÙ‰ Ø«Ù‚Ø© Ù…Ù†Ø®ÙØ¶ - ÙŠÙÙØ¶Ù„ ØªØ«Ø¨ÙŠØª ÙÙŠ Ø¨ÙŠØ¦Ø© Ù…Ø¹Ø²ÙˆÙ„Ø©")
        if factors.get("source", 0) < 0.5:
            recs.append("Ù…ØµØ¯Ø± Ø§Ù„Ù…Ù‡Ø§Ø±Ø© ØºÙŠØ± Ù…ÙˆØ«ÙˆÙ‚ - ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ù…ØµØ¯Ø± Ù‚Ø¨Ù„ Ø§Ù„ØªØ«Ø¨ÙŠØª")
        if factors.get("publisher", 0) < 0.5:
            recs.append("Ù†Ø§Ø´Ø± ØºÙŠØ± Ù…Ø¹Ø±ÙˆÙ Ø£Ùˆ ØºÙŠØ± Ù…ÙØ­Ù‚Ù‚ - ÙŠÙÙØ¶Ù„ Ù†Ø§Ø´Ø± Ù…ÙˆØ«Ù‚")
        if factors.get("capabilities", 0) < 0.5:
            recs.append("Ø§Ù„Ù…Ù‡Ø§Ø±Ø© ØªØªØ·Ù„Ø¨ Ù‚Ø¯Ø±Ø§Øª Ø®Ø·ÙŠØ±Ø© - Ø±Ø§Ø¬Ø¹ Ø§Ù„Ù‚Ø¯Ø±Ø§Øª Ù‚Ø¨Ù„ Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©")
        if factors.get("permissions", 0) < 0.5:
            recs.append("Ø§Ù„Ù…Ù‡Ø§Ø±Ø© ØªØªØ·Ù„Ø¨ ØµÙ„Ø§Ø­ÙŠØ§Øª Ø­Ø³Ø§Ø³Ø© - Ù…ÙˆØ§ÙÙ‚Ø© ØµØ±ÙŠØ­Ø© Ù…Ø·Ù„ÙˆØ¨Ø©")
        if "root" in capabilities or "root" in permissions:
            recs.append("Ø§Ù„Ù…Ù‡Ø§Ø±Ø© ØªØªØ·Ù„Ø¨ ØµÙ„Ø§Ø­ÙŠØ§Øª root - Ù„Ø§ ØªÙÙˆØ§ÙÙ‚ Ø¥Ù„Ø§ ÙÙŠ Ø­Ø§Ù„Ø§Øª Ø§Ø³ØªØ«Ù†Ø§Ø¦ÙŠØ©")
        if not source_info.license:
            recs.append("Ù„Ø§ ÙŠÙˆØ¬Ø¯ ØªØ±Ø®ÙŠØµ Ù…Ø­Ø¯Ø¯ - ØªØ£ÙƒØ¯ Ù…Ù† Ø­Ù‚ÙˆÙ‚ Ø§Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù…")
        if not recs:
            recs.append("Ø§Ù„Ù…Ù‡Ø§Ø±Ø© Ù…ÙˆØ«ÙˆÙ‚Ø© ÙˆÙŠÙ…ÙƒÙ† ØªØ«Ø¨ÙŠØªÙ‡Ø§")
        return recs

    def get_assessment(self, skill_id: str) -> Optional[TrustAssessment]:
        with self._lock:
            return self._assessments.get(skill_id)

    def get_all_assessments(self) -> List[TrustAssessment]:
        with self._lock:
            return list(self._assessments.values())

    def is_trusted(self, skill_id: str) -> bool:
        assessment = self.get_assessment(skill_id)
        if assessment is None:
            return False
        return assessment.trust_level in (TrustLevel.HIGH, TrustLevel.VERIFIED)

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            total = len(self._assessments)
            by_level = {}
            for a in self._assessments.values():
                by_level[a.trust_level.value] = by_level.get(a.trust_level.value, 0) + 1
            return {
                "total_assessed": total,
                "by_trust_level": by_level,
                "trusted_publishers": len(self._trusted_publishers),
            }


# instance Ø¹Ø§Ù…Ø©
skill_trust_model = SkillTrustModel()
