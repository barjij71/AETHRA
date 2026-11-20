"""CoreRegistry - enregistre les composants principaux d'AETHRA."""

from __future__ import annotations

import threading
from typing import Any, Dict, Optional


class CoreRegistry:
    """Registre thread-safe des composants du noyau AETHRA."""

    def __init__(self) -> None:
        self._components: Dict[str, Any] = {}
        self._lock = threading.RLock()

    def register(self, name: str, component: Any) -> None:
        with self._lock:
            self._components[name] = component

    def get(self, name: str) -> Optional[Any]:
        with self._lock:
            return self._components.get(name)

    def has(self, name: str) -> bool:
        with self._lock:
            return name in self._components

    def list(self) -> list[str]:
        with self._lock:
            return sorted(self._components.keys())

    def remove(self, name: str) -> bool:
        with self._lock:
            if name in self._components:
                del self._components[name]
                return True
            return False

    def clear(self) -> None:
        with self._lock:
            self._components.clear()


core_registry = CoreRegistry()
