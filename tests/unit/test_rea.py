import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from core.rea import REAEngine, REAStep, REAEvidence, REARecord, rea_engine

# Ø§Ø®ØªØ¨Ø§Ø± REA engine
rea = REAEngine()

# Ø¨Ø¯Ø¡ Ø¥Ø¬Ø±Ø§Ø¡
record_id = rea.start_action("create_file", "user1", expected_effect="/tmp/test.txt exists")
assert record_id is not None
record = rea.get_record(record_id)
assert record is not None
assert record.action == "create_file"
assert record.actor == "user1"
assert record.current_step == REAStep.ACTION
print("OK: REA start_action")

# Ù…Ù„Ø§Ø­Ø¸Ø©
obs_ok = rea.observe(record_id, "/tmp/test.txt exists")
assert obs_ok == True
record = rea.get_record(record_id)
assert record.observation == "/tmp/test.txt exists"
assert record.current_step == REAStep.OBSERVATION
print("OK: REA observe")

# Ø§Ù„ØªØ­Ù‚Ù‚
verifier_ok = rea.verify(record_id, "default")
assert verifier_ok == True
record = rea.get_record(record_id)
assert record.verification_passed == True
assert record.evidence is not None
assert record.evidence.matched == True
print("OK: REA verify")

# Postcondition
post_ok = rea.check_postcondition(record_id, True)
assert post_ok == True
record = rea.get_record(record_id)
assert record.postcondition_met == True
assert record.current_step == REAStep.POSTCONDITION
print("OK: REA check_postcondition")

# Ø¥ÙƒÙ…Ø§Ù„
complete_ok = rea.complete(record_id)
assert complete_ok == True
record = rea.get_record(record_id)
assert record.completed == True
assert record.current_step == REAStep.COMPLETED
assert record.completed_at is not None
print("OK: REA complete")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ÙØ´Ù„ ÙÙŠ Ø§Ù„ØªØ­Ù‚Ù‚
record_id2 = rea.start_action("delete_file", "user2", expected_effect="file deleted")
rea.observe(record_id2, "file not found")
verifier_ok2 = rea.verify(record_id2, "default")
assert verifier_ok2 == False  # Ø§Ù„Ù…Ù„Ø§Ø­Ø¸Ø© Ù„Ø§ ØªØªØ·Ø§Ø¨Ù‚ Ù…Ø¹ Ø§Ù„Ù…ØªÙˆÙ‚Ø¹
print("OK: REA verify mismatch detected")

# Ø¹Ø¯Ù… Ø§Ù„Ø¥ÙƒÙ…Ø§Ù„ Ø¥Ø°Ø§ Ù„Ù… ÙŠØªØ­Ù‚Ù‚ postcondition
record_id3 = rea.start_action("partial_task", "user3", expected_effect="done")
rea.observe(record_id3, "done")
rea.verify(record_id3, "default")
rea.check_postcondition(record_id3, False)  # postcondition ØºÙŠØ± Ù…Ø­Ù‚Ù‚Ø©
complete_ok3 = rea.complete(record_id3)
assert complete_ok3 == False
print("OK: REA complete blocked when postcondition not met")

# Ø§Ù„Ø³Ø¬Ù„Ø§Øª Ø§Ù„Ù…ÙƒØªÙ…Ù„Ø©
completed = rea.get_completed_records()
assert len(completed) >= 1
print("OK: REA completed records:", len(completed))

# Ø§Ù„Ø³Ø¬Ù„Ø§Øª Ø§Ù„Ù…Ø¹Ù„Ù‚Ø©
pending = rea.get_pending_records()
assert len(pending) >= 1
print("OK: REA pending records:", len(pending))

print("=== REA integration tests: PASSED ===")
