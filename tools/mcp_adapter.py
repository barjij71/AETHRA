"""Ù…Ø­ÙˆÙ„ MCP (Model Context Protocol) Ù„Ù€ AETHRA.

MCP ÙŠØ¬Ø¨ Ø£Ù† ÙŠÙƒÙˆÙ† adapter/integration protocol ÙˆÙ„ÙŠØ³ architecture Ø¨Ø¯ÙŠÙ„Ø© Ø¹Ù† AETHRA Core.
ÙŠØ³Ù…Ø­ Ø¨Ø¥Ø¶Ø§ÙØ© Ø£Ø¯ÙˆØ§Øª/ØªÙƒØ§Ù…Ù„Ø§Øª Ø®Ø§Ø±Ø¬ÙŠØ© Ø¨Ø£Ø³Ù„ÙˆØ¨ MCP Ø¶Ù…Ù† Ù…Ù†Ø¸ÙˆÙ…Ø© Skills/Tools.
"""

from __future__ import annotations
import json
import subprocess
import time
import uuid
from typing import Any, Callable, Dict, List, Optional

from core.eventbus import event_bus, Event
from tools.tool import Tool, ToolCategory, ToolRisk, ToolResult


class MCPClient:
    """Ø¹Ù…ÙŠÙ„ MCP Ù„Ù„Ø§ØªØµØ§Ù„ Ø¨Ø®ÙˆØ§Ø¯Ù… MCP Ø®Ø§Ø±Ø¬ÙŠØ©."""

    def __init__(self, server_command: List[str]) -> None:
        self.server_command = server_command
        self._process: Optional[subprocess.Popen] = None
        self._connected = False
        self._request_id = 0
        self._capabilities: List[str] = []

    def connect(self) -> bool:
        """Ø§Ù„Ø§ØªØµØ§Ù„ Ø¨Ø®Ø§Ø¯Ù… MCP."""
        try:
            self._process = subprocess.Popen(
                self.server_command,
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                text=True,
                bufsize=1,
            )
            self._connected = True
            return True
        except Exception:
            self._connected = False
            return False

    def disconnect(self) -> None:
        """ÙØµÙ„ Ø§Ù„Ø§ØªØµØ§Ù„."""
        self._connected = False
        if self._process:
            self._process.terminate()
            self._process = None

    def _send_request(self, method: str, params: Optional[Dict[str, Any]] = None) -> Dict[str, Any]:
        """Ø¥Ø±Ø³Ø§Ù„ Ø·Ù„Ø¨ JSON-RPC Ø¥Ù„Ù‰ Ø®Ø§Ø¯Ù… MCP."""
        if not self._connected or not self._process:
            raise RuntimeError("MCP client not connected")

        self._request_id += 1
        request = {
            "jsonrpc": "2.0",
            "id": self._request_id,
            "method": method,
            "params": params or {},
        }

        # ÙÙŠ Ø¨ÙŠØ¦Ø© Ø§Ù„Ø§Ø®ØªØ¨Ø§Ø±ØŒ Ù†Ø¹ÙŠØ¯ Ø§Ø³ØªØ¬Ø§Ø¨Ø© ÙˆÙ‡Ù…ÙŠØ©
        # ÙÙŠ Ø§Ù„Ø¥Ù†ØªØ§Ø¬ØŒ ÙŠÙØ±Ø³Ù„ Ø¹Ø¨Ø± stdin ÙˆÙŠÙÙ‚Ø±Ø£ Ù…Ù† stdout
        response = {
            "jsonrpc": "2.0",
            "id": self._request_id,
            "result": {"capabilities": self._capabilities},
        }
        return response

    def list_tools(self) -> List[Dict[str, Any]]:
        """Ø¥listing Ø§Ù„Ø£Ø¯ÙˆØ§Øª Ø§Ù„Ù…ØªØ§Ø­Ø© Ù…Ù† Ø®Ø§Ø¯Ù… MCP."""
        try:
            resp = self._send_request("tools/list")
            return resp.get("result", {}).get("tools", [])
        except Exception:
            return []

    def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        """Ø§Ø³ØªØ¯Ø¹Ø§Ø¡ Ø£Ø¯Ø§Ø© Ù…Ù† Ø®Ø§Ø¯Ù… MCP."""
        try:
            resp = self._send_request("tools/call", {"name": tool_name, "arguments": arguments})
            return resp.get("result", {})
        except Exception as exc:
            return {"error": str(exc)}

    def is_connected(self) -> bool:
        return self._connected


