import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from core.monitoring import (
    SystemMonitor, HealthChecker, ResourceAwareExecutor,
    ResourceLevel, ResourceSnapshot, HealthStatus,
    system_monitor, health_checker, resource_executor,
)

# Ø§Ø®ØªØ¨Ø§Ø± SystemMonitor
mon = SystemMonitor()
snap = mon.take_snapshot()
assert snap is not None
assert snap.cpu_percent >= 0
assert snap.memory_percent >= 0
assert snap.disk_percent >= 0
print("OK: SystemMonitor snapshot - CPU:", snap.cpu_percent, "MEM:", snap.memory_percent, "DISK:", snap.disk_percent)

# latest snapshot
latest = mon.get_latest_snapshot()
assert latest is not None
assert latest.timestamp == snap.timestamp
print("OK: SystemMonitor latest snapshot")

# snapshots list
snaps = mon.get_snapshots(5)
assert len(snaps) >= 1
print("OK: SystemMonitor snapshots list:", len(snaps))

# resource level
level = snap.overall_level()
assert level in (ResourceLevel.NORMAL, ResourceLevel.ELEVATED, ResourceLevel.HIGH, ResourceLevel.CRITICAL)
print("OK: Resource level:", level.value)

# HealthChecker
hc = HealthChecker()
def check_cpu():
    return HealthStatus(component="cpu", healthy=True, message="CPU OK")
hc.register_check("cpu", check_cpu)
result = hc.run_check("cpu")
assert result is not None
assert result.healthy == True
assert result.component == "cpu"
print("OK: HealthChecker run_check")

# run all checks
results = hc.run_all_checks()
assert "cpu" in results
print("OK: HealthChecker run_all_checks")

# system healthy
assert hc.is_system_healthy() == True
print("OK: HealthChecker is_system_healthy")

# failing check
def check_bad():
    return HealthStatus(component="bad", healthy=False, message="failed")
hc.register_check("bad", check_bad)
hc.run_check("bad")
result_bad = hc.get_result("bad")
assert result_bad is not None
assert result_bad.healthy == False
# Ø§Ù„Ù†Ø¸Ø§Ù… ØºÙŠØ± ØµØ­ÙŠ Ù„Ø£Ù† Ù‡Ù†Ø§Ùƒ ÙØ­Øµ ÙØ§Ø´Ù„
all_results = hc.run_all_checks()
assert any(not r.healthy for r in all_results.values())
print("OK: HealthChecker unhealthy detection")

# ResourceAwareExecutor
executor = ResourceAwareExecutor(mon)
conc = executor.get_adaptive_concurrency()
assert conc >= 1
assert conc <= 10
print("OK: ResourceAwareExecutor concurrency:", conc)

# throttle
throttle = executor.should_throttle()
print("OK: ResourceAwareExecutor should_throttle:", throttle)

# backpressure delay
delay = executor.get_backpressure_delay()
assert delay >= 0.0
print("OK: ResourceAwareExecutor backpressure_delay:", delay)

# instances Ø§Ù„Ø¹Ø§Ù…Ø©
assert system_monitor is not None
assert health_checker is not None
assert resource_executor is not None
print("OK: Global instances available")

print("=== Monitoring tests: PASSED ===")
