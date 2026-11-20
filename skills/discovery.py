"""SkillDiscovery - decouverte de skills depuis des sources."""

from __future__ import annotations
from typing import Any, Dict

from skills.manifest import IntegrityInfo, SkillManifest, SourceMetadata
from skills.lifecycle import Skill
from skills.registry import SkillRegistry


class SkillDiscovery:
    def __init__(self, registry: SkillRegistry) -> None:
        self.registry = registry

    def discover_from_manifest(self, manifest_data: Dict[str, Any]) -> Skill:
        integrity = None
        if manifest_data.get("integrity"):
            integrity = IntegrityInfo(**manifest_data["integrity"])
        source = None
        if manifest_data.get("source"):
            source = SourceMetadata(**manifest_data["source"])

        manifest = SkillManifest(
            id=manifest_data["id"],
            name=manifest_data["name"],
            version=manifest_data["version"],
            description=manifest_data.get("description", ""),
            author=manifest_data.get("author", "unknown"),
            permissions=manifest_data.get("permissions", []),
            capabilities=manifest_data.get("capabilities", []),
            platforms=manifest_data.get("platforms", ["linux"]),
            dependencies=manifest_data.get("dependencies", []),
            entrypoint=manifest_data.get("entrypoint", ""),
            integrity=integrity,
            source=source,
        )
        skill = Skill(manifest=manifest)
        self.registry.register(skill)
        return skill
