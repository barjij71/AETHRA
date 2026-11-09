import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from devices.android_adapter import AndroidRuntimeAdapter, TermuxAdapter, LinuxAdapter

# Ø§Ø®ØªØ¨Ø§Ø± Android adapter
android = AndroidRuntimeAdapter()
assert android.platform_name == "android"
assert android.is_available() == True
caps = android.get_capabilities()
assert caps.shell == True
assert caps.camera == True
print("OK: AndroidRuntimeAdapter")

# Ø§Ø®ØªØ¨Ø§Ø± Linux adapter
linux = LinuxAdapter()
assert linux.platform_name == "linux"
assert linux.is_available() == True
caps_l = linux.get_capabilities()
assert caps_l.shell == True
assert caps_l.mouse_keyboard == True
print("OK: LinuxAdapter")

# Ø§Ø®ØªØ¨Ø§Ø± Linux execute_command
result = linux.execute_command("echo hello_linux", {})
assert result["success"] == True
assert "hello_linux" in result["output"]
print("OK: LinuxAdapter.execute_command")

# Ø§Ø®ØªØ¨Ø§Ø± Termux adapter (ØºÙŠØ± Ù…ØªØ§Ø­ ÙÙŠ Linux)
termux = TermuxAdapter()
print("OK: TermuxAdapter (available:", termux.is_available(), ")")

print("=== Android Runtime Adapters tests: PASSED ===")