class MCPToolWrapper:
    """ØºÙ„Ø§Ù Ù„Ø£Ø¯ÙˆØ§Øª MCP Ù„ÙŠÙƒÙˆÙ† Ù…ØªÙˆØ§ÙÙ‚Ø§Ù‹ Ù…Ø¹ ToolRegistry ÙÙŠ AETHRA."""

    def __init__(self, mcp_client: MCPClient, tool_name: str,
                 tool_info: Dict[str, Any]) -> None:
        self.mcp_client = mcp_client
        self.tool_name = tool_name
        self.tool_info = tool_info

    def to_aethra_tool(self) -> Tool:
        """ØªØ­ÙˆÙŠÙ„ Ø£Ø¯Ø§Ø© MCP Ø¥Ù„Ù‰ Ø£Ø¯Ø§Ø© AETHRA."""
        input_schema = self.tool_info.get("inputSchema", {})
        description = self.tool_info.get("description", self.tool_name)

        # ØªØ­Ø¯ÙŠØ¯ Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ù…Ø®Ø§Ø·Ø±Ø© Ø¨Ù†Ø§Ø¡Ù‹ Ø¹Ù„Ù‰ Ø§Ù„Ø§Ø³Ù…
        risk = ToolRisk.SAFE
        requires_approval = False
        dangerous_keywords = ["delete", "remove", "format", "shutdown", "reboot", "root"]
        for kw in dangerous_keywords:
            if kw in self.tool_name.lower():
                risk = ToolRisk.DESTRUCTIVE
                requires_approval = True
                break

        return Tool(
            id="mcp:" + self.tool_name,
            name=self.tool_name,
            description=description,
            category=ToolCategory.CUSTOM,
            risk=risk,
            capabilities=["mcp.tool"],
            input_schema=input_schema,
            requires_approval=requires_approval,
            timeout_seconds=60,
        )

    def execute(self, params: Dict[str, Any]) -> ToolResult:
        """ØªÙ†ÙÙŠØ° Ø§Ù„Ø£Ø¯Ø§Ø© Ø¹Ø¨Ø± MCP."""
        start = time.time()
        try:
            result = self.mcp_client.call_tool(self.tool_name, params)
            return ToolResult(
                success="error" not in result,
                output=result.get("content", result),
                error=result.get("error"),
                duration_seconds=time.time() - start,
                evidence={"mcp_tool": self.tool_name, "params": params},
            )
        except Exception as exc:  # noqa: BLE001
            return ToolResult(
                success=False,
                error=str(exc),
                duration_seconds=time.time() - start,
                evidence={"mcp_tool": self.tool_name},
            )


class MCPAdapter:
    """Ù…Ø­ÙˆÙ„ MCP Ø¶Ù…Ù† Skill/Tool architecture ÙÙŠ AETHRA.

    ÙŠØ±Ø¨Ø· Ø®ÙˆØ§Ø¯Ù… MCP Ø§Ù„Ø®Ø§Ø±Ø¬ÙŠØ© Ø¨Ù†Ø¸Ø§Ù… Ø£Ø¯ÙˆØ§Øª AETHRA.
    """

    def __init__(self) -> None:
        self._clients: Dict[str, MCPClient] = {}
        self._wrappers: Dict[str, MCPToolWrapper] = {}
        self._lock = __import__("threading").RLock()

    def connect_server(self, server_id: str, command: List[str]) -> bool:
        """Ø§Ù„Ø§ØªØµØ§Ù„ Ø¨Ø®Ø§Ø¯Ù… MCP."""
        client = MCPClient(command)
        if client.connect():
            with self._lock:
                self._clients[server_id] = client
            return True
        return False

    def disconnect_server(self, server_id: str) -> None:
        with self._lock:
            client = self._clients.pop(server_id, None)
            if client:
                client.disconnect()

    def list_server_tools(self, server_id: str) -> List[Dict[str, Any]]:
        with self._lock:
            client = self._clients.get(server_id)
            if client:
                return client.list_tools()
        return []

    def wrap_tool(self, server_id: str, tool_name: str,
                  tool_info: Dict[str, Any]) -> MCPToolWrapper:
        """ØºÙ„Ø§Ù Ø£Ø¯Ø§Ø© MCP ÙƒØ£Ø¯Ø§Ø© AETHRA."""
        with self._lock:
            client = self._clients.get(server_id)
            if client is None:
                raise ValueError("Ø®Ø§Ø¯Ù… MCP ØºÙŠØ± Ù…ØªØµÙ„: " + server_id)
            wrapper = MCPToolWrapper(client, tool_name, tool_info)
            self._wrappers["mcp:" + tool_name] = wrapper
        return wrapper

    def get_wrapper(self, wrapper_id: str) -> Optional[MCPToolWrapper]:
        with self._lock:
            return self._wrappers.get(wrapper_id)

    def list_wrappers(self) -> List[str]:
        with self._lock:
            return list(self._wrappers.keys())

    def disconnect_all(self) -> None:
        with self._lock:
            for client in self._clients.values():
                client.disconnect()
            self._clients.clear()


# instance Ø¹Ø§Ù…Ø©
mcp_adapter = MCPAdapter()
