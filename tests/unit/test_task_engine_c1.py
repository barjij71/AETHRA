"""Tests C1 - TaskEngine: creation, execution, retry, dependencies, cancellation."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.task import TaskEngine, TaskStatus, Task


def test_task_creation_and_execution():
    engine = TaskEngine()
    results = []

    def handler(task):
        results.append(task.name)
        return "result_of_" + task.name

    engine.register_handler("default", handler)
    task_id = engine.create_task("T1", "default", "Tache test 1")

    task = engine.get_task(task_id)
    assert task.status == TaskStatus.QUEUED
    assert task.name == "T1"

    result = engine.execute_task(task_id)
    assert result == "result_of_T1"
    assert results == ["T1"]

    task = engine.get_task(task_id)
    assert task.status == TaskStatus.COMPLETED
    assert task.result == "result_of_T1"
    print("  OK: test_task_creation_and_execution")


def test_task_dependency_ordering():
    engine = TaskEngine()
    execution_order = []

    def make_handler(name):
        def handler(task):
            execution_order.append(name)
            return "done_" + name
        return handler

    engine.register_handler("default", make_handler("X"))

    t_x = engine.create_task("X", "default")
    t_y = engine.create_task("Y", "default", dependencies=[t_x])
    t_z = engine.create_task("Z", "default", dependencies=[t_x, t_y])

    ready = engine.get_ready_tasks()
    assert len(ready) == 1
    assert ready[0].name == "X"

    engine.execute_task(t_x)
    ready = engine.get_ready_tasks()
    assert len(ready) == 1
    assert ready[0].name == "Y"

    engine.execute_task(t_y)
    ready = engine.get_ready_tasks()
    assert len(ready) == 1
    assert ready[0].name == "Z"

    engine.execute_task(t_z)
    assert execution_order == ["X", "Y", "Z"]
    print("  OK: test_task_dependency_ordering")


def test_task_retry_on_failure():
    engine = TaskEngine()
    attempts = []

    def flaky_handler(task):
        attempts.append(1)
        if len(attempts) < 3:
            raise RuntimeError("Echec temporaire")
        return "succes_apres_retry"

    engine.register_handler("flaky", flaky_handler)
    task_id = engine.create_task("Flaky", "flaky", max_retries=5)

    try:
        engine.execute_task(task_id)
    except RuntimeError:
        pass

    task = engine.get_task(task_id)
    assert task.status == TaskStatus.RETRYING
    assert task.retry_count == 1
    print("  OK: test_task_retry_on_failure")


def test_task_cancellation_propagation():
    engine = TaskEngine()
    executed = []

    def handler(task):
        executed.append(task.name)
        return "done"

    engine.register_handler("default", handler)

    t_a = engine.create_task("A", "default")
    t_b = engine.create_task("B", "default", dependencies=[t_a])
    t_c = engine.create_task("C", "default", dependencies=[t_a])

    task_a = engine.get_task(t_a)
    task_a.status = TaskStatus.CANCELLED

    ready = engine.get_ready_tasks()
    assert len(ready) == 0
    print("  OK: test_task_cancellation_propagation")


def test_task_timeout_field():
    engine = TaskEngine()
    task_id = engine.create_task("AvecTimeout", "default", timeout_seconds=30)
    task = engine.get_task(task_id)
    assert task.timeout_seconds == 30
    print("  OK: test_task_timeout_field")


if __name__ == "__main__":
    test_task_creation_and_execution()
    test_task_dependency_ordering()
    test_task_retry_on_failure()
    test_task_cancellation_propagation()
    test_task_timeout_field()
    print("
=== Tous les tests C1 sont reussis ===")
