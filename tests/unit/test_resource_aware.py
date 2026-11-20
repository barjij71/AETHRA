import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from core.resource_aware import (
    ResourceAwareTaskScheduler, AdaptiveExecutor,
    resource_aware_scheduler, adaptive_executor,
)
from core.task import TaskEngine

# Ø§Ø®ØªØ¨Ø§Ø± ResourceAwareTaskScheduler
engine = TaskEngine()
scheduler = ResourceAwareTaskScheduler(task_engine=engine, max_concurrent_tasks=5)

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø­ØµÙˆÙ„ Ø¹Ù„Ù‰ Ø§Ù„ØªÙ‚Ø§Ø¹Ø¯ Ø§Ù„Ù…ØªÙƒÙŠÙ
conc = scheduler.get_adaptive_concurrency()
assert conc >= 1
assert conc <= 5
print("OK: get_adaptive_concurrency:", conc)

# Ø§Ø®ØªØ¨Ø§Ø± Ø§ÙƒØªØ³Ø§Ø¨ ÙˆØ¥Ø·Ù„Ø§Ù‚ Ù…ÙƒØ§Ù†
assert scheduler.acquire_slot() == True
assert scheduler.can_start_task() == True  # Ù„Ø§ ØªØ²Ø§Ù„ Ù‡Ù†Ø§Ùƒ Ø£Ù…Ø§ÙƒÙ†
scheduler.release_slot()
print("OK: acquire/release slot")

# Ø§Ø®ØªØ¨Ø§Ø± Ù…Ù„Ø¡ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ø£Ù…Ø§ÙƒÙ†
for i in range(5):
    assert scheduler.acquire_slot() == True
assert scheduler.acquire_slot() == False  # Ù…Ù…ØªÙ„Ø¦
scheduler.release_slot()
print("OK: concurrency limit enforced")

# Ø§Ø®ØªØ¨Ø§Ø± should_throttle Ùˆ backpressure_delay
throttle = scheduler.should_throttle()
print("OK: should_throttle:", throttle)

delay = scheduler.get_backpressure_delay()
assert delay >= 0.0
print("OK: backpressure_delay:", delay)

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø­Ø§Ù„Ø©
status = scheduler.get_status()
assert "running_count" in status
assert "current_concurrency" in status
assert "resource_level" in status
print("OK: get_status:", status)

# Ø§Ø®ØªØ¨Ø§Ø± AdaptiveExecutor
exec_ = AdaptiveExecutor(scheduler)
result = exec_.execute_with_adaptation(lambda: 42, "test_task")
assert result == 42
print("OK: AdaptiveExecutor execute_with_adaptation")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø­Ø§Ù„Ø§Øª Ø§Ù„Ø¹Ø§Ù…Ø©
assert resource_aware_scheduler is not None
assert adaptive_executor is not None
print("OK: Global instances available")

print("=== Resource-aware execution tests: PASSED ===")
