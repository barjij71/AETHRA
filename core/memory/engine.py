"""Ù…Ø­Ø±Ùƒ Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ø·ÙˆØ± Ù…Ø¹ Ø·Ø¨Ù‚Ø§Øª ÙˆØ¥Ø¯Ø§Ø±Ø© Ø³ÙŠØ§Ø³Ø§Øª Ø§Ù„Ø®ØµÙˆØµÙŠØ© ÙˆØ§Ù„Ø­Ø°Ù."""

from __future__ import annotations
import threading
import time
import uuid
from typing import Any, Dict, List, Optional

from core.memory.layers import MemoryLayer
from core.memory.entry import MemoryEntry


class MemoryEngine:
    """Ù…Ø­Ø±Ùƒ Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ù…Ø¹ Ø·Ø¨Ù‚Ø§Øª Ù…ØªØ¹Ø¯Ø¯Ø© ÙˆØ³ÙŠØ§Ø³Ø§Øª Ø§Ù„Ø®ØµÙˆØµÙŠØ© ÙˆØ§Ù„Ø­Ø°Ù‚."""

    def __init__(self, max_records_per_layer: int = 10000) -> None:
        self._records: Dict[str, MemoryEntry] = {}
        self._layer_index: Dict[str, Dict[str, str]] = {}  # layer -> key -> record_id
        self._max_records_per_layer = max_records_per_layer
        self._lock = threading.RLock()
        self._deletion_log: List[Dict[str, Any]] = []

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
        privacy_level: str = "internal",
    ) -> str:
        """ÙŠÙƒØªØ¨ Ù…Ø¯Ø®Ù„ Ø°Ø§ÙƒØ±Ø© ÙÙŠ Ø·Ø¨Ù‚Ø© Ù…Ø­Ø¯Ø¯Ø©."""
        record_id = str(uuid.uuid4())
        entry = MemoryEntry(
            id=record_id,
            layer=layer,
            key=key,
            value=value,
            source=source,
            relevance=relevance,
            confidence=confidence,
            ttl_seconds=ttl_seconds,
            metadata=metadata or {},
            privacy_level=privacy_level,
        )
        with self._lock:
            # ØªÙ†Ø¸ÙŠÙ Ø§Ù„Ø³Ø¬Ù„Ø§Øª Ø§Ù„Ù…Ù†ØªÙ‡ÙŠØ© Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ© ÙÙŠ Ù†ÙØ³ Ø§Ù„Ø·Ø¨Ù‚Ø© Ù‚Ø¨Ù„ Ø§Ù„Ø¥Ø¶Ø§ÙØ©
            self._enforce_layer_limit(layer)
            self._records[record_id] = entry
            self._layer_index.setdefault(layer, {})[key] = record_id
        return record_id

    def read(self, layer: str, key: str) -> Optional[Any]:
        """ÙŠÙ‚Ø±Ø£ Ù‚ÙŠÙ…Ø© Ù…Ù† Ø·Ø¨Ù‚Ø© Ø°Ø§ÙƒØ±Ø©."""
        with self._lock:
            record_id = self._layer_index.get(layer, {}).get(key)
            if record_id is None:
                return None
            entry = self._records.get(record_id)
            if entry is None or entry.is_expired():
                return None
            return entry.value

    def read_entry(self, layer: str, key: str) -> Optional[MemoryEntry]:
        """ÙŠÙ‚Ø±Ø£ Ø§Ù„Ù…Ø¯Ø®Ù„ Ø§Ù„ÙƒØ§Ù…Ù„."""
        with self._lock:
            record_id = self._layer_index.get(layer, {}).get(key)
            if record_id is None:
                return None
            entry = self._records.get(record_id)
            if entry is None or entry.is_expired():
                return None
            return entry

    def list_layer(self, layer: str, min_relevance: float = 0.0) -> List[MemoryEntry]:
        """ÙŠØ¹ÙŠØ¯ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ù…Ø¯Ø®Ù„Ø§Øª ØºÙŠØ± Ø§Ù„Ù…Ù†ØªÙ‡ÙŠØ© ÙÙŠ Ø·Ø¨Ù‚Ø©."""
        with self._lock:
            ids = self._layer_index.get(layer, {}).values()
            entries = []
            for rid in ids:
                entry = self._records.get(rid)
                if entry and not entry.is_expired() and entry.relevance >= min_relevance:
                    entries.append(entry)
            entries.sort(key=lambda e: e.timestamp, reverse=True)
            return entries

    def search(
        self,
        query: str,
        layers: Optional[List[str]] = None,
        min_confidence: float = 0.0,
        min_relevance: float = 0.0,
        limit: int = 50,
    ) -> List[MemoryEntry]:
        """Ø§Ù„Ø¨Ø­Ø« ÙÙŠ Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø­Ø³Ø¨ Ø§Ù„Ù†Øµ ÙˆØ§Ù„Ù…ØµØ§Ø¯Ø±."""
        with self._lock:
            target_layers = layers or [l.value for l in MemoryLayer]
            results = []
            for layer in target_layers:
                for entry in self.list_layer(layer, min_relevance=min_relevance):
                    if entry.confidence < min_confidence:
                        continue
                    text = str(entry.value) + " " + str(entry.source)
                    if query.lower() in text.lower():
                        results.append(entry)
            results.sort(key=lambda e: (e.confidence, e.relevance, e.timestamp), reverse=True)
            return results[:limit]

    def delete(self, layer: str, key: str, reason: str = "manual") -> bool:
        """ÙŠØ­Ø°Ù Ù…Ø¯Ø®Ù„ Ø°Ø§ÙƒØ±Ø© Ù…Ø¹ ØªØ³Ø¬ÙŠÙ„ ÙÙŠ Ø³Ø¬Ù„ Ø§Ù„Ø­Ø°Ù‚."""
        with self._lock:
            record_id = self._layer_index.get(layer, {}).pop(key, None)
            if record_id:
                entry = self._records.pop(record_id, None)
                self._deletion_log.append({
                    "record_id": record_id,
                    "layer": layer,
                    "key": key,
                    "reason": reason,
                    "timestamp": time.time(),
                })
                return True
            return False

    def clear_layer(self, layer: str, reason: str = "clear_layer") -> int:
        """ÙŠØ­Ø°Ù Ø¬Ù…ÙŠØ¹ Ù…Ø¯Ø®Ù„Ø§Øª Ø·Ø¨Ù‚Ø©."""
        with self._lock:
            keys = list(self._layer_index.get(layer, {}).keys())
            count = 0
            for key in keys:
                if self.delete(layer, key, reason):
                    count += 1
            return count

    def get_deletion_log(self, limit: int = 100) -> List[Dict[str, Any]]:
        """ÙŠØ¹ÙŠØ¯ Ø³Ø¬Ù„ Ø§Ù„Ø­Ø°Ù‚ Ù„Ù„Ù…Ø±Ø§Ø¬Ø¹Ø©."""
        with self._lock:
            return list(self._deletion_log[-limit:])

    def _enforce_layer_limit(self, layer: str) -> None:
        """ÙŠÙØ±Ø¶ Ø§Ù„Ø­Ø¯ Ø§Ù„Ø£Ù‚ØµÙ‰ Ù„Ù„Ø³Ø¬Ù„Ø§Øª ÙÙŠ Ø§Ù„Ø·Ø¨Ù‚Ø©."""
        entries = self.list_layer(layer)
        if len(entries) >= self._max_records_per_layer:
            # Ø­Ø°Ù Ø§Ù„Ø£Ù‚Ø¯Ù… ÙˆØ§Ù„Ø£Ù‚Ù„ Ø£Ù‡Ù…ÙŠØ©
            entries.sort(key=lambda e: (e.relevance, e.timestamp))
            to_remove = len(entries) - self._max_records_per_layer + 1
            for entry in entries[:to_remove]:
                key = entry.key
                record_id = self._layer_index.get(layer, {}).get(key)
                if record_id:
                    self._records.pop(record_id, None)
                self._layer_index.get(layer, {}).pop(key, None)
                self._deletion_log.append({
                    "record_id": entry.id,
                    "layer": layer,
                    "key": key,
                    "reason": "layer_limit_enforced",
                    "timestamp": time.time(),
                })

    def get_stats(self) -> Dict[str, Any]:
        """Ø¥Ø­ØµØ§Ø¦ÙŠØ§Øª Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ù„Ù„Ù…Ø±Ø§Ø¬Ø¹Ø©."""
        with self._lock:
            stats = {}
            for layer in MemoryLayer:
                entries = self.list_layer(layer.value)
                stats[layer.value] = {
                    "count": len(entries),
                    "avg_confidence": sum(e.confidence for e in entries) / len(entries) if entries else 0.0,
                }
            return stats
