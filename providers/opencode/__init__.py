"""OpenCode provider - adaptateur pour OpenCode sur la machine instabox.

OpenCode sert de :
1. Provider de modeles (via son API locale ou CLI)
2. Outil d execution (terminal, commande, navigation)
3. Agent d execution pour les taches AETHRA
"""

from __future__ import annotations

import subprocess
import threading
import time
import uuid
from typing import Any, Callable, Dict, List, Optional

from core.eventbus import event_bus, Event
from core.registry import core_registry


class OpenCodeProvider:
    """Adaptateur OpenCode pour AETHRA."""

    def __init__(self) -> None:
        self.name = "opencode"
        self.available = self._check_available()
        self._execution_history: List[Dict[str, Any]] = []

    def _check_available(self) -> bool:
        try:
            result = subprocess.run(
                ["opencode", "--version"],
                capture_output=True, text=True, timeout=10,
            )
            return result.returncode == 0
        except Exception:
            return False

    def is_available(self) -> bool:
        return self.available

    def execute_command(self, command: str, cwd: Optional[str] = None,
                        stream_callback: Optional[Callable[[str], None]] = None) -> Dict[str, Any]:
        """Execute une commande via OpenCode/shell avec streaming optionnel."""
        exec_id = str(uuid.uuid4())
        start = time.time()

        event = Event(
            type="opencode.command.start",
            payload={"exec_id": exec_id, "command": command, "cwd": cwd},
            source="opencode.provider",
        )
        event_bus.publish(event)

        try:
            result = subprocess.run(
                command, shell=True, capture_output=True, text=True,
                cwd=cwd, timeout=120,
            )
            output = {
                "exec_id": exec_id,
                "command": command,
                "cwd": cwd,
                "success": result.returncode == 0,
                "stdout": result.stdout,
                "stderr": result.stderr,
                "exit_code": result.returncode,
                "duration_seconds": time.time() - start,
            }
        except Exception as exc:  # noqa: BLE001
            output = {
                "exec_id": exec_id,
                "command": command,
                "cwd": cwd,
                "success": False,
                "stdout": "",
                "stderr": str(exc),
                "exit_code": -1,
                "duration_seconds": time.time() - start,
            }

        self._execution_history.append(output)

        event = Event(
            type="opencode.command.end",
            payload=output,
            source="opencode.provider",
        )
        event_bus.publish(event)

        return output

    def list_models(self) -> List[str]:
        """Liste les modeles disponibles via OpenCode."""
        return ["opencode-local"]

    def get_capabilities(self) -> List[str]:
        """Capacites du provider OpenCode."""
        return ["terminal", "command_execution", "file_operations", "model_inference"]

    def run_task(self, task_description: str, context: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Fait executer une tache par OpenCode comme agent."""
        output = self.execute_command(
            f'opencode run "{task_description}"',
            stream_callback=None,
        )
        return {
            "task": task_description,
            "provider": "opencode",
            "result": output,
        }


# Instance globale
opencode_provider = OpenCodeProvider()
core_registry.register("provider.opencode", opencode_provider)
