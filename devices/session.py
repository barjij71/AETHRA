"""DeviceSession - session de connexion a un appareil."""

from __future__ import annotations
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, Optional


@dataclass
class DeviceSession:
    session_id: str
    device_id: str
    created_at: float
    last_activity: float
    is_active: bool = True
    transport_type: str = "direct"  # direct, ssh, websocket, etc.
    metadata: Dict[str, Any] = field(default_factory=dict)

    def touch(self) -> None:
        self.last_activity = time.time()

    def close(self) -> None:
        self.is_active = False


class DeviceSessionManager:
    """Gere les sessions actives par appareil."""

    def __init__(self) -> None:
        self._sessions: Dict[str, DeviceSession] = {}
        self._lock = threading.RLock()

    def create(self, device_id: str, transport_type: str = "direct") -> DeviceSession:
        session_id = str(uuid.uuid4())
        now = time.time()
        session = DeviceSession(
            session_id=session_id,
            device_id=device_id,
            created_at=now,
            last_activity=now,
            transport_type=transport_type,
        )
        with self._lock:
            self._sessions[session_id] = session
        return session

    def get(self, session_id: str) -> Optional[DeviceSession]:
        with self._lock:
            return self._sessions.get(session_id)

    def get_by_device(self, device_id: str) -> list[DeviceSession]:
        with self._lock:
            return [s for s in self._sessions.values()
                    if s.device_id == device_id and s.is_active]

    def close(self, session_id: str) -> bool:
        with self._lock:
            session = self._sessions.get(session_id)
            if session:
                session.close()
                return True
            return False

    def close_all_for_device(self, device_id: str) -> int:
        with self._lock:
            count = 0
            for s in self._sessions.values():
                if s.device_id == device_id and s.is_active:
                    s.close()
                    count += 1
            return count
