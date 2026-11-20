"""Ù†Ø¸Ø§Ù… Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª (Notification System) Ù„Ø¬Ù‡Ø§Ø² Mesh.

ÙŠØ³Ù…Ø­ Ø¨Ø¥Ø±Ø³Ø§Ù„ Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø¥Ù„Ù‰ Ø£Ø¬Ù‡Ø²Ø© AndroidØŒ WindowsØŒ Linux Ø¹Ø¨Ø± Device Mesh.
ÙŠØ¯Ø¹Ù…: Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„Ù†Ø¸Ø§Ù…ØŒ Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„Ù…Ù‡Ø§Ù…ØŒ Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„ØªÙ†Ø¨ÙŠÙ‡ØŒ Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„Ù…ÙˆØ§ÙÙ‚Ø©.
"""

from __future__ import annotations
import enum
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class NotificationType(enum.Enum):
    """Ø£Ù†ÙˆØ§Ø¹ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª."""
    INFO = "INFO"
    WARNING = "WARNING"
    ERROR = "ERROR"
    SUCCESS = "SUCCESS"
    TASK_COMPLETED = "TASK_COMPLETED"
    TASK_FAILED = "TASK_FAILED"
    TASK_REQUIRES_APPROVAL = "TASK_REQUIRES_APPROVAL"
    SYSTEM_ALERT = "SYSTEM_ALERT"
    DEVICE_CONNECTED = "DEVICE_CONNECTED"
    DEVICE_DISCONNECTED = "DEVICE_DISCONNECTED"


class NotificationPriority(enum.Enum):
    """Ø£ÙˆÙ„ÙˆÙŠØ© Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±."""
    LOW = "LOW"
    NORMAL = "NORMAL"
    HIGH = "HIGH"
    URGENT = "URGENT"


@dataclass
class Notification:
    """Ø¥Ø´Ø¹Ø§Ø± ÙˆØ§Ø­Ø¯."""
    id: str
    type: NotificationType
    title: str
    message: str
    priority: NotificationPriority = NotificationPriority.NORMAL
    target_device_id: Optional[str] = None  # Ø¥Ø°Ø§ ÙƒØ§Ù† NoneØŒ ÙŠÙØ±Ø³Ù„ Ø¥Ù„Ù‰ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ø£Ø¬Ù‡Ø²Ø©
    source: str = "system"
    timestamp: float = field(default_factory=time.time)
    read: bool = False
    acknowledged: bool = False
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass
class NotificationDelivery:
    """ØªØ³Ø¬ÙŠÙ„ ØªØ³Ù„ÙŠÙ… Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±."""
    notification_id: str
    device_id: str
    delivered: bool = False
    delivered_at: Optional[float] = None
    error: Optional[str] = None


