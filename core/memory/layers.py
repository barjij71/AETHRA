"""Ø·Ø¨Ù‚Ø§Øª Ø§Ù„Ø°Ø§ÙƒØ±Ø© ÙÙŠ AETHRA."""

from __future__ import annotations
import enum


class MemoryLayer(enum.Enum):
    """Ø·Ø¨Ù‚Ø§Øª Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ù…Ø¯Ø¹ÙˆÙ…Ø©."""
    CONVERSATION = "conversation"
    WORKING = "working"
    TASK = "task"
    LONG_TERM = "long_term"
    PREFERENCE = "preference"
    DEVICE = "device"
    SKILL = "skill"
    KNOWLEDGE = "knowledge"

    @classmethod
    def all_layers(cls) -> list[str]:
        return [l.value for l in cls]
