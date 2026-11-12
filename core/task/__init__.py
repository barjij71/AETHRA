"""TaskEngine - moteur de taches avec machine a etats, dependances, retry, idempotence.

??tats de t??che :
    QUEUED, PREPARING, WAITING_DEPENDENCY, WAITING_APPROVAL, APPROVED,
    RUNNING, PAUSED, RETRYING, COMPLETED, FAILED, CANCELLED, ERROR
"""

from __future__ import annotations

import enum
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class TaskStatus(enum.Enum):
    QUEUED = "QUEUED"
    PREPARING = "PREPARING"
    WAITING_DEPENDENCY = "WAITING_DEPENDENCY"
    WAITING_APPROVAL = "WAITING_APPROVAL"
    APPROVED = "APPROVED"
    RUNNING = "RUNNING"
    PAUSED = "PAUSED"
    RETRYING = "RETRYING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    CANCELLED = "CANCELLED"
    ERROR = "ERROR"


@dataclass
class Task:
    """Une tache executable dans AETHRA."""

    id: str
    name: str
    description: str = ""
    status: TaskStatus = TaskStatus.QUEUED
    dependencies: List[str] = field(default_factory=list)
    dependents: List[str] = field(default_factory=list)
    max_retries: int = 3
    retry_count: int = 0
    timeout_seconds: Optional[int] = None
    created_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    error: Optional[str] = None
    result: Any = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def can_start(self, completed_task_ids: set[str]) -> bool:
        """La tache peut-elle demarrer (toutes les dependances terminees) ?"""
        return all(dep in completed_task_ids for dep in self.dependencies)

    def mark_started(self) -> None:
        self.status = TaskStatus.RUNNING
        self.started_at = time.time()

    def mark_completed(self, result: Any = None) -> None:
        self.status = TaskStatus.COMPLETED
        self.result = result
        self.completed_at = time.time()

    def mark_failed(self, error: str) -> None:
        self.status = TaskStatus.FAILED
        self.error = error
        self.completed_at = time.time()

    def mark_retrying(self) -> None:
        self.status = TaskStatus.RETRYING
        self.retry_count += 1


class TaskEngine:
    """Moteur d execution de taches avec dependances et retry."""

    def __init__(self) -> None:
        self._tasks: Dict[str, Task] = {}
        self._lock = threading.RLock()
        self._handlers: Dict[str, Callable[[Task], Any]] = {}
        self._completed: set[str] = set()

    def register_handler(self, task_type: str, handler: Callable[[Task], Any]) -> None:
        self._handlers[task_type] = handler

    def create_task(
        self,
        name: str,
        task_type: str,
        description: str = "",
        dependencies: Optional[List[str]] = None,
        max_retries: int = 3,
        timeout_seconds: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        task_id = str(uuid.uuid4())
        task = Task(
            id=task_id,
            name=name,
            description=description,
            dependencies=dependencies or [],
            max_retries=max_retries,
            timeout_seconds=timeout_seconds,
            metadata=metadata or {},
        )
        # Stocke le type pour le handler
        task.metadata["task_type"] = task_type
        with self._lock:
            self._tasks[task_id] = task
            # Mettre a jour les dependents des taches dependantes
            for dep in task.dependencies:
                if dep in self._tasks:
                    self._tasks[dep].dependents.append(task_id)
        return task_id

    def get_task(self, task_id: str) -> Optional[Task]:
        with self._lock:
            return self._tasks.get(task_id)

    def list_tasks(self) -> List[Task]:
        with self._lock:
            return list(self._tasks.values())

    def get_ready_tasks(self) -> List[Task]:
        """Retourne les taches QUEUED dont toutes les dependances sont completes."""
        ready = []
        with self._lock:
            for task in self._tasks.values():
                if task.status == TaskStatus.QUEUED and task.can_start(self._completed):
                    ready.append(task)
        return ready

    def execute_task(self, task_id: str) -> Any:
        """Execute une tache et retourne son resultat."""
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise ValueError(f"Tache introuvable: {task_id}")
            if task.status != TaskStatus.QUEUED:
                raise ValueError(f"Tache {task_id} n est pas en QUEUED (status: {task.status.value})")

        task_type = task.metadata.get("task_type", "default")
        handler = self._handlers.get(task_type)
        if handler is None:
            raise ValueError(f"Aucun handler pour le type: {task_type}")

        task.mark_started()
        try:
            result = handler(task)
            task.mark_completed(result)
            with self._lock:
                self._completed.add(task_id)
            return result
        except Exception as exc:  # noqa: BLE001
            task.mark_failed(str(exc))
            if task.retry_count < task.max_retries:
                task.mark_retrying()
            raise
