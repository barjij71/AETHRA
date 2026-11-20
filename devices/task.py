"""DeviceTask - tache a executer sur un appareil distant."""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional
import time
import uuid


@dataclass
class DeviceTask:
    """Une tache a executer sur un appareil du mesh."""
    task_id: str
    device_id: str
    command_type: str
    payload: Any
    status: str = "QUEUED"  # QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED
    created_at: float = field(default_factory=time.time)
    started_at: Optional[float] = None
    completed_at: Optional[float] = None
    result: Any = None
    error: Optional[str] = None
    dependencies: List[str] = field(default_factory=list)
    metadata: Dict[str, Any] = field(default_factory=dict)

    @classmethod
    def create(cls, device_id: str, command_type: str, payload: Any,
               dependencies: Optional[List[str]] = None) -> "DeviceTask":
        return cls(
            task_id=str(uuid.uuid4()),
            device_id=device_id,
            command_type=command_type,
            payload=payload,
            dependencies=dependencies or [],
        )
