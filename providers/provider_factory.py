"""Ù…ØµÙ†Ø¹ Ø§Ù„Ù…ÙˆÙØ±Ø§Øª - ÙŠÙ†Ø´Ø¦ Ø§Ù„Ù…ÙˆÙØ±Ø§Øª Ø¨Ù†Ø§Ø¡Ù‹ Ø¹Ù„Ù‰ Ø§Ù„ØªÙƒÙˆÙŠÙ†."""

from __future__ import annotations
from typing import Any, Dict, Optional

from providers.base import Provider
from providers.mock import MockProvider
from providers.ollama import OllamaProvider
from providers.openai import OpenAIProvider
from providers.anthropic import AnthropicProvider
from providers.gemini import GeminiProvider
from providers.openrouter import OpenRouterProvider


class ProviderFactory:
    """ÙŠÙ†Ø´Ø¦ ÙˆÙŠØ¹ÙŠØ¯ Ù…Ø«ÙŠÙ„Ø§Øª Ø§Ù„Ù…ÙˆÙØ±Ø§Øª."""

    _providers: Dict[str, Any] = {}

    @classmethod
    def create(cls, provider_id: str, **kwargs) -> Provider:
        if provider_id == "mock":
            return MockProvider(**kwargs)
        elif provider_id == "ollama":
            return OllamaProvider(**kwargs)
        elif provider_id == "openai":
            return OpenAIProvider(**kwargs)
        elif provider_id == "anthropic":
            return AnthropicProvider(**kwargs)
        elif provider_id == "gemini":
            return GeminiProvider(**kwargs)
        elif provider_id == "openrouter":
            return OpenRouterProvider(**kwargs)
        else:
            raise ValueError("Ù…Ø²ÙˆØ¯ ØºÙŠØ± Ù…Ø¹Ø±ÙˆÙ: " + provider_id)

    @classmethod
    def register_custom(cls, provider_id: str, provider_class: Any) -> None:
        cls._providers[provider_id] = provider_class

    @classmethod
    def create_custom(cls, provider_id: str, **kwargs) -> Provider:
        if provider_id in cls._providers:
            return cls._providers[provider_id](**kwargs)
        raise ValueError("Ù…Ø²ÙˆØ¯ Ù…Ø®ØµØµ ØºÙŠØ± Ù…Ø³Ø¬Ù„: " + provider_id)
