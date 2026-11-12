"""CoreRuntime - moteur principal d execution d AETHRA.

Le CoreRuntime possede le cycle de vie, l autorisation et l execution controlee.
Il coordonne TaskEngine, MemoryEngine, PermissionBroker, EventBus, etc.
"""

from __future__ import annotations

import threading
from typing import Any, Dict, Optional

from core.registry import core_registry


class CoreRuntime:
    """Instance unique du noyau AETHRA.

    Attributs publics :
        started : bool - le runtime est-il demarre ?
        shutdown_requested : bool - arret demande ?
    """

    def __init__(self) -> None:
        self.started = False
        self.shutdown_requested = False
        self._lock = threading.RLock()
        self._components: Dict[str, Any] = {}

    def start(self) -> None:
        with self._lock:
            if self.started:
                return
            self.started = True
            self.shutdown_requested = False
            # Enregistre le runtime lui-meme dans le registre global
            core_registry.register("runtime", self)

    def stop(self) -> None:
        with self._lock:
            self.shutdown_requested = True
            self.started = False

    def is_running(self) -> bool:
        return self.started and not self.shutdown_requested

    def register_component(self, name: str, component: Any) -> None:
        with self._lock:
            self._components[name] = component

    def get_component(self, name: str) -> Optional[Any]:
        with self._lock:
            return self._components.get(name)

    def get_all_components(self) -> Dict[str, Any]:
        with self._lock:
            return dict(self._components)


# Instance unique du runtime
core_runtime = CoreRuntime()
