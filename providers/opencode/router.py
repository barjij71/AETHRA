"""ProviderModelRouter - routage entre providers de modeles."""

from __future__ import annotations
from typing import Any, Dict, List, Optional

from core.registry import core_registry


class ProviderModelRouter:
    """Route les requetes vers le provider de modele approprie."""

    def __init__(self) -> None:
        self._providers: Dict[str, Any] = {}
        self._default_provider: Optional[str] = None
        self._model_preferences: Dict[str, str] = {}  # model_name -> provider_id

    def register(self, provider_id: str, provider: Any, is_default: bool = False) -> None:
        self._providers[provider_id] = provider
        if is_default or self._default_provider is None:
            self._default_provider = provider_id

    def route(self, model_name: Optional[str] = None, provider_id: Optional[str] = None) -> Any:
        if provider_id and provider_id in self._providers:
            return self._providers[provider_id]
        if model_name and model_name in self._model_preferences:
            pid = self._model_preferences[model_name]
            if pid in self._providers:
                return self._providers[pid]
        if self._default_provider:
            return self._providers[self._default_provider]
        raise RuntimeError("Aucun provider disponible")

    def set_preference(self, model_name: str, provider_id: str) -> None:
        self._model_preferences[model_name] = provider_id

    def list_providers(self) -> List[str]:
        return list(self._providers.keys())


router = ProviderModelRouter()
