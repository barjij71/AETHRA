"""MemoryEngine - memoires couchees de AETHRA.

Couches : Conversation, Working, Task, Long-term, Preference, Device, Skill, Knowledge.
Chaque enregistrement a : source, relevance, timestamp, confidence.
"""

from __future__ import annotations

import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


@dataclass
class MemoryRecord:
    """Un enregistrement en memoire."""

    id: str
    layer: str
    key: str
    value: Any
    source: str
    relevance: float = 1.0
    confidence: float = 1.0
    timestamp: float = field(default_factory=time.time)
    ttl_seconds: Optional[int] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def is_expired(self) -> bool:
        if self.ttl_seconds is None:
            return False
        return time.time() - self.timestamp > self.ttl_seconds


class MemoryEngine:
    """Moteur de memoire avec couches et politiques de suppression/audit."""

    def __init__(self) -> None:
        self._records: Dict[str, MemoryRecord] = {}
        self._layer_index: Dict[str, Dict[str, str]] = {}  # layer -> key -> record_id
        self._lock = threading.RLock()

    def write(
        self,
        layer: str,
        key: str,
        value: Any,
        source: str,
        relevance: float = 1.0,
        confidence: float = 1.0,
        ttl_seconds: Optional[int] = None,
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        record_id = str(uuid.uuid4())
        record = MemoryRecord(
            id=record_id,
            layer=layer,
            key=key,
            value=value,
            source=source,
            relevance=relevance,
            confidence=confidence,
            ttl_seconds=ttl_seconds,
            metadata=metadata or {},
        )
        with self._lock:
            self._records[record_id] = record
            self._layer_index.setdefault(layer, {})[key] = record_id
        return record_id

    def read(self, layer: str, key: str) -> Optional[Any]:
        with self._lock:
            record_id = self._layer_index.get(layer, {}).get(key)
            if record_id is None:
                return None
            record = self._records.get(record_id)
            if record is None or record.is_expired():
                return None
            return record.value

    def read_record(self, layer: str, key: str) -> Optional[MemoryRecord]:
        with self._lock:
            record_id = self._layer_index.get(layer, {}).get(key)
            if record_id is None:
                return None
            record = self._records.get(record_id)
            if record is None or record.is_expired():
                return None
            return record

    def list_layer(self, layer: str) -> List[MemoryRecord]:
        with self._lock:
            ids = self._layer_index.get(layer, {}).values()
            return [self._records[rid] for rid in ids if rid in self._records and not self._records[rid].is_expired()]

    def delete(self, layer: str, key: str) -> bool:
        with self._lock:
            record_id = self._layer_index.get(layer, {}).pop(key, None)
            if record_id:
                self._records.pop(record_id, None)
                return True
            return False

    def clear_layer(self, layer: str) -> int:
        with self._lock:
            keys = list(self._layer_index.get(layer, {}).keys())
            for key in keys:
                self.delete(layer, key)
            return len(keys)
