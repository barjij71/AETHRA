"""Ù†Ø¸Ø§Ù… Ø§Ù„ØªØ­Ù„Ù„ Ø¹Ù†Ø¯ Ø§Ù†Ù‚Ø·Ø§Ø¹ Ø§Ù„Ø¥Ù†ØªØ±Ù†Øª (Offline degradation).

Ø¹Ù†Ø¯ Ø§Ù†Ù‚Ø·Ø§Ø¹ Ø§Ù„Ø¥Ù†ØªØ±Ù†Øª:
Cloud unavailable â†’ Local provider â†’ Local tools â†’ Local memory â†’ Continue where possible.
Ø¨Ø¯Ù„Ø§Ù‹ Ù…Ù† Ø£Ù† ØªØµØ¨Ø­ AETHRA Ø¨Ø§Ù„ÙƒØ§Ù…Ù„ ØºÙŠØ± Ù‚Ø§Ø¨Ù„Ø© Ù„Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù….
"""

from __future__ import annotations
import enum
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class ConnectivityStatus(enum.Enum):
    ONLINE = "ONLINE"
    OFFLINE = "OFFLINE"
    DEGRADED = "DEGRADED"


@dataclass
class OfflineCapability:
    """Ù‚Ø¯Ø±Ø© Ù…ØªØ§Ø­Ø© Ø¨Ø¯ÙˆÙ† Ø§ØªØµØ§Ù„."""
    id: str
    name: str
    description: str
    available_offline: bool = True
    requires_cloud: bool = False


@dataclass
class OfflineModeConfig:
    """Ø¥Ø¹Ø¯Ø§Ø¯Ø§Øª ÙˆØ¶Ø¹ Ø¹Ø¯Ù… Ø§Ù„Ø§ØªØµØ§Ù„."""
    enable_local_models: bool = True
    enable_local_tools: bool = True
    enable_local_memory: bool = True
    max_local_tasks: int = 5
    cache_results: bool = True


