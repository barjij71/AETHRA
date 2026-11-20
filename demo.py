#!/usr/bin/env python3
"""AETHRA Demo - ÙˆØ§Ø¬Ù‡Ø© ØªØ¬Ø±ÙŠØ¨ÙŠØ© ØªÙØ§Ø¹Ù„ÙŠØ© Ù„Ù„Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø£ÙˆÙ„.

ØªØ³Ù…Ø­ Ù‡Ø°Ù‡ Ø§Ù„ÙˆØ§Ø¬Ù‡Ø© Ù„Ù„Ù…Ø³ØªØ®Ø¯Ù… Ø¨Ù€:
1. Ø¥Ø±Ø³Ø§Ù„ Ø£ÙˆØ§Ù…Ø± Ù†ØµÙŠØ© Ø¥Ù„Ù‰ AETHRA
2. Ø±Ø¤ÙŠØ© Ù…Ø³Ø§Ø± Ø§Ù„ØªÙ†ÙÙŠØ° Ø§Ù„ÙƒØ§Ù…Ù„ (Plan â†’ Tool â†’ Execution â†’ Result)
3. Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ§Øª ÙˆØ§Ù„Ù…ÙˆØ§ÙÙ‚Ø§Øª
4. Ø¹Ø±Ø¶ Ø§Ù„Ø°Ø§ÙƒØ±Ø© ÙˆØ§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚

Ø§Ù„ÙˆØ§Ø¬Ù‡Ø© ØªØ¹Ù…Ù„ Ø¹Ù„Ù‰ Ø§Ù„Ø­Ø§Ø³Ø¨Ø© Ø§Ù„Ø³Ø­Ø§Ø¨ÙŠØ© Ø¹Ø¨Ø± instabox SSH.
"""

from __future__ import annotations
import sys
import os
import time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from core.integration import aethra
from core.task import TaskStatus
from tools.tool import ToolCategory, ToolRisk


def print_header():
    """Ø·Ø¨Ø§Ø¹Ø© Ø±Ø£Ø³ Ø§Ù„ÙˆØ§Ø¬Ù‡Ø© Ø§Ù„ØªØ¬Ø±ÙŠØ¨ÙŠØ©."""
    print("=" * 60)
    print("  AETHRA - Personal AI Execution Environment")
    print("  ÙˆØ§Ø¬Ù‡Ø© ØªØ¬Ø±ÙŠØ¨ÙŠØ© ØªÙØ§Ø¹Ù„ÙŠØ© Ù„Ù„Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø£ÙˆÙ„")
    print("  phiÃªnØ©: 0.1.0-demo")
    print("=" * 60)
    print("")
    print("Ø§Ù„Ù…Ø³Ø§Ø± Ø§Ù„ØªÙ†ÙÙŠØ°ÙŠ: Ø®Ø·Ø© â†’ Ø£Ø¯Ø§Ø© â†’ ØªÙ†ÙÙŠØ° â†’ Ù†ØªÙŠØ¬Ø© â†’ ØªØ¯Ù‚ÙŠÙ‚")
    print("")

