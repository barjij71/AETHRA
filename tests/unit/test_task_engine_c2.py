"""Tests C2 - Propagation d'echec et annulation dans un workflow DAG."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.task import TaskEngine, TaskStatus
from core.workflow import Workflow


def test_workflow_failure_propagation():
    engine = TaskEngine()
    wf = Workflow("wf_echec")
    wf.set_engine(engine)

    def fail_handler(task):
        raise RuntimeError("Tache critique echec")

    def ok_handler(task):
        return "ok"

    engine.register_handler("fail", fail_handler)
    engine.register_handler("ok", ok_handler)

    t_fail = engine.create_task("FailTask", "fail")
    t_dep = engine.create_task("Dependent", "ok", dependencies=[t_fail])

    wf.add_task(t_fail)
    wf.add_task(t_dep)

    results = wf.execute()

    task_fail = engine.get_task(t_fail)
    assert task_fail.status == TaskStatus.FAILED

    task_dep = engine.get_task(t_dep)
    assert task_dep.status == TaskStatus.ERROR
    assert "Dependence echouee" in task_dep.error
    print("  OK: test_workflow_failure_propagation")


def test_workflow_success():
    engine = TaskEngine()
    wf = Workflow("wf_succes")
    wf.set_engine(engine)

    order = []

    def make_handler(name):
        def handler(task):
            order.append(name)
            return "done_" + name
        return handler

    engine.register_handler("default", make_handler("X"))

    t1 = engine.create_task("Step1", "default")
    t2 = engine.create_task("Step2", "default", dependencies=[t1])
    t3 = engine.create_task("Step3", "default", dependencies=[t2])

    wf.add_task(t1)
    wf.add_task(t2)
    wf.add_task(t3)

    results = wf.execute()
    assert order == ["Step1", "Step2", "Step3"]
    assert engine.get_task(t1).status == TaskStatus.COMPLETED
    assert engine.get_task(t2).status == TaskStatus.COMPLETED
    assert engine.get_task(t3).status == TaskStatus.COMPLETED
    print("  OK: test_workflow_success")


def test_workflow_cycle_detection():
    engine = TaskEngine()
    wf = Workflow("wf_cycle")
    wf.set_engine(engine)

    engine.register_handler("default", lambda task: "ok")

    t_a = engine.create_task("A", "default")
    t_b = engine.create_task("B", "default", dependencies=[t_a])
    task_a = engine.get_task(t_a)
    task_a.dependencies.append(t_b)

    wf.add_task(t_a)
    wf.add_task(t_b)

    try:
        wf.topological_order()
        assert False, "Devrait lever ValueError pour cycle"
    except ValueError as e:
        assert "Cycle" in str(e)
    print("  OK: test_workflow_cycle_detection")


def test_idempotency_repeat_execution_blocked():
    engine = TaskEngine()
    engine.register_handler("default", lambda task: "result")

    t = engine.create_task("Idempotent", "default")
    engine.execute_task(t)

    task = engine.get_task(t)
    assert task.status == TaskStatus.COMPLETED
    try:
        engine.execute_task(t)
        assert False, "Devrait lever ValueError"
    except ValueError:
        pass
    print("  OK: test_idempotency_repeat_execution_blocked")


if __name__ == "__main__":
    test_workflow_failure_propagation()
    test_workflow_success()
    test_workflow_cycle_detection()
    test_idempotency_repeat_execution_blocked()
    print("
=== Tous les tests C2 sont reussis ===")
