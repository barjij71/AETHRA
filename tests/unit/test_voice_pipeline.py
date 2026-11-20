import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from voice.pipeline import VoicePipeline

vp = VoicePipeline()
result = vp.process_text_input("Ù…Ø±Ø­Ø¨Ø§ ÙƒÙŠÙ Ø­Ø§Ù„ÙƒØŸ", language="ar")
assert result.success == True
assert result.response != ""
print("OK: Voice Pipeline text input - success:", result.success)

result2 = vp.process_text_input("Ù…Ù† Ø£Ù†ØªØŸ", language="ar")
assert result2.success == True
print("OK: Voice Pipeline identity question - success:", result2.success)

# Ø¹Ù„Ù‰ Ø§Ù„Ø£Ù‚Ù„ Ø¬Ù„Ø³Ø© ÙˆØ§Ø­Ø¯Ø© ÙŠØ¬Ø¨ Ø£Ù† ØªÙØ³Ø¬Ù„
sessions = vp.list_sessions()
assert len(sessions) >= 1
print("OK: Voice Pipeline sessions tracked:", len(sessions))

print("=== Voice Pipeline tests: PASSED ===")
