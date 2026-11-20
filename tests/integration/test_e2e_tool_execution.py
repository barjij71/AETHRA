"""Ø§Ø®ØªØ¨Ø§Ø± end-to-end: ØªÙ†ÙÙŠØ° Ø£Ø¯Ø§Ø© Ø¹Ø¨Ø± ÙƒØ§Ù…Ù„ Ø§Ù„Ù…Ø³Ø§Ø±.

Ø§Ù„Ù…Ø³Ø§Ø±:
Task -> TaskRunner -> ToolDispatcher (Policy/Permission/Approval) -> Executor -> Event -> Audit
"""

import sys
import os
import tempfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.integration import aethra
from core.task import TaskStatus


def test_shell_exec_without_approval_fails():
    task_id = aethra.task_engine.create_task(
        "ls_test", "shell.exec",
        params={"command": "ls /tmp"},
        actor="test_user",
    )
    try:
        aethra.task_runner.run_task(aethra.task_engine.get_task(task_id))
        assert False, "ÙŠØ¬Ø¨ Ø£Ù† ÙŠÙØ´Ù„ Ù„Ø£Ù† shell.exec ÙŠØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø©"
    except PermissionError as e:
        assert "Ù…ÙˆØ§ÙÙ‚" in str(e) or "Ù…ÙˆØ§ÙÙ‚Ø©" in str(e)
    print("OK: shell exec without approval fails")


def test_shell_exec_with_approval_succeeds():
    task_id = aethra.task_engine.create_task(
        "echo_test", "shell.exec",
        params={"command": "echo hello_aethra"},
        actor="test_user",
    )
    task = aethra.task_engine.get_task(task_id)

    gate = aethra.tool_dispatcher.request_execution(
        tool_id="shell.exec",
        params={"command": "echo hello_aethra"},
        actor="test_user",
    )
    assert not gate.approved

    aethra.tool_dispatcher.approve_gate(gate.tool_id, "admin")

    result = aethra.task_runner.run_task(task)
    assert "hello_aethra" in result
    assert task.status == TaskStatus.COMPLETED
    print("OK: shell exec with approval succeeds")


def test_file_write_and_read():
    tmpdir = tempfile.mkdtemp()
    test_file = os.path.join(tmpdir, "test_aethra.txt")

    try:
        write_task = aethra.task_engine.create_task(
            "write_test", "file.exec",
            params={"tool_id": "file.write", "operation": "write", "path": test_file, "content": "AETHRA test content"},
            actor="test_user",
        )
        write_task.metadata["tool_id"] = "file.write"

        gate = aethra.tool_dispatcher.request_execution(
            tool_id="file.write",
            params={"operation": "write", "path": test_file, "content": "AETHRA test content"},
            actor="test_user",
        )
        aethra.tool_dispatcher.approve_gate(gate.tool_id, "admin")

        write_result = aethra.task_runner.run_task(write_task)
        assert write_result == "ØªÙ… Ø§Ù„ÙƒØªØ§Ø¨Ø© Ø¥Ù„Ù‰ " + test_file

        read_task = aethra.task_engine.create_task(
            "read_test", "file.exec",
            params={"tool_id": "file.read", "operation": "read", "path": test_file},
            actor="test_user",
        )
        read_task.metadata["tool_id"] = "file.read"

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
        params={"command": "echo event_test"},
        actor="test_user",
    )
    task = aethra.task_engine.get_task(task_id)

    gate = aethra.tool_dispatcher.request_execution(
        tool_id="shell.exec",
        params={"command": "echo event_test"},
        actor="test_user",
    )
    aethra.tool_dispatcher.approve_gate(gate.tool_id, "admin")
    aethra.task_runner.run_task(task)

    final_count = len(aethra.event_bus.get_history())
    assert final_count > initial_count
    print("OK: event published on execution")


if __name__ == "__main__":
    test_shell_exec_without_approval_fails()
    test_shell_exec_with_approval_succeeds()
    test_file_write_and_read()
    test_event_published_on_execution()
    print("
=== Ø§Ø®ØªØ¨Ø§Ø±Ø§Øª end-to-end Ù„Ù„Ø£Ø¯ÙˆØ§Øª Ù†Ø¬Ø­Øª ===")