def print_menu():
    """Ø·Ø¨Ø§Ø¹Ø© Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„Ø®ÙŠØ§Ø±Ø§Øª."""
    print("
Ø§Ø®ØªØ± Ø¥Ø¬Ø±Ø§Ø¡Ù‹:")
    print("  1. Ø¥Ø±Ø³Ø§Ù„ Ø£Ù…Ø± Ù†ØµÙŠ (Ø¯Ø±Ø¯Ø´Ø©)")
    print("  2. ØªÙ†ÙÙŠØ° Ø£Ù…Ø± Ù†Ø¸Ø§Ù… (shell)")
    print("  3. Ø¹Ø±Ø¶ Ø§Ù„Ù…Ù‡Ø§Ù… Ø§Ù„Ø­Ø§Ù„ÙŠØ©")
    print("  4. Ø¹Ø±Ø¶ Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚ (Audit Log)")
    print("  5. Ø¹Ø±Ø¶ Ø­Ø§Ù„Ø© Ø§Ù„Ù†Ø¸Ø§Ù…")
    print("  6. Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ© (Permission Test)")
    print("  0. Ø®Ø±ÙˆØ¬")
    print("")


def demo_chat(conversation_id: str = "demo_conv"):
    """Ø¹ÙŠÙ†Ø© Ù…Ù† Ø§Ù„Ø¯Ø±Ø¯Ø´Ø© Ø§Ù„ØªÙØ§Ø¹Ù„ÙŠØ©."""
    print("
--- ÙˆØ¶Ø¹ Ø§Ù„Ø¯Ø±Ø¯Ø´Ø© (Ø§ÙƒØªØ¨ 'Ø®Ø±ÙˆØ¬' Ù„Ù„Ø¹ÙˆØ¯Ø©) ---")
    while True:
        user_input = input("
Ø£Ù†Øª: ").strip()
        if user_input.lower() in ("Ø®Ø±ÙˆØ¬", "exit", "back", "0"):
            break
        if not user_input:
            continue

        print("
[AETHRA] Ù…Ø¹Ø§Ù„Ø¬Ø© Ø§Ù„Ø·Ù„Ø¨...")
        resp = aethra.hermes_api.send_message(conversation_id, user_input, actor="demo_user")

        if resp.success:
            print(f"
[AETHRA] {resp.message}")
            if resp.steps:
                print("  Ø®Ø·ÙˆØ§Øª Ø§Ù„ØªÙ†ÙÙŠØ°:")
                for step in resp.steps:
                    status_icon = "âœ“" if step.status == "completed" else "âœ—" if step.status == "failed" else "â—‹"
                    print(f"    {status_icon} {step.step_type}: {step.label}")
        else:
            print(f"
[AETHRA] Ø®Ø·Ø£: {resp.message}")
            if resp.error:
                print(f"  Ø§Ù„ØªÙØ§ØµÙŠÙ„: {resp.error}")


def demo_shell_command():
    """Ø¹ÙŠÙ†Ø© Ù…Ù† ØªÙ†ÙÙŠØ° Ø£Ù…Ø± Ù†Ø¸Ø§Ù…."""
    print("
--- ØªÙ†ÙÙŠØ° Ø£Ù…Ø± Ù†Ø¸Ø§Ù… ---")
    command = input("Ø£Ø¯Ø®Ù„ Ø§Ù„Ø£Ù…Ø±: ").strip()
    if not command:
        return

    print(f"
[AETHRA] ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ù…Ø±: {command}")

    # Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø©
    task_id = aethra.task_engine.create_task(
        f"shell:{command[:30]}",
        "shell.exec",
        metadata={"command": command, "actor": "demo_user"},
    )
    task = aethra.task_engine.get_task(task_id)
    print(f"  [Task] ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ø§Ù„Ù…Ù‡Ù…Ø©: {task.id[:8]} (Ø§Ù„Ø­Ø§Ù„Ø©: {task.status.value})")

    # Ø·Ù„Ø¨ Ø§Ù„ØªÙ†ÙÙŠØ° Ø¹Ø¨Ø± ToolDispatcher (ÙŠÙ…Ø± Ø¹Ø¨Ø± Policy/Permission)
    gate = aethra.tool_dispatcher.request_execution(
        tool_id="shell.exec",
        params={"command": command},
        actor="demo_user",
    )

    if not gate.approved:
        print(f"  [Permission] Ø§Ù„Ø£Ù…Ø± ÙŠØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø© (Ø§Ù„Ù…Ø³ØªÙˆÙ‰: {gate.risk_level.value})")
        approve = input("  Ù‡Ù„ ØªÙˆØ§ÙÙ‚ Ø¹Ù„Ù‰ Ø§Ù„ØªÙ†ÙÙŠØ°ØŸ (Ù†/Ù„Ø§): ").strip().lower()
        if approve in ("Ù†", "Ù†Ø¹Ù…", "y", "yes"):
            aethra.tool_dispatcher.approve_gate(gate.tool_id, "demo_user")
            print("  [Permission] ØªÙ…Øª Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©")
        else:
            print("  [Permission] ØªÙ… Ø§Ù„Ø±ÙØ¶")
            return

    # Ø§Ù„ØªÙ†ÙÙŠØ°
    result = aethra.tool_dispatcher.execute(
        tool_id="shell.exec",
        params={"command": command},
        actor="demo_user",
        gate_id=gate.tool_id,
    )

    # ØªØ­Ø¯ÙŠØ« Ø§Ù„Ù…Ù‡Ù…Ø©
    if result.success:
        task.mark_completed(result.output)
        print(f"  [Execution]Ù†Ø¬Ø­ (Ù…Ø¯Ø©: {result.duration_seconds:.3f}Ø«)")
        print(f"  [Output]: {result.output[:200]}")
    else:
        task.mark_failed(result.error or "ÙØ´Ù„ ØºÙŠØ± Ù…Ø¹Ø±ÙˆÙ")
        print(f"  [Execution] ÙØ´Ù„: {result.error}")

    # Ø­Ø¯Ø« Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚
    print(f"  [Audit] ØªÙ… ØªØ³Ø¬ÙŠÙ„ Ø§Ù„Ø­Ø¯Ø« ÙÙŠ Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚")


def demo_list_tasks():
    """Ø¹Ø±Ø¶ Ø§Ù„Ù…Ù‡Ø§Ù… Ø§Ù„Ø­Ø§Ù„ÙŠØ©."""
    print("
--- Ø§Ù„Ù…Ù‡Ø§Ù… Ø§Ù„Ø­Ø§Ù„ÙŠØ© ---")
    tasks = aethra.task_engine.list_tasks()
    if not tasks:
        print("  Ù„Ø§ ØªÙˆØ¬Ø¯ Ù…Ù‡Ø§Ù….")
        return

    for t in tasks[-10:]:  # Ø¢Ø®Ø± 10 Ù…Ù‡Ø§Ù…
        status_icon = {
            "QUEUED": "â—‹",
            "RUNNING": "âŸ³",
            "COMPLETED": "âœ“",
            "FAILED": "âœ—",
            "CANCELLED": "âŠ˜",
            "RETRYING": "â†»",
        }.get(t.status.value, "?")
        print(f"  {status_icon} {t.id[:8]} | {t.name} | {t.status.value}")


def demo_audit_log():
    """Ø¹Ø±Ø¶ Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚."""
    print("
--- Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚ (Ø¢Ø®Ø± 10 Ø£Ø­Ø¯Ø§Ø«) ---")
    logs = aethra.permission_broker if hasattr(aethra, 'permission_broker') else None
    if logs:
        from core.security_policy import AuditEventType
        audit_logs = aethra.permission_broker if False else None
        # Ø§Ø³ØªØ®Ø¯Ø§Ù… security_policy Ù…Ø¨Ø§Ø´Ø±Ø© Ø¥Ø°Ø§ ÙƒØ§Ù† Ù…ØªØ§Ø­
        try:
            from core.security_policy import security_policy
            records = security_policy.get_audit_log(limit=10)
            for r in records:
                print(f"  [{r.timestamp:.0f}] {r.event_type.value} | {r.actor} | {r.resource} | {r.decision.value if r.decision else '-'}")
        except Exception as e:
            print(f"  ØºÙŠØ± Ù…ØªØ§Ø­: {e}")
    else:
        print("  Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚ ØºÙŠØ± Ù…ØªØ§Ø­ ÙÙŠ Ù‡Ø°Ø§ Ø§Ù„Ø¹Ø±Ø¶ Ø§Ù„ØªÙˆØ¶ÙŠØ­ÙŠ")


def demo_system_status():
    """Ø¹Ø±Ø¶ Ø­Ø§Ù„Ø© Ø§Ù„Ù†Ø¸Ø§Ù…."""
    print("
--- Ø­Ø§Ù„Ø© Ø§Ù„Ù†Ø¸Ø§Ù… ---")
    try:
        from core.monitoring import system_monitor, health_checker
        snap = system_monitor.get_latest_snapshot()
        if snap:
            print(f"  ÙˆØ­Ø¯Ø© Ø§Ù„Ù…Ø¹Ø§Ù„Ø¬Ø©: {snap.cpu_percent}%")
            print(f"  Ø§Ù„Ø°Ø§ÙƒØ±Ø©: {snap.memory_percent}% (Ù…ØªØ§Ø­Ø©: {snap.memory_available_mb:.0f} Ù…ÙŠØ¬Ø§Ø¨Ø§ÙŠØª)")
            print(f"  Ø§Ù„Ù‚Ø±Øµ: {snap.disk_percent}% (Ù…Ø³Ø§Ø­Ø© Ø­Ø±Ø©: {snap.disk_free_gb:.1f} Ø¬ÙŠØ¬Ø§Ø¨Ø§ÙŠØª)")
        else:
            print("  Ù„Ø§ ØªÙˆØ¬Ø¯ Ø¨ÙŠØ§Ù†Ø§Øª Ù…Ø±Ø§Ù‚Ø¨Ø© Ø¨Ø¹Ø¯")

        results = health_checker.run_all_checks()
        if results:
            print("
  ÙØ­ÙˆØµØ§Øª Ø§Ù„ØµØ­Ø©:")
            for name, r in results.items():
                icon = "âœ“" if r.healthy else "âœ—"
                print(f"    {icon} {name}: {r.message}")
    except Exception as e:
        print(f"  ØºÙŠØ± Ù…ØªØ§Ø­: {e}")


def demo_permission_test():
    """Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ©."""
    print("
--- Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ© ---")
    print("Ø§Ù„Ù…Ø³ØªÙˆÙŠØ§Øª: SAFE, LOW, SENSITIVE, DESTRUCTIVE")
    cap = input("Ø§Ù„Ù‚Ø¯Ø±Ø© (Ù…Ø«Ù„ shell.exec): ").strip() or "shell.exec"
    risk_str = input("Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ù…Ø®Ø§Ø·Ø±Ø© (SAFE/LOW/SENSITIVE/DESTRUCTIVE): ").strip() or "SAFE"

    try:
        risk = {"SAFE": 0, "LOW": 1, "SENSITIVE": 2, "DESTRUCTIVE": 3}[risk_str.upper()]
    except KeyError:
        risk = 0

    from core.policy import RiskLevel
    risk_levels = [RiskLevel.SAFE, RiskLevel.LOW, RiskLevel.SENSITIVE, RiskLevel.DESTRUCTIVE]

    perm = aethra.permission_broker.request_permission(
        actor="demo_user",
        capability=cap,
        resource="demo_resource",
        risk_level=risk_levels[risk],
    )

    print(f"
  Ø§Ù„Ù†ØªÙŠØ¬Ø©: {perm.decision.value}")
    print(f"  Ø§Ù„Ù…Ø·Ù„ÙˆØ¨ Ù…ÙˆØ§ÙÙ‚Ø©: {'Ù†Ø¹Ù…' if perm.decision.value == 'REQUIRES_APPROVAL' else 'Ù„Ø§'}")

    if perm.decision.value == "REQUIRES_APPROVE" or perm.decision.value == "REQUIRES_APPROVAL":
        approve = input("  Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©ØŸ (Ù†/Ù„Ø§): ").strip().lower()
        if approve in ("Ù†", "Ù†Ø¹Ù…", "y", "yes"):
            aethra.permission_broker.approve(perm.id, "demo_user")
            print("  âœ“ ØªÙ…Øª Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©")
        else:
            print("  âœ— ØªÙ… Ø§Ù„Ø±ÙØ¶")


def main():
    """Ø§Ù„Ø¯Ø§Ù„Ø© Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ© Ù„Ù„ÙˆØ§Ø¬Ù‡Ø© Ø§Ù„ØªØ¬Ø±ÙŠØ¨ÙŠØ©."""
    print_header()

    while True:
        print_menu()
        choice = input("Ø§Ø®ØªÙŠØ§Ø±Ùƒ: ").strip()

        if choice == "1":
            demo_chat()
        elif choice == "2":
            demo_shell_command()
        elif choice == "3":
            demo_list_tasks()
        elif choice == "4":
            demo_audit_log()
        elif choice == "5":
            demo_system_status()
        elif choice == "6":
            demo_permission_test()
        elif choice == "0":
            print("
[AETHRA] Ø´ÙƒØ±Ø§Ù‹ Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù… Ø§Ù„Ù†Ø³Ø®Ø© Ø§Ù„ØªØ¬Ø±ÙŠØ¨ÙŠØ©. Ø¥Ù„Ù‰ Ø§Ù„Ù„Ù‚Ø§Ø¡!")
            break
        else:
            print("  Ø§Ø®ØªÙŠØ§Ø± ØºÙŠØ± ØµØ­ÙŠØ­ØŒ ÙŠØ±Ø¬Ù‰ Ø§Ù„Ù…Ø­Ø§ÙˆÙ„Ø© Ù…Ø±Ø© Ø£Ø®Ø±Ù‰")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("

[AETHRA] ØªÙ… Ø§Ù„Ø¥ÙŠÙ‚Ø§Ù Ù…Ù† Ù‚Ø¨Ù„ Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù….")
    except Exception as e:
        print(f"
[AETHRA] Ø®Ø·Ø£ ØºÙŠØ± Ù…ØªÙˆÙ‚Ø¹: {e}")
        import traceback
        traceback.print_exc()
