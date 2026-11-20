"""DeviceCapabilities - capacites d'un appareil."""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Dict, Any


@dataclass
class DeviceCapabilities:
    """Capacites qu un appareil peut executer."""
    device_id: str
    shell: bool = False
    file_transfer: bool = False
    clipboard: bool = False
    notifications: bool = False
    app_control: bool = False
    mouse_keyboard: bool = False
    camera: bool = False
    microphone: bool = False
    gpu: bool = False
    local_ai: bool = False
    root: bool = False
    skills: List[str] = field(default_factory=list)
    custom: Dict[str, Any] = field(default_factory=dict)

    def has(self, capability: str) -> bool:
        return getattr(self, capability, False)
