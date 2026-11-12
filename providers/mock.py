"""Mock Provider Ù„Ù„Ø§Ø®ØªØ¨Ø§Ø± Ø¯ÙˆÙ† Ø§Ù„Ø­Ø§Ø¬Ø© Ø¥Ù„Ù‰ API keys Ø­Ù‚ÙŠÙ‚ÙŠØ©."""

from __future__ import annotations
import time
from typing import Any, Dict, List, Optional

from providers.base import Provider, ModelInfo, ProviderRequest, ProviderResponse


class MockProvider(Provider):
    """Ù…Ø²ÙˆØ¯ ÙˆÙ‡Ù…ÙŠ Ù„Ù„Ø§Ø®ØªØ¨Ø§Ø± ÙˆØ§Ù„ØªØ·ÙˆÙŠØ±."""

    def __init__(self, provider_id: str = "mock", name: str = "Mock Provider",
                 should_fail: bool = False, response_delay: float = 0.0) -> None:
        super().__init__(provider_id, name)
        self.available = True
        self.should_fail = should_fail
        self.response_delay = response_delay
        self.call_count = 0
        self.last_request: Optional[ProviderRequest] = None
        self.models = [
            ModelInfo(id="mock-model", name="Mock Model", provider=provider_id, capabilities=["chat"]),
        ]

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        self.call_count += 1
        self.last_request = request

        if self.response_delay > 0:
            time.sleep(self.response_delay)

        if self.should_fail:
            raise RuntimeError("Mock provider simulated failure")

        # Ø¨Ù†Ø§Ø¡ Ø§Ø³ØªØ¬Ø§Ø¨Ø© ÙˆÙ‡Ù…ÙŠØ© Ø¨Ù†Ø§Ø¡Ù‹ Ø¹Ù„Ù‰ Ù…Ø­ØªÙˆÙ‰ Ø§Ù„Ø·Ù„Ø¨
        user_messages = [m for m in request.messages if m.get("role") == "user"]
        user_content = user_messages[-1].get("content", "") if user_messages else ""

        if "Ù…Ø±Ø­Ø¨Ø§Ù‹" in user_content or "hello" in user_content.lower():
            content = "Ù…Ø±Ø­Ø¨Ø§Ù‹! Ø£Ù†Ø§ Ø¥ÙŠØ«Ø±Ø§ØŒ Ù…Ø³Ø§Ø¹Ø¯Ùƒ Ø§Ù„Ø°ÙƒÙŠ. ÙƒÙŠÙ ÙŠÙ…ÙƒÙ†Ù†ÙŠ Ù…Ø³Ø§Ø¹Ø¯ØªÙƒ Ø§Ù„ÙŠÙˆÙ…ØŸ"
        elif "Ù…Ù†" in user_content or "who" in user_content.lower():
            content = "Ø£Ù†Ø§ Ø¥ÙŠØ«Ø±Ø§ØŒ Ù†Ø¸Ø§Ù… Ù…Ø³Ø§Ø¹Ø¯ Ø°ÙƒÙŠ Ù…Ø³ØªÙ‚Ù„ ÙŠØ¹Ù…Ù„ Ø¹Ù„Ù‰ Core Ø®Ø§Øµ Ø¨Ù‡."
        elif "Ø­Ø³Ø§Ø¨" in user_content or "calculate" in user_content.lower() or "Ø­Ø§Ø³Ø¨" in user_content:
            content = "ÙŠÙ…ÙƒÙ†Ù†ÙŠ Ù…Ø³Ø§Ø¹Ø¯ØªÙƒ ÙÙŠ Ø§Ù„Ø¹Ù…Ù„ÙŠØ§Øª Ø§Ù„Ø­Ø³Ø§Ø¨ÙŠØ©. Ù…Ø§Ø°Ø§ ØªØ±ÙŠØ¯ Ø£Ù† ØªØ­Ø³Ø¨ØŸ"
        else:
            content = "ØªÙ„Ù‚ÙŠØª Ø·Ù„Ø¨Ùƒ: " + user_content[:100] + "... Ø³Ø£Ù‚ÙˆÙ… Ø¨Ù…Ø¹Ø§Ù„Ø¬ØªÙ‡."

        return ProviderResponse(
            content=content,
            model=request.model,
            provider=self.provider_id,
            usage={"prompt_tokens": 10, "completion_tokens": 20, "total_tokens": 30},
            latency_seconds=0.05,
            metadata={"mock": True},
        )

    def list_models(self) -> List[ModelInfo]:
        return self.models
