"""TerminalWidget - terminal avec streaming stdout/stderr.

Affiche les commandes reelles, stdout, stderr, code de sortie.
Comme dans l interface Hermes.
"""

from __future__ import annotations
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class TerminalOutput:
    """Une ligne de sortie terminal."""
    id: str
    stream: str  # stdout, stderr, system
    content: str
    timestamp: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class TerminalCommand:
    """Une commande executee dans le terminal."""
    id: str
    command: str
    cwd: Optional[str]
    exit_code: Optional[int]
    started_at: float
    completed_at: Optional[float]
    outputs: List[TerminalOutput] = field(default_factory=list)


class TerminalWidget:
    """Widget de terminal avec streaming."""

    def __init__(self) -> None:
        self._commands: List[TerminalCommand] = []
        self._lock = __import__("threading").RLock()

    def start_command(self, command: str, cwd: Optional[str] = None) -> str:
        cmd_id = str(uuid.uuid4())
        cmd = TerminalCommand(
            id=cmd_id,
            command=command,
            cwd=cwd,
            exit_code=None,
            started_at=time.time(),
            completed_at=None,
        )
        with self._lock:
            self._commands.append(cmd)
        return cmd_id

    def add_output(self, cmd_id: str, stream: str, content: str) -> bool:
        output = TerminalOutput(id=str(uuid.uuid4()), stream=stream, content=content)
        with self._lock:
            for cmd in self._commands:
                if cmd.id == cmd_id:
                    cmd.outputs.append(output)
                    return True
        return False

    def complete_command(self, cmd_id: str, exit_code: int) -> bool:
        with self._lock:
            for cmd in self._commands:
                if cmd.id == cmd_id:
                    cmd.exit_code = exit_code
                    cmd.completed_at = time.time()
                    return True
        return False

    def get_command(self, cmd_id: str) -> Optional[TerminalCommand]:
        with self._lock:
            for cmd in self._commands:
                if cmd.id == cmd_id:
                    return cmd
        return None

    def get_all_commands(self) -> List[TerminalCommand]:
        with self._lock:
            return list(self._commands)

    def get_latest_output(self) -> Optional[TerminalOutput]:
        """Derniere sortie pour le bouton jump-to-latest."""
        with self._lock:
            if not self._commands:
                return None
            last_cmd = self._commands[-1]
            if not last_cmd.outputs:
                return None
            return last_cmd.outputs[-1]
