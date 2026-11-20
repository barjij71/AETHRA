import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from devices.windows_adapter import NectarLinkAdapter, WindowsAdapter, SecureChannel

# Ø§Ø®ØªØ¨Ø§Ø± SecureChannel
channel = SecureChannel("android_1", "windows_1")
assert channel.connect() == True
assert channel.connected == True
assert channel.encrypted == True
assert channel.session_id is not None
print("OK: SecureChannel connect")

# Ø§Ø®ØªØ¨Ø§Ø± NectarLinkAdapter
nl = NectarLinkAdapter()
assert nl.available == True
ch = nl.pair_with_windows("win_dev_1", "android_dev_1")
assert ch.connected == True
assert nl.is_paired() == True
print("OK: NectarLinkAdapter pair")

# Ø¥Ø±Ø³Ø§Ù„ ÙˆØ§Ø³ØªÙ‚Ø¨Ø§Ù„ Ø¹Ø¨Ø± Ø§Ù„Ù‚Ù†Ø§Ø©
sent = nl.send_to_windows(b"test_data")
assert sent == True
print("OK: NectarLinkAdapter send_to_windows")

# Ø§Ø®ØªØ¨Ø§Ø± WindowsAdapter (ØºÙŠØ± Ù…ØªØ§Ø­ ÙÙŠ Linux)
wa = WindowsAdapter()
print("OK: WindowsAdapter (available:", wa.available, ")")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù‚Ø¯Ø±Ø§Øª
caps = wa.get_capabilities()
assert caps.shell == True
assert caps.clipboard == True
assert caps.app_control == True
print("OK: WindowsAdapter capabilities")

print("=== Windows / NectarLink tests: PASSED ===")
