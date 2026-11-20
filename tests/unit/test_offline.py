import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from core.offline import (
    OfflineManager, OfflineCapability, OfflineModeConfig,
    ConnectivityStatus, offline_manager,
)

# Ø§Ø®ØªØ¨Ø§Ø± OfflineManager
om = OfflineManager()
assert om.status == ConnectivityStatus.ONLINE
assert om.is_online() == True
assert om.is_offline() == False
assert om.is_degraded() == False
print("OK: OfflineManager initial status: ONLINE")

# ØªØ¹ÙŠÙŠÙ† ÙˆØ¶Ø¹ Ø¹Ø¯Ù… Ø§Ù„Ø§ØªØµØ§Ù„
om.set_status(ConnectivityStatus.OFFLINE)
assert om.status == ConnectivityStatus.OFFLINE
assert om.is_offline() == True
assert om.is_online() == False
print("OK: set_status OFFLINE")

# ØªØ¹ÙŠÙŠÙ† ÙˆØ¶Ø¹ Ù…ØªØ¯Ù‡ÙˆØ±
om.set_status(ConnectivityStatus.DEGRADED)
assert om.is_degraded() == True
print("OK: set_status DEGRADED")

# Ø¥Ø±Ø¬Ø§Ø¹ Ø¥Ù„Ù‰nected
om.set_status(ConnectivityStatus.ONLINE)
assert om.is_online() == True
print("OK: set_status ONLINE")

# ØªØ³Ø¬ÙŠÙ„ Ù…Ø²ÙˆØ¯ Ù…Ø­Ù„ÙŠ
om.register_local_provider("ollama_local", {"name": "Ollama Local"})
assert om.get_local_provider("ollama_local") is not None
assert om.list_local_providers() == ["ollama_local"]
print("OK: register/get local provider")

# ØªØ³Ø¬ÙŠÙ„ Ø£Ø¯Ø§Ø© Ù…Ø­Ù„ÙŠØ©
om.register_local_tool("shell.exec", {"name": "Shell Exec"})
assert om.get_local_tool("shell.exec") is not None
assert om.list_local_tools() == ["shell.exec"]
print("OK: register/get local tool")

# ØªØ®Ø²ÙŠÙ† ÙˆØ¬Ù„Ø¨ Ù†ØªÙŠØ¬Ø© Ù…Ø®Ø¨Ø£Ø©
om.cache_result("task_1_result", {"output": "hello"})
assert om.has_cached_result("task_1_result") == True
result = om.get_cached_result("task_1_result")
assert result == {"output": "hello"}
print("OK: cache/get result")

# Ø§Ù„Ù‚Ø¯Ø±Ø© Ø¹Ù„Ù‰ Ø§Ù„ØªÙ†ÙÙŠØ° Ø¨Ø¯ÙˆÙ† Ø§ØªØµØ§Ù„
om.set_status(ConnectivityStatus.OFFLINE)
assert om.can_execute_offline("shell.exec") == True
assert om.can_execute_offline("nonexistent_tool") == False
print("OK: can_execute_offline")

# Ø§Ù„Ù‚Ø¯Ø±Ø§Øª Ø§Ù„Ù…ØªØ§Ø­Ø©
caps = om.get_available_capabilities()
assert len(caps) >= 1
assert caps[0].available_offline == True
print("OK: get_available_capabilities:", len(caps))

# Ø³Ø¬Ù„Ø§Øª Ø§Ù„Ø§ØªØµØ§Ù„
callbacks = []
def on_status_change(status):
    callbacks.append(status.value)
om.register_callback(on_status_change)
assert len(om._callbacks) >= 1
om.set_status(ConnectivityStatus.OFFLINE)
om.set_status(ConnectivityStatus.ONLINE)
print("OK: connectivity callback registered")

# Ù…Ù„Ø®Øµ Ø§Ù„Ø­Ø§Ù„Ø©
summary = om.get_status_summary()
assert "status" in summary
assert "local_providers" in summary
assert "local_tools" in summary
print("OK: get_status_summary:", summary)

# Ø§Ù„Ù…Ø«ÙŠÙ„ Ø§Ù„Ø¹Ø§Ù…
assert offline_manager is not None
print("OK: Global offline_manager available")

print("=== Offline degradation tests: PASSED ===")
