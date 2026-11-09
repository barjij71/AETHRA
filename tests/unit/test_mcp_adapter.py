import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from tools.mcp_adapter import MCPClient, MCPToolWrapper, MCPAdapter, mcp_adapter

# Ø§Ø®ØªØ¨Ø§Ø± MCPAdapter (Ø¨Ø¯ÙˆÙ† Ø®Ø§Ø¯Ù… Ø­Ù‚ÙŠÙ‚ÙŠ - ÙŠØ³ØªØ®Ø¯Ù… Ù…Ø­Ø§ÙƒØ§Ø©)
adapter = MCPAdapter()
assert adapter is not None
print("OK: MCPAdapter created")

# Ø§Ø®ØªØ¨Ø§Ø± MCPClient (Ø¨Ø¯ÙˆÙ† Ø®Ø§Ø¯Ù… Ø­Ù‚ÙŠÙ‚ÙŠ)
client = MCPClient(["mcp-server-test"])
assert client.is_connected() == False
print("OK: MCPClient created (not connected)")

# Ø§Ø®ØªØ¨Ø§Ø± MCPToolWrapper
wrapper_info = {
    "description": "Ø£Ø¯Ø§Ø© Ø§Ø®ØªØ¨Ø§Ø± MCP",
    "inputSchema": {"type": "object", "properties": {"query": {"type": "string"}}},
}
wrapper = MCPToolWrapper(client, "test_tool", wrapper_info)
assert wrapper.tool_name == "test_tool"
assert wrapper.mcp_client == client
print("OK: MCPToolWrapper created")

# Ø§Ø®ØªØ¨Ø§Ø± to_aethra_tool
tool = wrapper.to_aethra_tool()
assert tool.id == "mcp:test_tool"
assert tool.name == "test_tool"
assert tool.category.value == "custom"
print("OK: MCPToolWrapper.to_aethra_tool -", tool.id, tool.risk.value)

# Ø§Ø®ØªØ¨Ø§Ø± execute (Ø³ÙŠÙØ´Ù„ Ù„Ø£Ù† Ø§Ù„Ø¹Ù…ÙŠÙ„ ØºÙŠØ± Ù…ØªØµÙ„ØŒ Ù„ÙƒÙ†Ù‡ Ù„Ø§ ÙŠØ¬Ø¨ Ø£Ù† ÙŠÙ†Ù‡Ø§Ø±)
result = wrapper.execute({"query": "test"})
assert result.success == False
assert result.error is not None
print("OK: MCPToolWrapper execute (disconnected)")

# Ø§Ø®ØªØ¨Ø§Ø± connect_server (Ø³ÙŠÙØ´Ù„ Ù„Ø£Ù† Ù„Ø§ Ø®Ø§Ø¯Ù… MCP Ø­Ù‚ÙŠÙ‚ÙŠØŒ Ù„ÙƒÙ†Ù‡ Ù„Ø§ ÙŠØ¬Ø¨ Ø£Ù† ÙŠÙ†Ù‡Ø§Ø±)
connected = adapter.connect_server("test_server", ["mcp-server"])
assert connected == False  # Ù„Ø§ Ø®Ø§Ø¯Ù… MCP Ø­Ù‚ÙŠÙ‚ÙŠ Ù…ØªØ§Ø­
print("OK: MCPAdapter connect_server (no server)")

# Ø§Ø®ØªØ¨Ø§Ø± list_wrappers
wrappers = adapter.list_wrappers()
assert isinstance(wrappers, list)
print("OK: MCPAdapter list_wrappers:", len(wrappers))

# Ø§Ù„Ù…Ø«ÙŠÙ„ Ø§Ù„Ø¹Ø§Ù…
assert mcp_adapter is not None
print("OK: Global mcp_adapter available")

print("=== MCP Adapter tests: PASSED ===")
