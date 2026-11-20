import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from core.learning import (
    LearningEngine, ExperienceRecord, Pattern, Suggestion,
    ExperienceType, SuggestionStatus, learning_engine,
)

# Ø§Ø®ØªØ¨Ø§Ø± LearningEngine
le = LearningEngine()

# ØªØ³Ø¬ÙŠÙ„ ØªØ¬Ø§Ø±Ø¨
eid1 = le.record_experience(
    ExperienceType.TASK_SUCCESS,
    "ØªÙ†ÙÙŠØ° Ù…Ù‡Ù…Ø© Ø¨Ù†Ø¬Ø§Ø­",
    task_id="task_1",
    tool_id="shell.exec",
    outcome="success",
    context={"duration": 5.0},
)
assert eid1 is not None
print("OK: record_experience success")

eid2 = le.record_experience(
    ExperienceType.TASK_FAILURE,
    "ÙØ´Ù„ ÙÙŠ ØªÙ†ÙÙŠØ° Ù…Ù‡Ù…Ø©",
    task_id="task_2",
    tool_id="shell.exec",
    outcome="failure",
    context={"error": "timeout"},
)
assert eid2 is not None
print("OK: record_experience failure")

eid3 = le.record_experience(
    ExperienceType.TASK_SUCCESS,
    "Ù…Ù‡Ù…Ø© Ù†Ø§Ø¬Ø­Ø© Ø£Ø®Ø±Ù‰",
    task_id="task_3",
    tool_id="file.write",
    outcome="success",
)
assert eid3 is not None
print("OK: record_experience success 2")

# Ø§Ø³ØªØ±Ø¬Ø§Ø¹ Ø§Ù„ØªØ¬Ø§Ø±Ø¨
exps = le.get_experiences(10)
assert len(exps) >= 3
print("OK: get_experiences:", len(exps))

# Ø§Ø³ØªØ±Ø¬Ø§Ø¹ Ø­Ø³Ø¨ Ø§Ù„Ù†ÙˆØ¹
success_exps = le.get_experiences_by_type(ExperienceType.TASK_SUCCESS)
assert len(success_exps) >= 2
print("OK: get_experiences_by_type:", len(success_exps))

# Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ø§Ù„Ø£Ù†Ù…Ø§Ø·
patterns = le.extract_patterns()
assert len(patterns) >= 1
print("OK: extract_patterns:", len(patterns))

# ØªÙˆÙ„ÙŠØ¯ Ø§Ù„Ø§Ù‚ØªØ±Ø§Ø­Ø§Øª
suggestions = le.generate_suggestions()
assert len(suggestions) >= 1
print("OK: generate_suggestions:", len(suggestions))

# Ø§Ø³ØªØ±Ø¬Ø§Ø¹ Ø§Ù„Ø§Ù‚ØªØ±Ø§Ø­Ø§Øª
all_sugg = le.get_suggestions()
assert len(all_sugg) >= 1
print("OK: get_suggestions:", len(all_sugg))

# Ù…Ø±Ø§Ø¬Ø¹Ø© Ø§Ù‚ØªØ±Ø§Ø­
sugg_id = all_sugg[0].id
assert le.review_suggestion(sugg_id, "approve", "admin", "Ù…Ù‚Ø¨ÙˆÙ„ Ù„Ù„ØªØ¬Ø±Ø¨Ø©") == True
sugg = le.get_suggestions(status=SuggestionStatus.APPROVED)
assert len(sugg) >= 1
assert sugg[0].reviewed_by == "admin"
print("OK: review_suggestion approve")

# ØªØ·Ø¨ÙŠÙ‚ Ø§Ù‚ØªØ±Ø§Ø­
assert le.apply_suggestion(sugg_id) == True
applied = le.get_suggestions(status=SuggestionStatus.APPLIED)
assert len(applied) >= 1
print("OK: apply_suggestion")

# Ø±ÙØ¶ Ø§Ù‚ØªØ±Ø§Ø­
sugg_id2 = le.generate_suggestions()[0].id if le.generate_suggestions() else None
if sugg_id2:
    assert le.review_suggestion(sugg_id2, "reject", "admin", "ØºÙŠØ± Ù…Ù†Ø§Ø³Ø¨") == True
    rejected = le.get_suggestions(status=SuggestionStatus.REJECTED)
    assert len(rejected) >= 1
    print("OK: review_suggestion reject")

# Ø§Ù„Ø¥Ø­ØµØ§Ø¦ÙŠØ§Øª
stats = le.get_stats()
assert stats["total_experiences"] >= 3
assert "experiences_by_type" in stats
assert "suggestions_by_status" in stats
assert stats["auto_apply"] == False
print("OK: get_stats:", stats["total_experiences"], "experiences")

# Ø§Ù„Ù…Ø«ÙŠÙ„ Ø§Ù„Ø¹Ø§Ù…
from core.learning import learning_engine
assert learning_engine is not None
print("OK: Global learning_engine available")

print("=== Learning system tests: PASSED ===")
