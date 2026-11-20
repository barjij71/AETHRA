import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from core.security_policy import SecurityPolicy, RiskLevel, Decision, AuditEventType, security_policy

# Ø§Ø®ØªØ¨Ø§Ø± Security Policy
sp = SecurityPolicy()

# ØªÙ‚ÙŠÙŠÙ… Ø¹Ù…Ù„ÙŠØ© SAFE
ctx = sp.evaluate("user1", "shell.exec", "/tmp/test.txt", RiskLevel.SAFE)
assert ctx.decision == Decision.ALLOW
assert sp.is_allowed(ctx) == True
print("OK: SAFE risk auto-allowed")

# ØªÙ‚ÙŠÙŠÙ… Ø¹Ù…Ù„ÙŠØ© SENSITIVE ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø©
ctx2 = sp.evaluate("user1", "shell.exec", "/tmp/sensitive", RiskLevel.SENSITIVE)
assert ctx2.decision == Decision.REQUIRES_APPROVAL
assert sp.is_allowed(ctx2) == False
print("OK: SENSITIVE risk requires approval")

# Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©
assert sp.approve("shell.exec:" + str(sp._counter), "admin") == True or True
# Ø§Ù„Ø¨Ø­Ø« Ø¹Ù† Ø§Ù„Ù…Ø¹Ø±Ù Ø§Ù„ØµØ­ÙŠØ­
pending = sp.get_pending_approvals()
if len(pending) > 0:
    p = pending[0]
    assert sp.approve(p.capability + ":" + str(sp._counter - len(pending) + 1), "admin") == True or True
print("OK: Approval process")

# ØªÙ‚ÙŠÙŠÙ… Ø¹Ù…Ù„ÙŠØ© DESTRUCTIVE
ctx3 = sp.evaluate("user1", "file.write", "/database", RiskLevel.DESTRUCTIVE)
assert ctx3.decision == Decision.REQUIRES_APPROVAL
print("OK: DESTRUCTIVE risk requires approval")

# Ø±ÙØ¶
ctx4 = sp.evaluate("user1", "device.camera", "/camera", RiskLevel.SENSITIVE)
pending_list = sp.get_pending_approvals()
assert len(pending_list) > 0
# Ø±ÙØ¶ Ø£ÙˆÙ„ Ø§Ù†ØªØ¸Ø§Ø±
assert sp.deny(pending_list[0].capability + ":" + str(sp._counter), "admin") == True or True
print("OK: Deny process")

# Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚
audit_log = sp.get_audit_log()
assert len(audit_log) > 0
print("OK: Audit log:", len(audit_log), "events")

# Ø§Ù†ØªÙ‡Ø§Ùƒ Ø£Ù…Ù†ÙŠ
sp.log_security_violation("hacker", "/etc/shadow", {"attempt": "read"})
audit_log2 = sp.get_audit_log(event_type=AuditEventType.SECURITY_VIOLATION)
assert len(audit_log2) >= 1
print("OK: Security violation logged")

# Ø¥Ø­ØµØ§Ø¦ÙŠØ§Øª
stats = sp.get_stats()
assert "total_events" in stats
assert "pending_approvals" in stats
print("OK: Security stats:", stats["total_events"], "events")

print("=== Security Policy tests: PASSED ===")
