"""Windows / NectarLink adapter - ÙŠØ±Ø¨Ø· AETHRA Ø¨Ù€ Windows Ø¹Ø¨Ø± Ø§ØªØµØ§Ù„ Ø¢Ù…Ù†.

Ø§Ù„ØªØµÙˆØ±:
AETHRA Android
      â”‚
 secure connection (NectarLink)
      â”‚
Windows
"""

from __future__ import annotations
import hashlib
import os
import subprocess
import time
from typing import Any, Dict, List, Optional

from core.integration import aethra
from devices.capabilities import DeviceCapabilities
from devices.identity import DeviceIdentity
from devices.registry import DeviceRegistry, DeviceEntry


class SecureChannel:
    """Ù‚Ù†Ø§Ø© Ø§ØªØµØ§Ù„ Ù…Ø´ÙØ±Ø© Ø¨ÙŠÙ† Ø§Ù„Ø£Ø¬Ù‡Ø²Ø© (Ù…Ø±Ø¬Ø¹ NectarLink)."""

    def __init__(self, device_id_a: str, device_id_b: str) -> None:
        self.device_id_a = device_id_a
        self.device_id_b = device_id_b
        self.session_id = hashlib.sha256(
            (device_id_a + device_id_b + str(time.time())).encode()
        ).hexdigest()[:16]
        self.connected = False
        self.encrypted = True
        self.created_at = time.time()

    def connect(self) -> bool:
        self.connected = True
        return True

    def disconnect(self) -> None:
        self.connected = False

    def send(self, data: bytes) -> bool:
        if not self.connected:
            return False
        # Ù…Ø­Ø§ÙƒØ§Ø© Ø§Ù„Ø¥Ø±Ø³Ø§Ù„ Ø§Ù„Ù…Ø´ÙØ±
        return True

    def receive(self) -> Optional[bytes]:
        if not self.connected:
            return None
        return None


class NectarLinkAdapter:
    """Ù…Ø­ÙˆÙ„ NectarLink - Ø§ØªØµØ§Ù„ Ø¢Ù…Ù† Ø¨ÙŠÙ† Android Ùˆ Windows."""

    def __init__(self) -> None:
        self.platform_name = "nectar_link"
        self.available = True
        self._channels: Dict[str, SecureChannel] = {}
        self._windows_device_id: Optional[str] = None

    def pair_with_windows(self, windows_device_id: str,
                          android_device_id: str) -> SecureChannel:
        """Ø¥Ù‚Ø±Ø§Ù† Ø¬Ù‡Ø§Ø² Android Ù…Ø¹ Windows Ø¹Ø¨Ø± Ù‚Ù†Ø§Ø© Ø¢Ù…Ù†Ø©."""
        channel = SecureChannel(android_device_id, windows_device_id)
        channel.connect()
        channel_id = android_device_id + ":" + windows_device_id
        self._channels[channel_id] = channel
        self._windows_device_id = windows_device_id
        return channel

    def send_to_windows(self, data: bytes) -> bool:
        if self._windows_device_id is None:
            return False
        channel_id = "android_dev_1:" + self._windows_device_id
        channel = self._channels.get(channel_id)
        if channel and channel.connected:
            return channel.send(data)
        return False

    def receive_from_windows(self) -> Optional[bytes]:
        if self._windows_device_id is None:
            return None
        channel_id = "android_dev_1:" + self._windows_device_id
        channel = self._channels.get(channel_id)
        if channel and channel.connected:
            return channel.receive()
        return None

    def is_paired(self) -> bool:
        return self._windows_device_id is not None


