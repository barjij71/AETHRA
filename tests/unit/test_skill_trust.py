import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from skills.trust_model import (
    SkillTrustModel, TrustLevel, SkillSource, SkillSourceInfo,
    TrustAssessment, skill_trust_model,
)

# Ø§Ø®ØªØ¨Ø§Ø± SkillTrustModel
tm = SkillTrustModel()

# Ù…Ù‡Ø§Ø±Ø© Ù†Ø¸Ø§Ù…ÙŠØ© Ù…ÙˆØ«ÙˆÙ‚Ø©
source_sys = SkillSourceInfo(source_type=SkillSource.SYSTEM, publisher="AETHRA", publisher_verified=True, license="MIT")
assessment = tm.assess("skill.system", source_sys, ["shell.exec"], ["file.read"])
assert assessment.trust_level == TrustLevel.VERIFIED or assessment.trust_level == TrustLevel.HIGH
assert assessment.score >= 0.8
assert assessment.requires_approval == False
print("OK: System skill -", assessment.trust_level.value, assessment.score)

# Ù…Ù‡Ø§Ø±Ø© Ø®Ø§Ø±Ø¬ÙŠØ© ØºÙŠØ± Ù…ÙˆØ«ÙˆÙ‚Ø©
source_ext = SkillSourceInfo(source_type=SkillSource.EXTERNAL, publisher=None, publisher_verified=False, license=None)
assessment2 = tm.assess("skill.external", source_ext, ["root", "system.shutdown"], ["root", "network.access"])
assert assessment2.trust_level in (TrustLevel.UNTRUSTED, TrustLevel.LOW)
assert assessment2.score < 0.7
assert assessment2.requires_approval == True
assert len(assessment2.recommendations) > 0
print("OK: External untrusted skill -", assessment2.trust_level.value, assessment2.score)

# Ù…Ù‡Ø§Ø±Ø© Ù…ØªÙˆØ³Ø·Ø© Ù…Ù† Ø§Ù„Ù…Ø¬ØªÙ…Ø¹
source_comm = SkillSourceInfo(source_type=SkillSource.COMMUNITY, publisher="dev_user", publisher_verified=False, license="GPL")
assessment3 = tm.assess("skill.community", source_comm, ["file.read"], ["file.read"])
assert assessment3.trust_level in list(TrustLevel)
print("OK: Community skill -", assessment3.trust_level.value, assessment3.score)

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø­ØµÙˆÙ„ Ø¹Ù„Ù‰ Ø§Ù„ØªÙ‚ÙŠÙŠÙ…
retrieved = tm.get_assessment("skill.system")
assert retrieved is not None
assert retrieved.skill_id == "skill.system"
print("OK: Get assessment")

# Ø§Ø®ØªØ¨Ø§Ø± Ø¬Ù…ÙŠØ¹ Ø§Ù„ØªÙ‚ÙŠÙŠÙ…Ø§Øª
all_assessments = tm.get_all_assessments()
assert len(all_assessments) >= 3
print("OK: All assessments:", len(all_assessments))

# Ø§Ø®ØªØ¨Ø§Ø± is_trusted
assert tm.is_trusted("skill.system") == True
assert tm.is_trusted("skill.external") == False
print("OK: is_trusted")

# Ø§Ø®ØªØ¨Ø§Ø± Ø¥Ø¶Ø§ÙØ© Ù†Ø§Ø´Ø± Ù…ÙˆØ«ÙˆÙ‚
tm.add_trusted_publisher("trusted_dev")
source_priv = SkillSourceInfo(source_type=SkillSource.PRIVATE, publisher="trusted_dev", publisher_verified=True, license="Apache")
assessment4 = tm.assess("skill.private", source_priv, ["file.read"], ["file.read"])
assert assessment4.trust_level in (TrustLevel.HIGH, TrustLevel.VERIFIED)
print("OK: Trusted publisher skill -", assessment4.trust_level.value)

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø¥Ø­ØµØ§Ø¦ÙŠØ§Øª
stats = tm.get_stats()
assert stats["total_assessed"] >= 3
assert "by_trust_level" in stats
print("OK: Skill trust stats:", stats["total_assessed"], "assessed")

# Ø§Ù„Ù…Ø«ÙŠÙ„ Ø§Ù„Ø¹Ø§Ù…
assert skill_trust_model is not None
print("OK: Global skill_trust_model available")

print("=== Skill Trust Model tests: PASSED ===")
