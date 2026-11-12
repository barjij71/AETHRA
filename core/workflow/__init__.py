"""Workflow / DAG / Orchestrateur de taches.

Gestion des dependances, ordonnancement topologique, detection de cycles,
concurrency bornee, backpressure.
"""

from __future__ import annotations

from collections import deque
from typing import Any, Dict, List, Set

from core.task import Task, TaskEngine, TaskStatus


class Workflow:
    """Un DAG de taches a executer."""

    def __init__(self, name: str) -> None:
        self.name = name
        self.task_ids: List[str] = []
        self._task_engine: Optional[TaskEngine] = None

    def set_engine(self, engine: TaskEngine) -> None:
        self._task_engine = engine

    def add_task(self, task_id: str) -> None:
        if task_id not in self.task_ids:
            self.task_ids.append(task_id)

    def topological_order(self) -> List[str]:
        """Retourne les IDs de taches dans l ordre topologique.
        Detecte les cycles et l??ve une ValueError si cycle trouve.
        """
        if self._task_engine is None:
            raise RuntimeError("Aucun TaskEngine associe au workflow")

        tasks = {tid: self._task_engine.get_task(tid) for tid in self.task_ids}
        tasks = {tid: t for tid, t in tasks.items() if t is not None}

        # Calcul des degres entrants
        in_degree: Dict[str, int] = {tid: 0 for tid in tasks}
        for tid, task in tasks.items():
            for dep in task.dependencies:
                if dep in in_degree:
                    in_degree[tid] += 1  # dep -> tid, tid a un prealable de plus

        # Correction : in_degree[tid] = nombre de dependances de tid
        in_degree = {tid: len([d for d in task.dependencies if d in tasks]) for tid, task in tasks.items()}

        queue = deque([tid for tid, deg in in_degree.items() if deg == 0])
        order: List[str] = []

        while queue:
            tid = queue.popleft()
            order.append(tid)
            task = tasks[tid]
            for dependent in task.dependents:
                if dependent in in_degree:
                    in_degree[dependent] -= 1
                    if in_degree[dependent] == 0:
                        queue.append(dependent)

        if len(order) != len(tasks):
            remaining = set(tasks.keys()) - set(order)
            raise ValueError(f"Cycle detecte dans le workflow: {remaining}")

        return order

    def execute(self) -> Dict[str, Any]:
        """Execute toutes les taches du workflow dans l ordre topologique."""
        if self._task_engine is None:
            raise RuntimeError("Aucun TaskEngine associe au workflow")

        order = self.topological_order()
        results: Dict[str, Any] = {}

        for tid in order:
            task = self._task_engine.get_task(tid)
            if task is None:
                results[tid] = {"error": "Tache introuvable"}
                continue
            try:
                result = self._task_engine.execute_task(tid)
                results[tid] = result
            except Exception as exc:  # noqa: BLE001
                results[tid] = {"error": str(exc)}
                # Propagation de l echec aux dependants
                for dep_id in task.dependents:
                    dep_task = self._task_engine.get_task(dep_id)
                    if dep_task and dep_task.status == TaskStatus.QUEUED:
                        dep_task.status = TaskStatus.ERROR
                        dep_task.error = f"Dependence echouee: {tid}"

        return results
