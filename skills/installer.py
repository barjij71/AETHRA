"""SkillInstaller - installation, activation, execution."""

from __future__ import annotations
import time

from skills.lifecycle import Skill, SkillState
from skills.registry import SkillRegistry
from skills.validator import SkillValidator


class SkillInstaller:
    def __init__(self, registry: SkillRegistry, validator: SkillValidator) -> None:
        self.registry = registry
        self.validator = validator

    def install(self, skill: Skill, data: bytes, platform: str = "linux") -> bool:
        skill.transition(SkillState.VALIDATING)
        if not self.validator.validate_all(skill.manifest, data, platform):
            skill.transition(SkillState.ERROR)
            skill.error = "; ".join(self.validator.errors)
            return False
        skill.transition(SkillState.VALID)
        skill.transition(SkillState.INSTALLING)
        skill.installed_at = time.time()
        skill.transition(SkillState.INSTALLED)
        return True

    def enable(self, skill: Skill) -> bool:
        if skill.state != SkillState.INSTALLED:
            return False
        skill.transition(SkillState.ENABLED)
        skill.enabled_at = time.time()
        return True

    def run(self, skill: Skill) -> bool:
        if skill.state != SkillState.ENABLED:
            return False
        skill.transition(SkillState.RUNNING)
        return True
