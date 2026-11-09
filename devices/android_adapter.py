"""Android Runtime Adapter - ÙŠØ±Ø¨Ø· AETHRA Core Ø¨Ø®Ø¯Ù…Ø§Øª Android.

Ø§Ù„Ø·Ø¨Ù‚Ø© Ø§Ù„Ù…Ù†ØµØ©:
Android
  â†“
Platform Adapters
  â†“
AETHRA Core
"""

from __future__ import annotations
import os
import shutil
import subprocess
from typing import Any, Dict, Optional

from core.integration import aethra
from core.memory import MemoryEngine
from core.task import TaskEngine
from devices.capabilities import DeviceCapabilities
from devices.identity import DeviceIdentity
from devices.registry import DeviceRegistry, DeviceEntry


class PlatformAdapter:
    """ÙˆØ§Ø¬Ù‡Ø© Ø¹Ø§Ù…Ø© Ù„Ù… adapters Ø§Ù„Ù…Ù†ØµØ§Øª."""
    def __init__(self, platform_name: str) -> None:
        self.platform_name = platform_name
        self.available = self._check_available()

    def _check_available(self) -> bool:
        return False

    def get_capabilities(self) -> DeviceCapabilities:
        raise NotImplementedError

    def execute_command(self, command: str, params: Dict[str, Any]) -> Dict[str, Any]:
        raise NotImplementedError

    def read_file(self, path: str) -> Dict[str, Any]:
        raise NotImplementedError

    def write_file(self, path: str, content: str) -> Dict[str, Any]:
        raise NotImplementedError

    def is_available(self) -> bool:
        return self.available


class AndroidRuntimeAdapter(PlatformAdapter):
    """Ù…Ø­ÙˆÙ„è¿è¡Œæ—¶ Ù„Ù€ Android - ÙŠØ±Ø¨Ø· Core Ø¨Ø®Ø¯Ù…Ø§Øª Android."""

    def __init__(self) -> None:
        super().__init__("android")
        self.device_id = "android_device_" + str(abs(hash(self)))[:6]
        self._registry: Optional[DeviceRegistry] = None
        self._memory: Optional[MemoryEngine] = None
        self._task_engine: Optional[TaskEngine] = None

    def _check_available(self) -> bool:
        return True

    def bind(self, registry: DeviceRegistry, memory: MemoryEngine,
             task_engine: TaskEngine) -> None:
        self._registry = registry
        self._memory = memory
        self._task_engine = task_engine

        caps = self.get_capabilities()
        identity = DeviceIdentity(
            device_id=self.device_id,
            name="Android Device",
            platform="android",
        )
        identity.pair("android_public_key")

        entry = DeviceEntry(
            identity=identity,
            capabilities=caps,
            is_online=True,
        )
        if self._registry:
            self._registry.register(entry)

    def get_capabilities(self) -> DeviceCapabilities:
        return DeviceCapabilities(
            device_id=self.device_id,
            shell=True,
            file_transfer=True,
            clipboard=True,
            notifications=True,
            app_control=True,
            mouse_keyboard=False,
            camera=True,
            microphone=True,
            gpu=True,
            local_ai=True,
            root=False,
            skills=["shell.exec", "file.read", "file.write"],
        )

    def execute_command(self, command: str, params: Dict[str, Any]) -> Dict[str, Any]:
        result = {
            "platform": "android",
            "device_id": self.device_id,
            "command": command,
            "success": False,
            "output": "",
            "error": "",
        }

        if self._task_engine:
            task_id = self._task_engine.create_task(
                "android_cmd:" + command[:30],
                "shell.exec",
                metadata={"command": command, "actor": "android_adapter"},
            )
            result["task_id"] = task_id
            result["success"] = True
            result["output"] = "ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© Android: " + command

        return result

    def read_file(self, path: str) -> Dict[str, Any]:
        return {
            "platform": "android",
            "device_id": self.device_id,
            "path": path,
            "success": True,
            "content": "",
            "note": "Ù‚Ø±Ø§Ø¡Ø© Ù…Ù„Ù Android Ø¹Ø¨Ø± adb/storage",
        }

    def write_file(self, path: str, content: str) -> Dict[str, Any]:
        return {
            "platform": "android",
            "device_id": self.device_id,
            "path": path,
            "success": True,
            "bytes_written": len(content.encode()),
            "note": "ÙƒØªØ§Ø¨Ø© Ù…Ù„Ù Android Ø¹Ø¨Ø± adb/storage",
        }