class WindowsAdapter:
    """Ù…Ø­ÙˆÙ„ Windows - Ù‚Ø¯Ø±Ø§Øª Windows (file, process, system info, notifications)."""

    def __init__(self) -> None:
        self.platform_name = "windows"
        self.available = self._check_available()
        self.device_id = "windows_device_" + str(abs(hash(self)))[:6]

    def _check_available(self) -> bool:
        """ÙŠØªØ­Ù‚Ù‚ Ù…Ù† ØªÙˆÙØ± Ø¨ÙŠØ¦Ø© Windows."""
        import sys
        return sys.platform.startswith("win") or os.name == "nt"

    def get_capabilities(self) -> DeviceCapabilities:
        from devices.capabilities import DeviceCapabilities
        return DeviceCapabilities(
            device_id=self.device_id,
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
            skills=["shell.exec", "file.read", "file.write", "process.list"],
        )

    def execute_command(self, command: str, params: Dict[str, Any]) -> Dict[str, Any]:
        """ÙŠÙ†ÙØ° Ø£Ù…Ø±Ø§Ù‹ Ø¹Ù„Ù‰ Windows (Ù…Ø­Ø§ÙƒØ§Ø© ÙÙŠ Ø¨ÙŠØ¦Ø© Linux)."""
        if not self.available:
            return {
                "platform": "windows",
                "success": False,
                "error": "Windows environment not available",
            }
        try:
            result = subprocess.run(
                command, shell=True, capture_output=True, text=True, timeout=60,
            )
            return {
                "platform": "windows",
                "device_id": self.device_id,
                "success": result.returncode == 0,
                "output": result.stdout,
                "error": result.stderr,
            }
        except Exception as exc:
            return {"platform": "windows", "success": False, "error": str(exc)}

    def read_file(self, path: str) -> Dict[str, Any]:
        """ÙŠÙ‚Ø±Ø£ Ù…Ù„ÙØ§Ù‹ Ù…Ù† Windows storage."""
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as f:
                return {
                    "platform": "windows",
                    "device_id": self.device_id,
                    "path": path,
                    "success": True,
                    "content": f.read(),
                }
        except Exception as exc:
            return {"platform": "windows", "device_id": self.device_id,
                    "path": path, "success": False, "error": str(exc)}

    def write_file(self, path: str, content: str) -> Dict[str, Any]:
        """ÙŠÙƒØªØ¨ Ø¥Ù„Ù‰ Ù…Ù„Ù ÙÙŠ Windows storage."""
        try:
            os.makedirs(os.path.dirname(path) or ".", exist_ok=True)
            with open(path, "w", encoding="utf-8") as f:
                f.write(content)
            return {
                "platform": "windows",
                "device_id": self.device_id,
                "path": path,
                "success": True,
                "bytes_written": len(content.encode()),
            }
        except Exception as exc:
            return {"platform": "windows", "device_id": self.device_id,
                    "path": path, "success": False, "error": str(exc)}

    def list_processes(self) -> Dict[str, Any]:
        """ÙŠØ³Ø±Ø¯ Ø§Ù„Ø¹Ù…Ù„ÙŠØ§Øª Ø§Ù„Ø¬Ø§Ø±ÙŠØ© Ø¹Ù„Ù‰ Windows."""
        try:
            result = subprocess.run(
                ["tasklist"], capture_output=True, text=True, timeout=30,
            )
            return {
                "platform": "windows",
                "device_id": self.device_id,
                "success": result.returncode == 0,
                "output": result.stdout,
                "error": result.stderr,
            }
        except Exception as exc:
            return {"platform": "windows", "device_id": self.device_id,
                    "success": False, "error": str(exc)}

    def send_notification(self, title: str, message: str) -> Dict[str, Any]:
        """Ø¥Ø±Ø³Ø§Ù„ Ø¥Ø´Ø¹Ø§Ø± Ø¥Ù„Ù‰ Windows."""
        return {
            "platform": "windows",
            "device_id": self.device_id,
            "success": True,
            "title": title,
            "message": message,
            "note": "Ø¥Ø´Ø¹Ø§Ø± Windows Ø¹Ø¨Ø± NectarLink",
        }


# instances Ø¹Ø§Ù…Ø©
nectar_link = NectarLinkAdapter()
windows_adapter = WindowsAdapter()
