"""DeviceIdentity - identite d'un appareil dans le mesh."""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Optional


@dataclass
class DeviceIdentity:
    device_id: str
    name: str
    platform: str  # android, windows, linux
    public_key: Optional[str] = None  # pour le pairing/chiffrement
    paired_at: Optional[float] = None
    is_paired: bool = False
    metadata: dict = field(default_factory=dict)

    def pair(self, public_key: str) -> None:
        self.public_key = public_key
        self.is_paired = True
        import time
        self.paired_at = time.time()
