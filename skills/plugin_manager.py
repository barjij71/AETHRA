"""Ù†Ø¸Ø§Ù… Ø§Ù„Ø¥Ø¶Ø§ÙØ§Øª ÙˆØ§Ù„ØªÙˆØ³Ø¹Ø§Øª (Plugin/Extension architecture).

ÙŠØ³Ù…Ø­ Ø¨Ø¥Ø¶Ø§ÙØ© Ù‚Ø¯Ø±Ø§Øª Ø¯ÙˆÙ† Ø¥Ø¹Ø§Ø¯Ø© Ø¨Ù†Ø§Ø¡ Core Ø¨Ø§Ù„ÙƒØ§Ù…Ù„.
ÙŠØ¯Ø¹Ù…: community skills, private skills, provider adapters, OSINT tools, Linux tools, Windows tools.
"""

from __future__ import annotations
import enum
import importlib
import threading
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class PluginState(enum.Enum):
    DISCOVERED = "DISCOVERED"
    VALIDATING = "VALIDATING"
    VALID = "VALID"
    INSTALLED = "INSTALLED"
    ENABLED = "ENABLED"
    RUNNING = "RUNNING"
    ERROR = "ERROR"
    DISABLED = "DISABLED"


class PluginType(enum.Enum):
    SKILL = "skill"
    TOOL = "tool"
    PROVIDER = "provider"
    ADAPTER = "adapter"
    OSINT = "osint"
    LINUX_TOOL = "linux_tool"
    WINDOWS_TOOL = "windows_tool"


@dataclass
class PluginManifest:
    """Manifesto Ø§Ù„Ø¥Ø¶Ø§ÙØ©."""
    id: str
    name: str
    version: str
    description: str
    author: str
    plugin_type: PluginType
    capabilities: List[str] = field(default_factory=list)
    dependencies: List[str] = field(default_factory=list)
    entrypoint: str = ""
    permissions: List[str] = field(default_factory=list)
    min_aethra_version: str = "0.1.0"
    source_url: Optional[str] = None
    license: Optional[str] = None


@dataclass
class Plugin:
    """Ø¥Ø¶Ø§ÙØ© ÙÙŠ Ù†Ø¸Ø§Ù… AETHRA."""
    manifest: PluginManifest
    state: PluginState = PluginState.DISCOVERED
    instance: Optional[Any] = None
    error: Optional[str] = None
    installed_at: Optional[float] = None
    enabled_at: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def transition(self, new_state: PluginState) -> None:
        self.state = new_state


class PluginHook:
    """Ù†Ù‚Ø·Ø© Ø±Ø¨Ø· (hook) Ù„Ù„Ø¥Ø¶Ø§ÙØ§Øª."""
    def __init__(self, name: str) -> None:
        self.name = name
        self._handlers: List[Callable[[Any], Any]] = []
        self._lock = threading.RLock()

    def register(self, handler: Callable[[Any], Any]) -> None:
        with self._lock:
            self._handlers.append(handler)

    def unregister(self, handler: Callable[[Any], Any]) -> None:
        with self._lock:
            self._handlers = [h for h in self._handlers if h != handler]

    def emit(self, data: Any) -> List[Any]:
        results = []
        with self._lock:
            handlers = list(self._handlers)
        for handler in handlers:
            try:
                results.append(handler(data))
            except Exception:  # noqa: BLE001
                pass
        return results


class PluginManager:
    """Ù…Ø¯ÙŠØ± Ø§Ù„Ø¥Ø¶Ø§ÙØ§Øª ÙÙŠ AETHRA."""

    def __init__(self) -> None:
        self._plugins: Dict[str, Plugin] = {}
        self._hooks: Dict[str, PluginHook] = {}
        self._lock = threading.RLock()
        self._installer: Optional[Callable[[Plugin], bool]] = None

    def register(self, plugin: Plugin) -> None:
        with self._lock:
            self._plugins[plugin.manifest.id] = plugin

    def get(self, plugin_id: str) -> Optional[Plugin]:
        with self._lock:
            return self._plugins.get(plugin_id)

    def list(self) -> List[Plugin]:
        with self._lock:
            return list(self._plugins.values())

    def list_by_type(self, plugin_type: PluginType) -> List[Plugin]:
        with self._lock:
            return [p for p in self._plugins.values()
                    if p.manifest.plugin_type == plugin_type]

    def list_by_state(self, state: PluginState) -> List[Plugin]:
        with self._lock:
            return [p for p in self._plugins.values() if p.state == state]

    def create_hook(self, name: str) -> PluginHook:
        with self._lock:
            if name not in self._hooks:
                self._hooks[name] = PluginHook(name)
            return self._hooks[name]

    def get_hook(self, name: str) -> Optional[PluginHook]:
        with self._lock:
            return self._hooks.get(name)

    def emit_hook(self, name: str, data: Any) -> List[Any]:
        hook = self.get_hook(name)
        if hook is None:
            return []
        return hook.emit(data)

    def set_installer(self, installer: Callable[[Plugin], bool]) -> None:
        self._installer = installer

    def install(self, plugin: Plugin) -> bool:
        if self._installer is None:
            plugin.error = "Ù„Ø§ ÙŠÙˆØ¬Ø¯ Ù…Ø«Ø¨Øª Ù…Ø³Ø¬Ù„"
            plugin.transition(PluginState.ERROR)
            return False

        plugin.transition(PluginState.VALIDATING)
        try:
            success = self._installer(plugin)
        except Exception as exc:  # noqa: BLE001
            plugin.transition(PluginState.ERROR)
            plugin.error = str(exc)
            return False

        if success:
            plugin.transition(PluginState.INSTALLED)
            plugin.installed_at = time.time()
        else:
            plugin.transition(PluginState.ERROR)
        return success

    def enable(self, plugin_id: str) -> bool:
        with self._lock:
            plugin = self._plugins.get(plugin_id)
            if plugin is None:
                return False
            if plugin.state != PluginState.INSTALLED:
                return False
            plugin.transition(PluginState.ENABLED)
            plugin.enabled_at = time.time()
        return True

    def run(self, plugin_id: str, data: Any = None) -> Any:
        with self._lock:
            plugin = self._plugins.get(plugin_id)
            if plugin is None:
                return None
            if plugin.state != PluginState.ENABLED:
                return None
            plugin.transition(PluginState.RUNNING)

        result = self.emit_hook("plugin:" + plugin_id, data)

        with self._lock:
            plugin.transition(PluginState.ENABLED)
        return result

    def remove(self, plugin_id: str) -> bool:
        with self._lock:
            if plugin_id in self._plugins:
                del self._plugins[plugin_id]
                return True
            return False

    def get_stats(self) -> Dict[str, Any]:
        with self._lock:
            by_state = {}
            for p in self._plugins.values():
                by_state[p.state.value] = by_state.get(p.state.value, 0) + 1
            by_type = {}
            for p in self._plugins.values():
                by_type[p.manifest.plugin_type.value] = by_type.get(p.manifest.plugin_type.value, 0) + 1
            return {
                "total": len(self._plugins),
                "by_state": by_state,
                "by_type": by_type,
                "hooks": len(self._hooks),
            }


# instance Ø¹Ø§Ù…Ø©
plugin_manager = PluginManager()
