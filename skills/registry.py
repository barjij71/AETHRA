"""SkillRegistry - registre des skills."""

from __future__ import annotations
import threading
from typing import Any, Dict, List, Optional

from skills.lifecycle import Skill, SkillState


class SkillRegistry:
    def __init__(self) -> None:
        self._skills: Dict[str, Skill] = {}
        self._lock = threading.RLock()

    def register(self, skill: Skill) -> None:
        with self._lock:
            self._skills[skill.manifest.id] = skill

    def get(self, skill_id: str) -> Optional[Skill]:
        with self._lock:
            return self._skills.get(skill_id)

    def list(self) -> List[Skill]:
        with self._lock:
            return list(self._skills.values())

    def list_by_state(self, state: SkillState) -> List[Skill]:
        with self._lock:
            return [s for s in self._skills.values() if s.state == state]

    def remove(self, skill_id: str) -> bool:
        with self._lock:
            if skill_id in self._skills:
                del self._skills[skill_id]
                return True
            return False
