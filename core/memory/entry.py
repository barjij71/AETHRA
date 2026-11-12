"""Ù…Ø¯Ø®Ù„ Ø°Ø§ÙƒØ±Ø© ÙˆØ§Ø­Ø¯ Ù…Ø¹ Ù…ØµØ¯Ø± ÙˆØ£Ù‡Ù…ÙŠØ© ÙˆØªimestamp ÙˆØ«Ù‚Ø©."""

from __future__ import annotations
import time
from dataclasses import dataclass, field
from typing import Any, Dict, Optional


@dataclass
class MemoryEntry:
    """Ù…Ø¯Ø®Ù„ ÙÙŠ Ø¥Ø­Ø¯Ù‰ Ø·Ø¨Ù‚Ø§Øª Ø§Ù„Ø°Ø§ÙƒØ±Ø©."""
    id: str
    layer: str
    key: str
    value: Any
    source: str
    relevance: float = 1.0
    confidence: float = 1.0
    timestamp: float = field(default_factory=time.time)
    ttl_seconds: Optional[int] = None
    metadata: Dict[str, Any] = field(default_factory=dict)
    privacy_level: str = "internal"  # internal, confidential, secret

    def is_expired(self) -> bool:
        if self.ttl_seconds is None:
            return False
        return time.time() - self.timestamp > self.ttl_seconds

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "layer": self.layer,
            "key": self.key,
            "value": self.value,
            "source": self.source,
            "relevance": self.relevance,
            "confidence": self.confidence,
            "timestamp": self.timestamp,
            "ttl_seconds": self.ttl_seconds,
            "metadata": self.metadata,
            "privacy_level": self.privacy_level,
        }
