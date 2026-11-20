"""Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø±Ø¨Ø· Ø¨ÙŠÙ† Ø§Ù„Ø°Ø§ÙƒØ±Ø© ÙˆØ§Ù„Ù…Ù‡Ø§Ù…."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.memory import MemoryEngine
from core.task import TaskEngine, TaskStatus
from core.memory.task_integration import MemoryTaskIntegration


def test_task_start_recorded():
    mem = MemoryEngine()
    engine = TaskEngine()
    integration = MemoryTaskIntegration(engine, mem)

    task_id = engine.create_task("test_task", "default", metadata={"actor": "user1"})
    task = engine.get_task(task_id)
    integration.record_task_start(task, "user1")

    status = mem.read("task", "task:" + task.id + ":status")
    assert status is not None
    assert status["actor"] == "user1"
    assert status["status"] == "QUEUED"
    print("  OK: task start recorded")


def test_task_complete_recorded():
    mem = MemoryEngine()
    engine = TaskEngine()
    integration = MemoryTaskIntegration(engine, mem)

    task_id = engine.create_task("complete_task", "default")
    task = engine.get_task(task_id)
    integration.record_task_complete(task, "success_result")
    task.mark_completed("success_result")

    result = mem.read("task", "task:" + task.id + ":result")
    assert result is not None
    assert "success_result" in str(result)
    print("  OK: task complete recorded")


def test_task_failure_recorded():
    mem = MemoryEngine()
    engine = TaskEngine()
    integration = MemoryTaskIntegration(engine, mem)

    task_id = engine.create_task("fail_task", "default")
    task = engine.get_task(task_id)
    integration.record_task_failure(task, "timeout error")

    status = mem.read("task", "task:" + task.id + ":status")
    assert status is not None
    assert "timeout error" in str(status)
    print("  OK: task failure recorded")


def test_task_context_retrieval():
    mem = MemoryEngine()
    engine = TaskEngine()
    integration = MemoryTaskIntegration(engine, mem)

    task_id = engine.create_task("context_task", "default")
    task = engine.get_task(task_id)
    integration.record_task_start(task, "user1")
    integration.record_task_complete(task, "done")

    ctx = integration.get_task_context(task.id)
    assert ctx is not None
    assert "status" in ctx
    assert "goal" in ctx
    assert "result" in ctx
    print("  OK: task context retrieval")


def test_task_memory_search():
    mem = MemoryEngine()
    engine = TaskEngine()
    integration = MemoryTaskIntegration(engine, mem)

    task_id = engine.create_task("searchable_task", "default")
    task = engine.get_task(task_id)
    integration.record_task_start(task, "user1")
    integration.link_task_to_memory(task.id, "keyword", "é‡è¦ä»»åŠ¡")

    results = integration.search_task_memory("é‡è¦ä»»åŠ¡")
    assert len(results) >= 1
    print("  OK: task memory search")


if __name__ == "__main__":
    test_task_start_recorded()
    test_task_complete_recorded()
    test_task_failure_recorded()
    test_task_context_retrieval()
    test_task_memory_search()
    print("\n=== Memory-Task Integration: PASSED ===")
