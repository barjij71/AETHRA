"""DeviceRegistry - registre des appareils du mesh."""

from __future__ import annotations
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional

from devices.identity import DeviceIdentity
from devices.capabilities import DeviceCapabilities


@dataclass
class DeviceEntry:
    """Un appareil enregistre dans le mesh."""
    identity: DeviceIdentity
    capabilities: DeviceCapabilities
    session_id: Optional[str] = None
    connected_at: Optional[float] = None
    last_seen: Optional[float] = None
    is_online: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)


class DeviceRegistry:
    """Registre des appareils du Device Mesh."""

    def __init__(self) -> None:
        self._devices: Dict[str, DeviceEntry] = {}
        self._lock = threading.RLock()

    def register(self, entry: DeviceEntry) -> None:
        with self._lock:
            self._devices[entry.identity.device_id] = entry

    def get(self, device_id: str) -> Optional[DeviceEntry]:
        with self._lock:
            return self._devices.get(device_id)

    def list(self) -> List[DeviceEntry]:
        with self._lock:
            return list(self._devices.values())

    def list_online(self) -> List[DeviceEntry]:
        with self._lock:
            return [d for d in self._devices.values() if d.is_online]

    def set_online(self, device_id: str, online: bool) -> bool:
        with self._lock:
            entry = self._devices.get(device_id)
            if entry is None:
                return False
            entry.is_online = online
            entry.last_seen = time.time()
            return True

    def remove(self, device_id: str) -> bool:
        with self._lock:
            if device_id in self._devices:
                del self._devices[device_id]
                return True
            return False
