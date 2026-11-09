"""EventBus - bus d evenements pour la communication inter-composants.

Tous les evenements sont audit??s : creation, transition d etat, execution, verification.
"""

from __future__ import annotations

import threading
import time
from collections import defaultdict
from typing import Any, Callable, Dict, List


class Event:
    """Evenement emis sur le bus."""

    def __init__(self, type: str, payload: Any = None, source: str = "") -> None:
        self.type = type
        self.payload = payload
        self.source = source
        self.timestamp = time.time()


class EventBus:
    """Bus d evenements publish/subscribe thread-safe."""

    def __init__(self) -> None:
        self._subscribers: Dict[str, List[Callable[[Event], None]]] = defaultdict(list)
        self._history: List[Event] = []
        self._max_history = 10000
        self._lock = threading.RLock()

    def subscribe(self, event_type: str, callback: Callable[[Event], None]) -> None:
        with self._lock:
            self._subscribers[event_type].append(callback)

    def unsubscribe(self, event_type: str, callback: Callable[[Event], None]) -> None:
        with self._lock:
            if event_type in self._subscribers:
                self._subscribers[event_type] = [
                    cb for cb in self._subscribers[event_type] if cb != callback
                ]

    def publish(self, event: Event) -> None:
        with self._lock:
            self._history.append(event)
            if len(self._history) > self._max_history:
                self._history = self._history[-self._max_history:]
            callbacks = list(self._subscribers.get(event.type, []))

        # Appel en dehors du verrou pour eviter les blocages
        for cb in callbacks:
            try:
                cb(event)
            except Exception:  # noqa: BLE001
                pass  # Un subscriber qui echoue ne doit pas casser les autres

    def get_history(self, event_type: Optional[str] = None, limit: int = 100) -> List[Event]:
        with self._lock:
            history = self._history
            if event_type:
                history = [e for e in history if e.type == event_type]
            return history[-limit:]

    def clear_history(self) -> None:
        with self._lock:
            self._history.clear()


event_bus = EventBus()
