"""DeviceTransport - transport chiffre entre appareils."""

from __future__ import annotations
from dataclasses import dataclass
from typing import Any, Callable, Optional


@dataclass
class DeviceTransport:
    """Couche de transport pour communiquer avec un appareil."""
    device_id: str
    session_id: str
    encrypted: bool = True
    connected: bool = True

    def send(self, data: bytes) -> bool:
        """Envoie des donnees a l appareil distant."""
        if not self.connected:
            return False
        # Simulation d envoi
        return True

    def receive(self) -> Optional[bytes]:
        """ReÃ§oit des donnees de l appareil distant."""
        if not self.connected:
            return None
        return None

    def disconnect(self) -> None:
        self.connected = False


class DeviceTransportManager:
    """Gere les transports par session."""

    def __init__(self) -> None:
        self._transports: dict = {}
        self._lock = __import__("threading").RLock()

    def add(self, session_id: str, transport: DeviceTransport) -> None:
        with self._lock:
            self._transports[session_id] = transport

    def get(self, session_id: str) -> Optional[DeviceTransport]:
        with self._lock:
            return self._transports.get(session_id)

    def remove(self, session_id: str) -> None:
        with self._lock:
            self._transports.pop(session_id, None)
