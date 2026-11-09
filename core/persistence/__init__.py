"""Persistence / Recovery - etat persiste et reprise apres interruption.

Les taches et workflows doivent etre restaurables :
Running Task -> Persisted State -> Restart -> Recovery -> Resume/Retry.
"""

from __future__ import annotations

import json
import os
import threading
from typing import Any, Dict, Optional


class PersistenceManager:
    """Gere la sauvegarde et restauration de l etat des taches."""

    def __init__(self, storage_dir: str = "/data/workspace/aethra-data/persistence") -> None:
        self.storage_dir = storage_dir
        self._lock = threading.RLock()
        os.makedirs(storage_dir, exist_ok=True)

    def _task_path(self, task_id: str) -> str:
        return os.path.join(self.storage_dir, f"task_{task_id}.json")

    def save_task(self, task_id: str, state: Dict[str, Any]) -> None:
        with self._lock:
            path = self._task_path(task_id)
            tmp = path + ".tmp"
            with open(tmp, "w") as f:
                json.dump(state, f, indent=2, default=str)
            os.replace(tmp, path)

    def load_task(self, task_id: str) -> Optional[Dict[str, Any]]:
        with self._lock:
            path = self._task_path(task_id)
            if not os.path.exists(path):
                return None
            with open(path, "r") as f:
                return json.load(f)

    def delete_task(self, task_id: str) -> bool:
        with self._lock:
            path = self._task_path(task_id)
            if os.path.exists(path):
                os.remove(path)
                return True
            return False

    def list_saved_tasks(self) -> list[str]:
        with self._lock:
            if not os.path.exists(self.storage_dir):
                return []
            files = [f for f in os.listdir(self.storage_dir) if f.startswith("task_") and f.endswith(".json")]
            return sorted(files)
