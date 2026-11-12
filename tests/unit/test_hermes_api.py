import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from ui.hermes_api import HermesAPI, HermesResponse

hermes = HermesAPI()

# Ø§Ø®ØªØ¨Ø§Ø± Ø¥Ø±Ø³Ø§Ù„ Ø±Ø³Ø§Ù„Ø© Ù†ØµÙŠØ©
resp = hermes.send_message("conv_1", "Ù…Ø±Ø­Ø¨Ø§ ÙƒÙŠÙ Ø­Ø§Ù„ÙƒØŸ", actor="user1")
assert resp.success == True
assert resp.message != ""
print("OK: Hermes send_message - success:", resp.success)
print("  response:", resp.message[:80])

# Ø§Ø®ØªØ¨Ø§Ø± Ø¥Ø±Ø³Ø§Ù„ Ø£Ù…Ø± shell (ÙŠØ¨Ø¯Ø£ Ø¨Ù€ /shell )
resp2 = hermes.send_message("conv_1", "/shell echo hello_from_hermes", actor="user1")
assert resp2.success == True
assert "hello_from_hermes" in resp2.message
print("OK: Hermes shell command - success:", resp2.success)

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ø³ØªØ±Ø¬Ø§Ø¹ Ø§Ù„Ø±Ø³Ø§Ø¦Ù„
msgs = hermes.get_messages("conv_1")
assert len(msgs) >= 2
print("OK: Hermes get_messages:", len(msgs))

# Ø§Ø®ØªØ¨Ø§Ø± Ø®Ø·ÙˆØ§Øª Ø§Ù„ØªÙ†ÙÙŠØ°
steps = hermes.get_steps("conv_1")
assert len(steps) >= 1
print("OK: Hermes get_steps:", len(steps))

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø§Ù†ØªÙ‚Ø§Ù„ Ø¥Ù„Ù‰ Ø£Ø­Ø¯Ø« Ø®Ø·ÙˆØ©
latest = hermes.jump_to_latest("conv_1")
assert latest is not None
print("OK: Hermes jump_to_latest:", latest.step_type)

# Ø§Ø®ØªØ¨Ø§Ø± Ù…Ù„Ø®Øµ Ø§Ù„Ù…Ø­Ø§Ø¯Ø«Ø©
summary = hermes.get_conversation_summary("conv_1")
assert summary["message_count"] >= 2
assert summary["step_count"] >= 1
print("OK: Hermes conversation summary")

# Ø§Ø®ØªØ¨Ø§Ø± Ù‚Ø§Ø¦Ù…Ø© Ø§Ù„Ù…Ø­Ø§Ø¯Ø«Ø§Øª
convs = hermes.list_conversations()
assert "conv_1" in convs
print("OK: Hermes list_conversations:", convs)

print("=== Hermes API tests: PASSED ===")
