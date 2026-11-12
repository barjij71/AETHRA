"""SkillManifest, IntegrityInfo, SourceMetadata."""

from __future__ import annotations
import hashlib
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class IntegrityInfo:
    algorithm: str = "sha256"
    hash: str = ""
    size_bytes: int = 0
    signed: bool = False
    signature: Optional[str] = None

    def verify(self, data: bytes) -> bool:
        return hashlib.sha256(data).hexdigest() == self.hash


@dataclass
class SourceMetadata:
    url: str
    type: str = "git"
    branch: Optional[str] = None
    commit: Optional[str] = None
    license: Optional[str] = None
    author: Optional[str] = None


@dataclass
class SkillManifest:
    id: str
    name: str
    version: str
    description: str
    author: str
    permissions: List[str] = field(default_factory=list)
    capabilities: List[str] = field(default_factory=list)
    platforms: List[str] = field(default_factory=list)
    dependencies: List[str] = field(default_factory=list)
    entrypoint: str = ""
    integrity: Optional[IntegrityInfo] = None
    source: Optional[SourceMetadata] = None

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id, "name": self.name, "version": self.version,
            "description": self.description, "author": self.author,
            "permissions": self.permissions, "capabilities": self.capabilities,
            "platforms": self.platforms, "dependencies": self.dependencies,
            "entrypoint": self.entrypoint,
            "integrity": self.integrity.__dict__ if self.integrity else None,
            "source": self.source.__dict__ if self.source else None,
        }
