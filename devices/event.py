"""DeviceEvent - evenements du Device Mesh."""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict, Optional
import time


@dataclass
class DeviceEvent:
    """Un evenement dans le mesh (connexion, commande, erreur, etc.)."""
    event_id: str
    device_id: str
    event_type: str  # connect, disconnect, command_start, command_end, error, etc.
    payload: Any = None
    timestamp: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)