class OfflineManager:
    """Ù…Ø¯ÙŠØ± ÙˆØ¶Ø¹ Ø¹Ø¯Ù… Ø§Ù„Ø§ØªØµØ§Ù„ ÙÙŠ AETHRA."""

    def __init__(self) -> None:
        self.status = ConnectivityStatus.ONLINE
        self.config = OfflineModeConfig()
        self._local_providers: Dict[str, Any] = {}
        self._local_tools: Dict[str, Any] = {}
        self._cached_results: Dict[str, Any] = {}
        self._lock = __import__("threading").RLock()
        self._callbacks: List[Callable[[ConnectivityStatus], None]] = []

    def set_status(self, status: ConnectivityStatus) -> None:
        """ØªØ¹ÙŠÙŠÙ† Ø­Ø§Ù„Ø© Ø§Ù„Ø§ØªØµØ§Ù„."""
        old_status = self.status
        self.status = status
        if old_status != status:
            with self._lock:
                callbacks = list(self._callbacks)
            for cb in callbacks:
                try:
                    cb(status)
                except Exception:  # noqa: BLE001
                    pass

    def is_online(self) -> bool:
        return self.status == ConnectivityStatus.ONLINE

    def is_offline(self) -> bool:
        return self.status == ConnectivityStatus.OFFLINE

    def is_degraded(self) -> bool:
        return self.status == ConnectivityStatus.DEGRADED

    def register_local_provider(self, provider_id: str, provider: Any) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ù…Ø²ÙˆØ¯ Ù…Ø­Ù„ÙŠ Ù„Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù… Ø¹Ù†Ø¯ Ø§Ù†Ù‚Ø·Ø§Ø¹ Ø§Ù„Ø¥Ù†ØªØ±Ù†Øª."""
        with self._lock:
            self._local_providers[provider_id] = provider

    def get_local_provider(self, provider_id: str) -> Optional[Any]:
        with self._lock:
            return self._local_providers.get(provider_id)

    def list_local_providers(self) -> List[str]:
        with self._lock:
            return list(self._local_providers.keys())

    def register_local_tool(self, tool_id: str, tool: Any) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ø£Ø¯Ø§Ø© Ù…Ø­Ù„ÙŠØ© Ù„Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù… Ø¹Ù†Ø¯ Ø§Ù†Ù‚Ø·Ø§Ø¹ Ø§Ù„Ø¥Ù†ØªØ±Ù†Øª."""
        with self._lock:
            self._local_tools[tool_id] = tool

    def get_local_tool(self, tool_id: str) -> Optional[Any]:
        with self._lock:
            return self._local_tools.get(tool_id)

    def list_local_tools(self) -> List[str]:
        with self._lock:
            return list(self._local_tools.keys())

    def cache_result(self, key: str, value: Any) -> None:
        """ØªØ®Ø²ÙŠÙ† Ù†ØªÙŠØ¬Ø© ÙÙŠ Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ø¤Ù‚ØªØ© Ù„Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù… Ø¨Ø¯ÙˆÙ† Ø§ØªØµØ§Ù„."""
        if self.config.cache_results:
            with self._lock:
                self._cached_results[key] = {
                    "value": value,
                    "cached_at": time.time(),
                }

    def get_cached_result(self, key: str) -> Optional[Any]:
        """Ø¬Ù„Ø¨ Ù†ØªÙŠØ¬Ø© Ù…Ù† Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ø¤Ù‚ØªØ©."""
        with self._lock:
            entry = self._cached_results.get(key)
            if entry:
                return entry["value"]
        return None

    def has_cached_result(self, key: str) -> bool:
        with self._lock:
            return key in self._cached_results

    def register_callback(self, callback: Callable[[ConnectivityStatus], None]) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ø¯Ø§Ù„Ø© Ø§Ø³ØªØ¯Ø¹Ø§Ø¡ Ø¹Ù†Ø¯ ØªØºÙŠÙŠØ± Ø­Ø§Ù„Ø© Ø§Ù„Ø§ØªØµØ§Ù„."""
        self._callbacks.append(callback)

    def get_available_capabilities(self) -> List[OfflineCapability]:
        """Ø¥Ø±Ø¬Ø§Ø¹ Ø§Ù„Ù‚Ø¯Ø±Ø§Øª Ø§Ù„Ù…ØªØ§Ø­Ø© ÙÙŠ ÙˆØ¶Ø¹ Ø¹Ø¯Ù… Ø§Ù„Ø§ØªØµØ§Ù„."""
        capabilities = []
        if self.config.enable_local_tools:
            capabilities.append(OfflineCapability(
                id="local_tools",
                name="Ø£Ø¯ÙˆØ§Øª Ù…Ø­Ù„ÙŠØ©",
                description="ØªÙ†ÙÙŠØ° Ø£Ø¯ÙˆØ§Øª Ù…Ø­Ù„ÙŠØ© Ø¨Ø¯ÙˆÙ† Ø§ØªØµØ§Ù„",
                available_offline=True,
                requires_cloud=False,
            ))
        if self.config.enable_local_memory:
            capabilities.append(OfflineCapability(
                id="local_memory",
                name="Ø°Ø§ÙƒØ±Ø© Ù…Ø­Ù„ÙŠØ©",
                description="Ù‚Ø±Ø§Ø¡Ø© ÙˆÙƒØªØ§Ø¨Ø© Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ø­Ù„ÙŠØ©",
                available_offline=True,
                requires_cloud=False,
            ))
        if self.config.enable_local_models:
            capabilities.append(OfflineCapability(
                id="local_models",
                name="Ù†Ù…Ø§Ø°Ø¬ Ù…Ø­Ù„ÙŠØ©",
                description="Ø§Ø³ØªØ®Ø¯Ø§Ù… Ù†Ù…Ø§Ø°Ø¬ Ù…Ø­Ù„ÙŠØ© (OllamaØŒ Ø¥Ù„Ø®)",
                available_offline=True,
                requires_cloud=False,
            ))
        return capabilities

    def can_execute_offline(self, tool_id: str) -> bool:
        """Ù‡Ù„ ÙŠÙ…ÙƒÙ† ØªÙ†ÙÙŠØ° Ø£Ø¯Ø§Ø© Ø¨Ø¯ÙˆÙ† Ø§ØªØµØ§Ù„ØŸ"""
        if self.is_online():
            return True
        return self.get_local_tool(tool_id) is not None

    def get_status_summary(self) -> Dict[str, Any]:
        with self._lock:
            return {
                "status": self.status.value,
                "local_providers": len(self._local_providers),
                "local_tools": len(self._local_tools),
                "cached_results": len(self._cached_results),
                "max_local_tasks": self.config.max_local_tasks,
                "available_capabilities": len(self.get_available_capabilities()),
            }


# instance Ø¹Ø§Ù…Ø©
offline_manager = OfflineManager()
