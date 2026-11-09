"""SkillState et Skill - cycle de vie d'un skill."""

from __future__ import annotations
import enum
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional

from skills.manifest import SkillManifest


class SkillState(enum.Enum):
    DISCOVERED = "DISCOVERED"
    VALIDATING = "VALIDATING"
    VALID = "VALID"
    INSTALLING = "INSTALLING"
    INSTALLED = "INSTALLED"
    ENABLED = "ENABLED"
    RUNNING = "RUNNING"
    DISABLED = "DISABLED"
    ERROR = "ERROR"


@dataclass
class Skill:
    manifest: SkillManifest
    state: SkillState = SkillState.DISCOVERED
    installed_at: Optional[float] = None
    enabled_at: Optional[float] = None
    error: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def transition(self, new_state: SkillState) -> None:
        self.state = new_state
