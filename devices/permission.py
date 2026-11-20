"""DevicePermission - permissions specifiques a un appareil."""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Set
import enum


class DevicePermissionLevel(enum.Enum):
    DENIED = "DENIED"
    REQUEST = "REQUEST"
    GRANTED = "GRANTED"


@dataclass
class DevicePermission:
    device_id: str
    capability: str
    level: DevicePermissionLevel = DevicePermissionLevel.REQUEST
    granted_at: Optional[float] = None
    granted_by: Optional[str] = None
    conditions: Dict[str, Any] = field(default_factory=dict)

    def grant(self, granted_by: str) -> None:
        self.level = DevicePermissionLevel.GRANTED
        self.granted_by = granted_by
        import time
        self.granted_at = time.time()

    def deny(self) -> None:
        self.level = DevicePermissionLevel.DENIED


class DevicePermissionManager:
    """Gere les permissions par appareil et par capacite."""

    def __init__(self) -> None:
        self._permissions: dict = {}
        self._lock = __import__("threading").RLock()

    def get(self, device_id: str, capability: str) -> Optional[DevicePermission]:
        with self._lock:
            key = f"{device_id}:{capability}"
            return self._permissions.get(key)

    def set(self, device_id: str, capability: str, level: DevicePermissionLevel) -> DevicePermission:
        with self._lock:
            key = f"{device_id}:{capability}"
            perm = self._permissions.get(key)
            if perm is None:
                perm = DevicePermission(device_id=device_id, capability=capability, level=level)
                self._permissions[key] = perm
            else:
                perm.level = level
            return perm

    def is_granted(self, device_id: str, capability: str) -> bool:
        perm = self.get(device_id, capability)
        return perm is not None and perm.level == DevicePermissionLevel.GRANTED
