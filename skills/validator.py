"""SkillValidator - pipeline de validation."""

from __future__ import annotations
from typing import List

from skills.manifest import SkillManifest


class SkillValidator:
    def __init__(self) -> None:
        self.errors: List[str] = []

    def validate_manifest(self, manifest: SkillManifest) -> bool:
        self.errors = []
        if not manifest.id: self.errors.append("Manifest missing id")
        if not manifest.name: self.errors.append("Manifest missing name")
        if not manifest.version: self.errors.append("Manifest missing version")
        if not manifest.entrypoint: self.errors.append("Manifest missing entrypoint")
        return len(self.errors) == 0

    def validate_integrity(self, manifest: SkillManifest, data: bytes) -> bool:
        if manifest.integrity is None:
            return True
        if not manifest.integrity.verify(data):
            self.errors.append("Integrity check failed: hash mismatch")
            return False
        return True

    def validate_compatibility(self, manifest: SkillManifest, platform: str = "linux") -> bool:
        if platform not in manifest.platforms and "all" not in manifest.platforms:
            self.errors.append(f"Platform {platform} not supported")
            return False
        return True

    def validate_permissions(self, manifest: SkillManifest) -> bool:
        dangerous = ["root", "system.shutdown", "data.delete", "network.access"]
        for perm in manifest.permissions:
            if perm in dangerous:
                self.errors.append(f"Dangerous permission requires approval: {perm}")
        return len(self.errors) == 0

    def validate_all(self, manifest: SkillManifest, data: bytes, platform: str = "linux") -> bool:
        ok = True
        ok &= self.validate_manifest(manifest)
        ok &= self.validate_integrity(manifest, data)
        ok &= self.validate_compatibility(manifest, platform)
        ok &= self.validate_permissions(manifest)
        return ok
