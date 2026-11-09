"""Ø§Ø®ØªØ¨Ø§Ø± end-to-end: ØªÙ†ÙÙŠØ° Ø£Ø¯Ø§Ø© Ø¹Ø¨Ø± ÙƒØ§Ù…Ù„ Ø§Ù„Ù…Ø³Ø§Ø±."""

import sys
import os
import tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.integration import aethra
from core.task import TaskStatus


def test_shell_exec_without_approval_fails():
    task_id = aethra.task_engine.create_task(
        "ls_test", "shell.exec",
        metadata={"command": "ls /tmp", "actor": "test_user"},
    )
    try:
        aethra.task_runner.run_task(aethra.task_engine.get_task(task_id))
        assert False, "should fail because shell.exec requires approval"
    except PermissionError as e:
        assert "approval" in str(e).lower() or "Ù…ÙˆØ§ÙÙ‚" in str(e)
    print("OK: shell exec without approval fails")


def test_shell_exec_with_approval_succeeds():
    task_id = aethra.task_engine.create_task(
        "echo_test", "shell.exec",
        metadata={"command": "echo hello_aethra", "actor": "test_user"},
    )
    task = aethra.task_engine.get_task(task_id)

    gate = aethra.tool_dispatcher.request_execution(
        tool_id="shell.exec",
        params={"command": "echo hello_aethra"},
        actor="test_user",
    )
    assert not gate.approved

    aethra.tool_dispatcher.approve_gate(gate.tool_id, "admin")

    result = aethra.task_runner.run_task(task, gate_id=gate.tool_id)
    assert "hello_aethra" in result
    assert task.status == TaskStatus.COMPLETED
    print("OK: shell exec with approval succeeds")


def test_file_write_and_read():
    tmpdir = tempfile.mkdtemp()
    test_file = os.path.join(tmpdir, "test_aethra.txt")

    try:
        write_task = aethra.task_engine.create_task(
            "write_test", "file.exec",
            metadata={"tool_id": "file.write", "operation": "write", "path": test_file, "content": "AETHRA test content", "actor": "test_user"},
        )

        gate = aethra.tool_dispatcher.request_execution(
            tool_id="file.write",
            params={"operation": "write", "path": test_file, "content": "AETHRA test content"},
            actor="test_user",
        )
        aethra.tool_dispatcher.approve_gate(gate.tool_id, "admin")

        write_result = aethra.task_runner.run_task(write_task, gate_id=gate.tool_id)
        assert write_result == "ØªÙ… Ø§Ù„ÙƒØªØ§Ø¨Ø© Ø¥Ù„Ù‰ " + test_file

        read_task = aethra.task_engine.create_task(
            "read_test", "file.exec",
            metadata={"tool_id": "file.read", "operation": "read", "path": test_file, "actor": "test_user"},
        )

        read_result = aethra.task_runner.run_task(read_task)
        assert "AETHRA test content" in read_result
        print("OK: file write and read")
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)


def test_event_published_on_execution():
    initial_count = len(aethra.event_bus.get_history())

    task_id = aethra.task_engine.create_task(
        "event_test", "shell.exec",
        metadata={"command": "echo event_test", "actor": "test_user"},
    )
    task = aethra.task_engine.get_task(task_id)

    gate = aethra.tool_dispatcher.request_execution(
        tool_id="shell.exec",
        params={"command": "echo event_test"},
        actor="test_user",
    )
    aethra.tool_dispatcher.approve_gate(gate.tool_id, "admin")
    aethra.task_runner.run_task(task, gate_id=gate.tool_id)

    final_count = len(aethra.event_bus.get_history())
    assert final_count > initial_count
    print("OK: event published on execution")


if __name__ == "__main__":
    test_shell_exec_without_approval_fails()
    test_shell_exec_with_approval_succeeds()
    test_file_write_and_read()
    test_event_published_on_execution()
    print("\n=== E2E tool execution tests PASSED ===")