class NotificationManager:
    """Ù…Ø¯ÙŠØ± Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª ÙÙŠ AETHRA."""

    def __init__(self) -> None:
        self._notifications: Dict[str, Notification] = {}
        self._deliveries: Dict[str, List[NotificationDelivery]] = {}  # notification_id -> deliveries
        self._device_subscribers: Dict[str, List[str]] = {}  # device_id -> notification_ids
        self._lock = __import__("threading").RLock()
        self._callbacks: List[Callable[[Notification], None]] = []

    def create_notification(
        self,
        type: NotificationType,
        title: str,
        message: str,
        priority: NotificationPriority = NotificationPriority.NORMAL,
        target_device_id: Optional[str] = None,
        source: str = "system",
        metadata: Optional[Dict[str, Any]] = None,
    ) -> str:
        """Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø± Ø¬Ø¯ÙŠØ¯."""
        notification_id = "notif_" + str(uuid.uuid4())[:12]
        notification = Notification(
            id=notification_id,
            type=type,
            title=title,
            message=message,
            priority=priority,
            target_device_id=target_device_id,
            source=source,
            metadata=metadata or {},
        )
        with self._lock:
            self._notifications[notification_id] = notification
            self._deliveries[notification_id] = []

        # Ø¥Ø´Ø¹Ø§Ø± Ø§Ù„Ø¯ÙˆØ§Ù„ Ø§Ù„Ù…Ø³Ø¬Ù„Ø©
        for cb in self._callbacks:
            try:
                cb(notification)
            except Exception:  # noqa: BLE001
                pass

        return notification_id

    def notify_task_completed(self, task_id: str, task_name: str,
                              target_device_id: Optional[str] = None) -> str:
        """Ø¥Ø´Ø¹Ø§Ø± Ø¨Ø¥ÙƒÙ…Ø§Ù„ Ù…Ù‡Ù…Ø©."""
        return self.create_notification(
            type=NotificationType.TASK_COMPLETED,
            title="ØªÙ… Ø¥ÙƒÙ…Ø§Ù„ Ø§Ù„Ù…Ù‡Ù…Ø©",
            message=f"Ø§Ù„Ù…Ù‡Ù…Ø© '{task_name}' ({task_id[:8]}) ØªÙ… Ø¥ÙƒÙ…Ø§Ù„Ù‡Ø§ Ø¨Ù†Ø¬Ø§Ø­.",
            priority=NotificationPriority.NORMAL,
            target_device_id=target_device_id,
            source="task_engine",
            metadata={"task_id": task_id, "task_name": task_name},
        )

    def notify_task_failed(self, task_id: str, task_name: str, error: str,
                           target_device_id: Optional[str] = None) -> str:
        """Ø¥Ø´Ø¹Ø§Ø± Ø¨ÙØ´Ù„ Ù…Ù‡Ù…Ø©."""
        return self.create_notification(
            type=NotificationType.TASK_FAILED,
            title="ÙØ´Ù„ ÙÙŠ Ø§Ù„Ù…Ù‡Ù…Ø©",
            message=f"Ø§Ù„Ù…Ù‡Ù…Ø© '{task_name}' ({task_id[:8]}) ÙØ´Ù„Øª: {error}",
            priority=NotificationPriority.HIGH,
            target_device_id=target_device_id,
            source="task_engine",
            metadata={"task_id": task_id, "task_name": task_name, "error": error},
        )

    def notify_task_requires_approval(self, task_id: str, task_name: str,
                                      target_device_id: Optional[str] = None) -> str:
        """Ø¥Ø´Ø¹Ø§Ø± Ø¨Ø£Ù† Ø§Ù„Ù…Ù‡Ù…Ø© ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø©."""
        return self.create_notification(
            type=NotificationType.TASK_REQUIRES_APPROVAL,
            title="Ø§Ù„Ù…Ù‡Ù…Ø© ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚Ø©",
            message=f"Ø§Ù„Ù…Ù‡Ù…Ø© '{task_name}' ({task_id[:8]}) ØªØªØ·Ù„Ø¨ Ù…ÙˆØ§ÙÙ‚ØªÙƒ Ù„Ù„ØªÙ†ÙÙŠØ°.",
            priority=NotificationPriority.URGENT,
            target_device_id=target_device_id,
            source="permission_broker",
            metadata={"task_id": task_id, "task_name": task_name},
        )

    def notify_system_alert(self, title: str, message: str,
                            priority: NotificationPriority = NotificationPriority.HIGH) -> str:
        """Ø¥Ø´Ø¹Ø§Ø± ØªÙ†Ø¨ÙŠÙ‡ Ù†Ø¸Ø§Ù…."""
        return self.create_notification(
            type=NotificationType.SYSTEM_ALERT,
            title=title,
            message=message,
            priority=priority,
            source="system_monitor",
        )

    def deliver_to_device(self, notification_id: str, device_id: str) -> bool:
        """ØªØ³Ù„ÙŠÙ… Ø¥Ø´Ø¹Ø§Ø± Ø¥Ù„Ù‰ Ø¬Ù‡Ø§Ø² Ù…Ø­Ø¯Ø¯."""
        with self._lock:
            notification = self._notifications.get(notification_id)
            if notification is None:
                return False

            delivery = NotificationDelivery(
                notification_id=notification_id,
                device_id=device_id,
                delivered=True,
                delivered_at=time.time(),
            )

            deliveries = self._deliveries.get(notification_id, [])
            deliveries.append(delivery)
            self._deliveries[notification_id] = deliveries

            # Ø¥Ø¶Ø§ÙØ© Ø¥Ù„Ù‰ Ø§Ù„Ù…Ø´ØªØ±ÙƒÙŠÙ† ÙÙŠ Ø§Ù„Ø¬Ù‡Ø§Ø²
            device_notifs = self._device_subscribers.get(device_id, [])
            if notification_id not in device_notifs:
                device_notifs.append(notification_id)
                self._device_subscribers[device_id] = device_notifs

            return True

    def get_notifications(self, device_id: Optional[str] = None,
                          unread_only: bool = False) -> List[Notification]:
        """Ø§Ù„Ø­ØµÙˆÙ„ Ø¹Ù„Ù‰ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª."""
        with self._lock:
            notifications = list(self._notifications.values())

        if device_id:
            # Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ù…ÙˆØ¬Ù‡Ø© Ù„Ù‡Ø°Ø§ Ø§Ù„Ø¬Ù‡Ø§Ø² Ø£Ùˆ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø§Ù„Ø¹Ø§Ù…Ø©
            device_notif_ids = self._device_subscribers.get(device_id, [])
            notifications = [n for n in notifications
                             if n.target_device_id is None
                             or n.target_device_id == device_id
                             or n.id in device_notif_ids]

        if unread_only:
            notifications = [n for n in notifications if not n.read]

        notifications.sort(key=lambda n: n.timestamp, reverse=True)
        return notifications

    def mark_read(self, notification_id: str) -> bool:
        """ØªØ­Ø¯ÙŠØ¯ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø± ÙƒÙ…Ù‚Ø±ÙˆØ¡."""
        with self._lock:
            notification = self._notifications.get(notification_id)
            if notification:
                notification.read = True
                return True
            return False

    def mark_acknowledged(self, notification_id: str) -> bool:
        """ØªØ­Ø¯ÙŠØ¯ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø± ÙƒÙ…Ø¹ØªÙ…Ø¯."""
        with self._lock:
            notification = self._notifications.get(notification_id)
            if notification:
                notification.acknowledged = True
                return True
            return False

    def register_callback(self, callback: Callable[[Notification], None]) -> None:
        """ØªØ³Ø¬ÙŠÙ„ Ø¯Ø§Ù„Ø© Ø§Ø³ØªØ¯Ø¹Ø§Ø¡ Ø¹Ù†Ø¯ Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø± Ø¬Ø¯ÙŠØ¯."""
        self._callbacks.append(callback)

    def get_delivery_status(self, notification_id: str) -> List[NotificationDelivery]:
        """Ø§Ù„Ø­ØµÙˆÙ„ Ø¹Ù„Ù‰ Ø­Ø§Ù„Ø© ØªØ³Ù„ÙŠÙ… Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±."""
        with self._lock:
            return self._deliveries.get(notification_id, [])

    def get_stats(self) -> Dict[str, Any]:
        """Ø¥Ø­ØµØ§Ø¦ÙŠØ§Øª Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª."""
        with self._lock:
            total = len(self._notifications)
            unread = sum(1 for n in self._notifications.values() if not n.read)
            by_type = {}
            for n in self._notifications.values():
                by_type[n.type.value] = by_type.get(n.type.value, 0) + 1
            by_priority = {}
            for n in self._notifications.values():
                by_priority[n.priority.value] = by_priority.get(n.priority.value, 0) + 1
            return {
                "total": total,
                "unread": unread,
                "by_type": by_type,
                "by_priority": by_priority,
                "devices_subscribed": len(self._device_subscribers),
            }


# instance Ø¹Ø§Ù…Ø©
notification_manager = NotificationManager()
