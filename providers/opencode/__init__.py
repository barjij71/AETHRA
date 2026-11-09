"""OpenCode provider - adaptateur pour OpenCode sur la machine instabox.

OpenCode peut etre utilise comme :
1. Provider de modeles (via son API locale ou CLI)
2. Outil d execution (terminal, commande, navigation)
"""

from __future__ import annotations

import subprocess
from typing import Any, Dict, Optional

from core.registry import core_registry


class OpenCodeProvider:
    """Adaptateur OpenCode pour AETHRA."""

    def __init__(self) -> None:
        self.name = "opencode"
        self.available = self._check_available()

    def _check_available(self) -> bool:
        try:
            result = subprocess.run(
                ["opencode", "--version"],
                capture_output=True, text=True, timeout=10,
            )
            return result.returncode == 0
        except Exception:
            return False

    def execute_command(self, command: str, cwd: Optional[str] = None) -> Dict[str, Any]:
        """Execute une commande via OpenCode/shell."""
        try:
            result = subprocess.run(
                command, shell=True, capture_output=True, text=True,
                cwd=cwd, timeout=120,
            )
            return {
                "success": result.returncode == 0,
                "stdout": result.stdout,
                "stderr": result.stderr,
                "exit_code": result.returncode,
            }
        except Exception as exc:  # noqa: BLE001
            return {
                "success": False,
                "stdout": "",
                "stderr": str(exc),
                "exit_code": -1,
            }

    def list_models(self) -> list[str]:
        """Liste les modeles disponibles via OpenCode."""
        # OpenCode n expose pas encore de liste de modeles via CLI simple
        return ["opencode-local"]

    def is_available(self) -> bool:
        return self.available


# Enregistrement dans le registre global
opencode_provider = OpenCodeProvider()
core_registry.register("provider.opencode", opencode_provider)
