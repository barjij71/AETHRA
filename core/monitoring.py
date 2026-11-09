"""Ù†Ø¸Ø§Ù… Ø§Ù„Ù…Ø±Ø§Ù‚Ø¨Ø© Ø§Ù„Ø°Ø§ØªÙŠØ© ÙˆØ£Ø¯Ø§Ø¡ Ø§Ù„Ù†Ø¸Ø§Ù… (Self-monitoring & Observability).

Ù…Ø±Ø§Ù‚Ø¨Ø©: CPU, RAM, GPU, Battery, Network, Storage, Temperature.
Ø§Ø³ØªØ®Ø¯Ø§Ù…: bounded concurrency, backpressure, resource limits, adaptive execution.
"""

from __future__ import annotations
import enum
import os
import threading
import time
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional


class ResourceLevel(enum.Enum):
    """Ù…Ø³ØªÙˆÙŠØ§Øª Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""
    NORMAL = "NORMAL"
    ELEVATED = "ELEVATED"
    HIGH = "HIGH"
    CRITICAL = "CRITICAL"


@dataclass
class ResourceSnapshot:
    """Ù„Ù‚Ø·Ø§Øª Ù…ÙˆØ§Ø±Ø¯ Ø§Ù„Ù†Ø¸Ø§Ù… ÙÙŠ Ù„Ø­Ø¸Ø© Ù…Ø¹ÙŠÙ†Ø©."""
    timestamp: float
    cpu_percent: float
    memory_percent: float
    memory_available_mb: float
    disk_percent: float
    disk_free_gb: float
    network_sent_mb: float
    network_recv_mb: float
    temperature_c: Optional[float] = None
    battery_percent: Optional[float] = None
    battery_charging: Optional[bool] = None
    load_average: Optional[float] = None

    def overall_level(self) -> ResourceLevel:
        """ØªØ­Ø¯ÙŠØ¯ Ù…Ø³ØªÙˆÙ‰ Ø§Ù„Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ø¹Ø§Ù…."""
        max_cpu = max(self.cpu_percent, self.memory_percent, self.disk_percent)
        if max_cpu >= 90:
            return ResourceLevel.CRITICAL
        elif max_cpu >= 75:
            return ResourceLevel.HIGH
        elif max_cpu >= 60:
            return ResourceLevel.ELEVATED
        return ResourceLevel.NORMAL


@dataclass
class HealthStatus:
    """Ø­Ø§Ù„Ø© ØµØ­Ø© Ù…ÙƒÙˆÙ† Ù…Ù† Ù…ÙƒÙˆÙ†Ø§Øª Ø§Ù„Ù†Ø¸Ø§Ù…."""
    component: str
    healthy: bool
    message: str
    timestamp: float = field(default_factory=time.time)
    metadata: Dict[str, Any] = field(default_factory=dict)


class SystemMonitor:
    """Ù…Ø±Ø§Ù‚Ø¨ Ù…ÙˆØ§Ø±Ø¯ Ø§Ù„Ù†Ø¸Ø§Ù…."""

    def __init__(self) -> None:
        self._snapshots: List[ResourceSnapshot] = []
        self._max_snapshots = 1000
        self._lock = threading.RLock()
        self._callbacks: List[Callable[[ResourceSnapshot], None]] = []
        self._monitoring = False
        self._monitor_thread: Optional[threading.Thread] = None

    def take_snapshot(self) -> ResourceSnapshot:
        """Ø£Ø®Ø° Ù„Ù‚Ø·Ø© Ù…ÙˆØ§Ø±Ø¯ Ø§Ù„Ù†Ø¸Ø§Ù… Ø§Ù„Ø­Ø§Ù„ÙŠØ©."""
        cpu = self._get_cpu_percent()
        mem = self._get_memory_info()
        disk = self._get_disk_info()
        net = self._get_network_info()

        snapshot = ResourceSnapshot(
            timestamp=time.time(),
            cpu_percent=cpu,
            memory_percent=mem["percent"],
            memory_available_mb=mem["available_mb"],
            disk_percent=disk["percent"],
            disk_free_gb=disk["free_gb"],
            network_sent_mb=net["sent_mb"],
            network_recv_mb=net["recv_mb"],
            temperature_c=self._get_temperature(),
            battery_percent=self._get_battery_percent(),
            battery_charging=self._get_battery_charging(),
            load_average=self._get_load_average(),
        )

        with self._lock:
            self._snapshots.append(snapshot)
            if len(self._snapshots) > self._max_snapshots:
                self._snapshots = self._snapshots[-self._max_snapshots:]
            for cb in self._callbacks:
                cb(snapshot)

        return snapshot

    def get_latest_snapshot(self) -> Optional[ResourceSnapshot]:
        with self._lock:
            return self._snapshots[-1] if self._snapshots else None

    def get_snapshots(self, limit: int = 50) -> List[ResourceSnapshot]:
        with self._lock:
            return self._snapshots[-limit:]

    def register_callback(self, callback: Callable[[ResourceSnapshot], None]) -> None:
        with self._lock:
            self._callbacks.append(callback)

    def start_monitoring(self, interval_seconds: float = 5.0) -> None:
        """Ø¨Ø¯Ø¡ Ø§Ù„Ù…Ø±Ø§Ù‚Ø¨Ø© Ø§Ù„Ø¯ÙˆØ±ÙŠØ© ÙÙŠ Ø®ÙŠØ· Ù…Ù†ÙØµÙ„."""
        if self._monitoring:
            return
        self._monitoring = True
        self._monitor_thread = threading.Thread(
            target=self._monitor_loop,
            args=(interval_seconds,),
            daemon=True,
        )
        self._monitor_thread.start()

    def stop_monitoring(self) -> None:
        self._monitoring = False
        if self._monitor_thread:
            self._monitor_thread.join(timeout=2)

    def _monitor_loop(self, interval: float) -> None:
        while self._monitoring:
            self.take_snapshot()
            time.sleep(interval)

    # Ø¯ÙˆØ§Ù„ Ù…Ø³Ø§Ø¹Ø¯Ø© Ù„Ø¬Ù„Ø¨ Ø§Ù„Ù…Ø¹Ù„ÙˆÙ…Ø§Øª (Ù…Ø­Ø§ÙƒØ§Ø© ÙÙŠ Ø¨ÙŠØ¦Ø© Linux)
    def _get_cpu_percent(self) -> float:
        try:
            with open("/proc/stat", "r") as f:
                line = f.readline()
            parts = line.split()
            total = sum(int(x) for x in parts[1:])
            idle = int(parts[4])
            return round((1 - idle / total) * 100, 1) if total > 0 else 0.0
        except Exception:
            return 0.0

    def _get_memory_info(self) -> Dict[str, float]:
        try:
            with open("/proc/meminfo", "r") as f:
                content = f.read()
            mem_total = 0
            mem_available = 0
            for line in content.splitlines():
                if line.startswith("MemTotal:"):
                    mem_total = int(line.split()[1]) / 1024  # MB
                elif line.startswith("MemAvailable:"):
                    mem_available = int(line.split()[1]) / 1024
            if mem_total > 0:
                percent = round((1 - mem_available / mem_total) * 100, 1)
            else:
                percent = 0.0
            return {"percent": percent, "available_mb": round(mem_available, 1)}
        except Exception:
            return {"percent": 0.0, "available_mb": 0.0}

    def _get_disk_info(self) -> Dict[str, float]:
        try:
            st = os.statvfs("/")
            free_gb = round(st.f_bavail * st.f_frsize / (1024**3), 2)
            total_gb = round(st.f_blocks * st.f_frsize / (1024**3), 2)
            percent = round((1 - st.f_bavail / st.f_blocks) * 100, 1) if st.f_blocks > 0 else 0.0
            return {"percent": percent, "free_gb": free_gb, "total_gb": total_gb}
        except Exception:
            return {"percent": 0.0, "free_gb": 0.0, "total_gb": 0.0}

    def _get_network_info(self) -> Dict[str, float]:
        try:
            with open("/proc/net/dev", "r") as f:
                lines = f.readlines()
            sent = 0
            recv = 0
            for line in lines[2:]:
                parts = line.split()
                if len(parts) >= 10:
                    iface = parts[0].strip(":")
                    if iface != "lo":
                        recv += int(parts[1])
                        sent += int(parts[9])
            return {"sent_mb": round(sent / (1024**2), 2), "recv_mb": round(recv / (1024**2), 2)}
        except Exception:
            return {"sent_mb": 0.0, "recv_mb": 0.0}

    def _get_temperature(self) -> Optional[float]:
        try:
            with open("/sys/class/thermal/thermal_zone0/temp", "r") as f:
                temp = int(f.read().strip()) / 1000
            return round(temp, 1)
        except Exception:
            return None

    def _get_battery_percent(self) -> Optional[float]:
        try:
            for path in ["/sys/class/power_supply/BAT0/capacity",
                         "/sys/class/power_supply/BAT1/capacity"]:
                if os.path.exists(path):
                    with open(path, "r") as f:
                        return int(f.read().strip())
        except Exception:
            pass
        return None

    def _get_battery_charging(self) -> Optional[bool]:
        try:
            for path in ["/sys/class/power_supply/BAT0/status",
                         "/sys/class/power_supply/BAT1/status"]:
                if os.path.exists(path):
                    with open(path, "r") as f:
                        return "charging" in f.read().strip().lower()
        except Exception:
            pass
        return None

    def _get_load_average(self) -> Optional[float]:
        try:
            with open("/proc/loadavg", "r") as f:
                return float(f.read().split()[0])
        except Exception:
            return None


class HealthChecker:
    """Ù…Ø¯Ù‚Ù‚ ØµØ­Ø© Ù…ÙƒÙˆÙ†Ø§Øª Ø§Ù„Ù†Ø¸Ø§Ù…."""

    def __init__(self) -> None:
        self._checks: Dict[str, Callable[[], HealthStatus]] = {}
        self._results: Dict[str, HealthStatus] = {}
        self._lock = threading.RLock()

    def register_check(self, name: str, check_fn: Callable[[], HealthStatus]) -> None:
        with self._lock:
            self._checks[name] = check_fn

    def run_check(self, name: str) -> Optional[HealthStatus]:
        check_fn = self._checks.get(name)
        if check_fn is None:
            return None
        try:
            result = check_fn()
        except Exception as exc:
            result = HealthStatus(
                component=name,
                healthy=False,
                message=str(exc),
            )
        with self._lock:
            self._results[name] = result
        return result

    def run_all_checks(self) -> Dict[str, HealthStatus]:
        with self._lock:
            checks = dict(self._checks)
        results = {}
        for name, check_fn in checks.items():
            results[name] = self.run_check(name)
        return results

    def get_result(self, name: str) -> Optional[HealthStatus]:
        with self._lock:
            return self._results.get(name)

    def is_system_healthy(self) -> bool:
        with self._lock:
            return all(r.healthy for r in self._results.values())


class ResourceAwareExecutor:
    """Ù…Ù†ÙØ° ÙˆØ§Ø¹Ù Ø¨Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ - ÙŠØ¶Ø¨Ø· Ø§Ù„Ø£Ø¯Ø§Ø¡ Ø­Ø³Ø¨ Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""

    def __init__(self, monitor: SystemMonitor) -> None:
        self.monitor = monitor
        self._max_concurrent_tasks = 10
        self._current_concurrency = 10
        self._lock = threading.RLock()

    def get_adaptive_concurrency(self) -> int:
        """Ø¥Ø±Ø¬Ø§Ø¹ Ø¹Ø¯Ø¯ Ø§Ù„Ù…Ù‡Ø§Ù… Ø§Ù„Ù…ØªØ²Ø§Ù…Ù†Ø© Ø§Ù„Ù…ÙˆØµÙ‰ Ø¨Ù‡ Ø­Ø³Ø¨ Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯."""
        snapshot = self.monitor.get_latest_snapshot()
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

    def should_throttle(self) -> bool:
        """Ù‡Ù„ ÙŠØ¬Ø¨ ØªØ¨Ø·ÙŠØ¡ Ø§Ù„ØªÙ†ÙÙŠØ° Ø¨Ø³Ø¨Ø¨ Ø§Ø³ØªÙ‡Ù„Ø§Ùƒ Ø§Ù„Ù…ÙˆØ§Ø±Ø¯ØŸ"""
        snapshot = self.monitor.get_latest_snapshot()
        if snapshot is None:
            return False
        return snapshot.overall_level() in (ResourceLevel.HIGH, ResourceLevel.CRITICAL)

    def get_backpressure_delay(self) -> float:
        """ØªØ£Ø®ÙŠØ± Ø§Ù„Ø¶ØºØ· Ø§Ù„Ø®Ù„ÙÙŠ (backpressure) Ø¨Ø§Ù„Ø«ÙˆØ§Ù†ÙŠ."""
        snapshot = self.monitor.get_latest_snapshot()
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


# instances Ø¹Ø§Ù…Ø©
system_monitor = SystemMonitor()
health_checker = HealthChecker()
resource_executor = ResourceAwareExecutor(system_monitor)
