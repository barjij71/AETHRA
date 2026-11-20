"""ØªÙƒØ§Ù…Ù„ Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª Ù…Ø¹ Core Ùˆ TaskEngine.

ÙŠØ³Ù…Ø­ Ø¨Ø±Ø¨Ø· Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª Ø¨Ø§Ù„Ù…Ù‡Ø§Ù… ÙˆØªØ¶Ù…ÙŠÙ† Ù…Ø­ØªÙˆØ§Ù‡Ø§ ÙÙŠ Ø³ÙŠØ§Ù‚ Ø§Ù„Ø§Ø³ØªØ¯Ù„Ø§Ù„.
"""

from __future__ import annotations
from typing import Any, Dict, List, Optional

from core.memory import MemoryEngine
from core.task import Task, TaskEngine
from tools.attachment import Attachment, AttachmentManager


class AttachmentIntegration:
    """ÙŠØ±Ø¨Ø· Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª Ø¨Ø§Ù„Ø°Ø§ÙƒØ±Ø© ÙˆØ§Ù„Ù…Ù‡Ø§Ù…."""

    def __init__(
        self,
        attachment_manager: AttachmentManager,
        memory_engine: MemoryEngine,
        task_engine: TaskEngine,
    ) -> None:
        self.attachment_manager = attachment_manager
        self.memory_engine = memory_engine
        self.task_engine = task_engine

    def attach_to_task(self, attachment_id: str, task_id: str) -> bool:
        """ÙŠØ±Ø¨Ø· Ù…Ø±ÙÙ‚ Ø¨Ù…Ù‡Ù…Ø© ÙˆÙŠØ³Ø¬Ù„Ù‡ ÙÙŠ Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ù‡Ù…Ø©."""
        att = self.attachment_manager.get(attachment_id)
        if not att:
            return False

        task = self.task_engine.get_task(task_id)
        if not task:
            return False

        # Ø±Ø¨Ø· Ø§Ù„Ù…Ø±ÙÙ‚ Ø¨Ø§Ù„Ù…Ù‡Ù…Ø©
        task.metadata.setdefault("attachments", [])
        if attachment_id not in task.metadata["attachments"]:
            task.metadata["attachments"].append(attachment_id)

        # ØªØ³Ø¬ÙŠÙ„ ÙÙŠ Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ù‡Ù…Ø©
        self.memory_engine.write(
            layer="task",
            key="attachment:" + attachment_id,
            value=att.to_dict(),
            source="attachment_manager",
            relevance=0.9,
            confidence=1.0,
            privacy_level="internal",
        )
        return True

    def get_attachment_context(self, attachment_id: str) -> Optional[str]:
        """ÙŠØ¹ÙŠØ¯ Ø³ÙŠØ§Ù‚ Ø§Ù„Ù…Ø±ÙÙ‚ Ù„Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù… ÙÙŠ Ø§Ù„Ø§Ø³ØªØ¯Ù„Ø§Ù„."""
        att = self.attachment_manager.get(attachment_id)
        if not att:
            return None
        return self.attachment_manager.get_context_summary(attachment_id)

    def list_task_attachments(self, task_id: str) -> List[Attachment]:
        """ÙŠØ¹ÙŠØ¯ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª Ø§Ù„Ù…Ø±ØªØ¨Ø·Ø© Ø¨Ù…Ù‡Ù…Ø©."""
        task = self.task_engine.get_task(task_id)
        if not task:
            return []
        ids = task.metadata.get("attachments", [])
        return [self.attachment_manager.get(aid) for aid in ids if self.attachment_manager.get(aid)]
