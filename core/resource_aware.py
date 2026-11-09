"""ØªÙ†ÙÙŠØ° ÙˆØ§Ø¹Ù Ø¨Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ (Resource-aware execution).

ÙŠØ±Ø¨Ø· Ø¨ÙŠÙ† SystemMonitor Ùˆ TaskEngine Ù„Ø¶Ø¨Ø· Ø§Ù„Ø£Ø¯Ø§Ø¡ Ø­Ø³Ø¨ Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯:
- bounded concurrency
- backpressure
- adaptive execution
- throttle Ø¹Ù†Ø¯Ù…Ø§ ØªÙƒÙˆÙ† Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ Ù…Ø±ØªÙØ¹Ø©
"""

from __future__ import annotations
import threading
import time
from typing import Any, Callable, Dict, List, Optional

from core.monitoring import ResourceLevel, ResourceSnapshot, system_monitor
from core.task import TaskEngine, TaskStatus


class ResourceAwareTaskScheduler:
    """Ù…Ø¬Ø¯ÙˆÙ„ Ù…Ù‡Ø§Ù… ÙˆØ§Ø¹Ù Ø¨Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""

    def __init__(
        self,
        task_engine: TaskEngine,
        max_concurrent_tasks: int = 10,
    ) -> None:
        self.task_engine = task_engine
        self._max_concurrent_tasks = max_concurrent_tasks
        self._current_concurrency = max_concurrent_tasks
        self._running_count = 0
        self._lock = threading.RLock()
        self._monitor = system_monitor
        self._throttle_callbacks: List[Callable[[float], None]] = []

    def get_adaptive_concurrency(self) -> int:
        """Ø¥Ø±Ø¬Ø§Ø¹ Ø¹Ø¯Ø¯ Ø§Ù„Ù…Ù‡Ø§Ù… Ø§Ù„Ù…ØªØ²Ø§Ù…Ù†Ø© Ø§Ù„Ù…ÙˆØµÙ‰ Ø¨Ù‡ Ø­Ø³Ø¨ Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""
        snapshot = self._monitor.get_latest_snapshot()
        if snapshot is None:
            return self._max_concurrent_tasks

        level = snapshot.overall_level()
        with self._lock:
            if level == ResourceLevel.CRITICAL:
                self._current_concurrency = max(1, self._max_concurrent_tasks // 4)
            elif level == ResourceLevel.HIGH:
                self._current_concurrency = max(2, self._max_concurrent_tasks // 2)
            elif level == ResourceLevel.ELEVATED:
                self._current_concurrency = max(4, int(self._max_concurrent_tasks * 0.75))
            else:
                self._current_concurrency = self._max_concurrent_tasks
        return self._current_concurrency

    def can_start_task(self) -> bool:
        """Ù‡Ù„ ÙŠÙ…ÙƒÙ† Ø¨Ø¯Ø¡ Ù…Ù‡Ù…Ø© Ø¬Ø¯ÙŠØ¯Ø© Ø¨Ù†Ø§Ø¡Ù‹ Ø¹Ù„Ù‰ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ Ø§Ù„Ø­Ø§Ù„ÙŠØ©ØŸ"""
        with self._lock:
            if self._running_count >= self._current_concurrency:
                return False
        snapshot = self._monitor.get_latest_snapshot()
        if snapshot is None:
            return True
        return snapshot.overall_level() != ResourceLevel.CRITICAL

    def acquire_slot(self) -> bool:
        """Ø§Ù„Ø­ØµÙˆÙ„ Ø¹Ù„Ù‰ Ù…ÙƒØ§Ù† ØªÙ†ÙÙŠØ°. ÙŠØ¹ÙŠØ¯ False Ø¥Ø°Ø§ ÙƒØ§Ù† Ù…Ù…ØªÙ„Ø¦Ø§Ù‹."""
        with self._lock:
            concurrency = self.get_adaptive_concurrency()
            if self._running_count >= concurrency:
                return False
            self._running_count += 1
            return True

    def release_slot(self) -> None:
        """Ø¥Ø·Ù„Ø§Ù‚ Ù…ÙƒØ§Ù† ØªÙ†ÙÙŠØ°."""
        with self._lock:
            if self._running_count > 0:
                self._running_count -= 1

    def should_throttle(self) -> bool:
        """Ù‡Ù„ ÙŠØ¬Ø¨ ØªØ¨Ø·ÙŠØ¡ Ø§Ù„ØªÙ†ÙÙŠØ° Ø¨Ø³Ø¨Ø¨ Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ØŸ"""
        snapshot = self._monitor.get_latest_snapshot()
        if snapshot is None:
            return False
        return snapshot.overall_level() in (ResourceLevel.HIGH, ResourceLevel.CRITICAL)

    def get_backpressure_delay(self) -> float:
        """ØªØ£Ø®ÙŠØ± Ø§Ù„Ø¶ØºØ· Ø§Ù„Ø®Ù„ÙÙŠ (backpressure) Ø¨Ø§Ù„Ø«ÙˆØ§Ù†ÙŠ."""
        snapshot = self._monitor.get_latest_snapshot()
        if snapshot is None:
            return 0.0
        level = snapshot.overall_level()
        if level == ResourceLevel.CRITICAL:
            return 2.0
        elif level == ResourceLevel.HIGH:
            return 1.0
        elif level == ResourceLevel.ELEVATED:
            return 0.5
        return 0.0

    def register_throttle_callback(self, callback: Callable[[float], None]) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ø¯Ø§Ù„Ø© Ø§Ø³ØªØ¯Ø¹Ø§Ø¡ Ø¹Ù†Ø¯ Ø§Ù„ØªÙ‚Ù†ÙŠÙ†."""
        self._throttle_callbacks.append(callback)

    def notify_throttle(self, delay: float) -> None:
        """Ø¥Ø´Ø¹Ø§Ø± Ø¬Ù…ÙŠØ¹ Ø¯ÙˆØ§Ù„ Ø§Ù„ØªÙ‚Ù†ÙŠÙ†."""
        for cb in self._throttle_callbacks:
            try:
                cb(delay)
            except Exception:  # noqa: BLE001
                pass

    def get_status(self) -> Dict[str, Any]:
        """Ø­Ø§Ù„Ø© Ø§Ù„Ù…Ø¬Ø¯ÙˆÙ„ Ø§Ù„Ø­Ø§Ù„ÙŠØ©."""
        snapshot = self._monitor.get_latest_snapshot()
        with self._lock:
            return {
                "running_count": self._running_count,
                "max_concurrent": self._max_concurrent_tasks,
                "current_concurrency": self._current_concurrency,
                "resource_level": snapshot.overall_level().value if snapshot else "unknown",
                "should_throttle": self.should_throttle(),
                "backpressure_delay": self.get_backpressure_delay(),
            }


class AdaptiveExecutor:
    """Ù…Ù†ÙØ° ØªÙƒÙŠÙÙŠ ÙŠØ¶Ø¨Ø· Ø³Ù„ÙˆÙƒ Ø§Ù„ØªÙ†ÙÙŠØ° Ø­Ø³Ø¨ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""

    def __init__(
        self,
        scheduler: ResourceAwareTaskScheduler,
    ) -> None:
        self.scheduler = scheduler

    def execute_with_adaptation(
        self,
        task_func: Callable[[], Any],
        task_name: str = "adaptive_task",
    ) -> Any:
        """ØªÙ†ÙÙŠØ° Ù…Ù‡Ù…Ø© Ù…Ø¹ ØªÙƒÙŠÙŠÙ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""
        delay = self.scheduler.get_backpressure_delay()
        if delay > 0:
            time.sleep(delay)

        if not self.scheduler.can_start_task():
            raise RuntimeError("Ù„Ø§ ÙŠÙ…ÙƒÙ† Ø¨Ø¯Ø¡ Ø§Ù„Ù…Ù‡Ù…Ø© - Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ Ù…Ù…ØªÙ„Ø¦Ø© Ø£Ùˆ Ø­Ø±Ø¬Ø©")

        acquired = self.scheduler.acquire_slot()
        if not acquired:
            raise RuntimeError("Ù„Ø§ ÙŠÙ…ÙƒÙ† Ø§Ù„Ø­ØµÙˆÙ„ Ø¹Ù„Ù‰ Ù…ÙƒØ§Ù† ØªÙ†ÙÙŠØ°")

        try:
            result = task_func()
            return result
        finally:
            self.scheduler.release_slot()

    def get_status(self) -> Dict[str, Any]:
        return self.scheduler.get_status()


# instances Ø¹Ø§Ù…Ø©
resource_aware_scheduler = ResourceAwareTaskScheduler(
    task_engine=None,  # Ø³ÙŠØªÙ… Ø±Ø¨Ø·Ù‡ Ù„Ø§Ø­Ù‚Ù‹Ø§
)
adaptive_executor = AdaptiveExecutor(resource_aware_scheduler)