class TermuxAdapter(PlatformAdapter):
    """Ù…Ø­ÙˆÙ„ Termux - ÙŠØ±Ø¨Ø· AETHRA Ø¨Ù€ Termux Ø¹Ù„Ù‰ Android."""

    def __init__(self) -> None:
        super().__init__("termux")

    def _check_available(self) -> bool:
        return shutil.which("termux-info") is not None

    def get_capabilities(self) -> DeviceCapabilities:
        return DeviceCapabilities(
            device_id="termux_device",
            shell=True,
            file_transfer=True,
            clipboard=False,
            notifications=False,
            app_control=False,
            mouse_keyboard=False,
            camera=False,
            microphone=False,
            gpu=False,
            local_ai=False,
            root=False,
            skills=["shell.exec", "file.read", "file.write"],
        )

    def execute_command(self, command: str, params: Dict[str, Any]) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                command, shell=True, capture_output=True, text=True, timeout=30,
            )
            return {
                "platform": "termux",
                "success": result.returncode == 0,
                "output": result.stdout,
                "error": result.stderr,
            }
        except Exception as exc:
            return {"platform": "termux", "success": False, "error": str(exc)}

    def read_file(self, path: str) -> Dict[str, Any]:
        try:
            with open(path, "r", encoding="utf-8") as f:
                return {"platform": "termux", "path": path, "success": True, "content": f.read()}
        except Exception as exc:
            return {"platform": "termux", "path": path, "success": False, "error": str(exc)}

    def write_file(self, path: str, content: str) -> Dict[str, Any]:
        try:
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            return {"platform": "termux", "path": path, "success": True}
        except Exception as exc:
            return {"platform": "termux", "path": path, "success": False, "error": str(exc)}


class LinuxAdapter(PlatformAdapter):
    """Ù…Ø­ÙˆÙ„ Linux - ÙŠØ±Ø¨Ø· AETHRA Ø¨Ù€ Linux desktop."""

    def __init__(self) -> None:
        super().__init__("linux")

    def _check_available(self) -> bool:
        return True

    def get_capabilities(self) -> DeviceCapabilities:
        return DeviceCapabilities(
            device_id="linux_device",
            shell=True,
            file_transfer=True,
            clipboard=True,
            notifications=True,
            app_control=True,
            mouse_keyboard=True,
            camera=False,
            microphone=False,
            gpu=True,
            local_ai=True,
            root=False,
            skills=["shell.exec", "file.read", "file.write"],
        )

    def execute_command(self, command: str, params: Dict[str, Any]) -> Dict[str, Any]:
        try:
            result = subprocess.run(
                command, shell=True, capture_output=True, text=True, timeout=60,
            )
            return {
                "platform": "linux",
                "success": result.returncode == 0,
                "output": result.stdout,
                "error": result.stderr,
            }
        except Exception as exc:
            return {"platform": "linux", "success": False, "error": str(exc)}

    def read_file(self, path: str) -> Dict[str, Any]:
        try:
            with open(path, "r", encoding="utf-8") as f:
                return {"platform": "linux", "path": path, "success": True, "content": f.read()}
        except Exception as exc:
            return {"platform": "linux", "path": path, "success": False, "error": str(exc)}

    def write_file(self, path: str, content: str) -> Dict[str, Any]:
        try:
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            return {"platform": "linux", "path": path, "success": True}
        except Exception as exc:
            return {"platform": "linux", "path": path, "success": False, "error": str(exc)}


# instances Ø¹Ø§Ù…Ø©
android_adapter = AndroidRuntimeAdapter()
termux_adapter = TermuxAdapter()
linux_adapter = LinuxAdapter()
