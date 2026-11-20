"""ØªØ¹Ø±ÙŠÙ Ø§Ù„Ø£Ø¯Ø§Ø© ÙˆÙ†ØªÙŠØ¬ØªÙ‡Ø§."""

from __future__ import annotations
import enum
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class ToolCategory(enum.Enum):
    DEVICE = "device"
    APP = "app"
    FILE = "file"
    CLIPBOARD = "clipboard"
    NOTIFICATION = "notification"
    NETWORK = "network"
    SYSTEM = "system"
    CUSTOM = "custom"


class ToolRisk(enum.Enum):
    SAFE = "SAFE"
    LOW = "LOW"
    SENSITIVE = "SENSITIVE"
    DESTRUCTIVE = "DESTRUCTIVE"


@dataclass
class ToolResult:
    """Ù†ØªÙŠØ¬Ø© ØªÙ†ÙÙŠØ° Ø£Ø¯Ø§Ø©."""
    success: bool
    output: Any = None
    error: Optional[str] = None
    exit_code: Optional[int] = None
    duration_seconds: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)
    evidence: Dict[str, Any] = field(default_factory=dict)


@dataclass
class Tool:
    """ØªØ¹Ø±ÙŠÙ Ø£Ø¯Ø§Ø© ÙÙŠ Ø³Ø¬Ù„ Ø£Ø¯ÙˆØ§Øª AETHRA."""
    id: str
    name: str
    description: str
    category: ToolCategory
    risk: ToolRisk = ToolRisk.SAFE
    capabilities: List[str] = field(default_factory=list)
    input_schema: Dict[str, Any] = field(default_factory=dict)
    output_schema: Dict[str, Any] = field(default_factory=dict)
    requires_approval: bool = False
    reversible: bool = False
    timeout_seconds: int = 30
    enabled: bool = True
    metadata: Dict[str, Any] = field(default_factory=dict)

    def requires_device_capability(self) -> List[str]:
        """Ø§Ù„Ø¹Ù†Ø§ØµØ± Ø§Ù„Ù„Ø§Ø²Ù…Ø© Ù…Ù† Ø¬Ù‡Ø§Ø² Ø§Ù„ØªÙ†ÙÙŠØ°."""
        return list(self.capabilities)
