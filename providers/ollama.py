"""Ù…Ø²ÙˆØ¯ Ollama Ù„Ù„Ù†Ù…Ø§Ø°Ø¬ Ø§Ù„Ù…Ø­Ù„ÙŠØ©."""

from __future__ import annotations
import json
import time
import urllib.request
from typing import Any, Dict, List, Optional

from providers.base import Provider, ModelInfo, ProviderRequest, ProviderResponse


class OllamaProvider(Provider):
    """Ù…Ø²ÙˆØ¯ Ollama ÙŠØ¹Ù…Ù„ Ù…Ø­Ù„ÙŠØ§Ù‹."""

    def __init__(self, base_url: str = "http://localhost:11434") -> None:
        super().__init__("ollama", "Ollama")
        self.base_url = base_url.rstrip("/")
        self.available = self._check_available()

    def _check_available(self) -> bool:
        try:
            req = urllib.request.Request(self.base_url + "/api/tags")
            with urllib.request.urlopen(req, timeout=5) as resp:
                if resp.status == 200:
                    data = json.loads(resp.read().decode())
                    self.models = [
                        ModelInfo(
                            id=m.get("name", ""),
                            name=m.get("name", ""),
                            provider="ollama",
                            capabilities=["chat"],
                        )
                        for m in data.get("models", [])
                    ]
                    return True
        except Exception:
            pass
        return False

    def complete(self, request: ProviderRequest) -> ProviderResponse:
        start = time.time()
        payload = {
            "model": request.model,
            "messages": request.messages,
            "temperature": request.temperature,
            "stream": False,
        }
        if request.max_tokens:
            payload["options"] = {"num_predict": request.max_tokens}

        data = json.dumps(payload).encode("utf-8")
        req = urllib.request.Request(
            self.base_url + "/api/chat",
            data=data,
            headers={"Content-Type": "application/json"},
            method="POST",
        )
        with urllib.request.urlopen(req, timeout=120) as resp:
            result = json.loads(resp.read().decode())
        content = result.get("message", {}).get("content", "")

        return ProviderResponse(
            content=content,
            model=request.model,
            provider="ollama",
            usage={},
            latency_seconds=time.time() - start,
        )
