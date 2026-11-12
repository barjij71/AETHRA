"""DeviceCommand - commande executee sur un appareil distant."""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict, Optional
import time


@dataclass
class DeviceCommand:
    """Une commande a executer sur un appareil."""
    command_id: str
    device_id: str
    command_type: str  # shell, file_transfer, clipboard, notification, etc.
    payload: Any
    status: str = "QUEUED"  # QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    created_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    result: Any = None
    error: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def mark_started(self) -> None:
        self.status = "RUNNING"
        self.started_at = time.time()

    def mark_completed(self, result: Any = None) -> None:
        self.status = "COMPLETED"
        self.result = result
        self.completed_at = time.time()

    def mark_failed(self, error: str) -> None:
        self.status = "FAILED"
        self.error = error
        self.completed_at = time.time()
