"""HolographicPersona - persona holographique adaptative d AETHRA.

Holoforme : feminine, futuristic, interactive, adaptive.
Ã‰tats : IDLE, LISTENING, THINKING, EXECUTING, WAITING_APPROVAL, SPEAKING, SUCCESS, ERROR.
Les effets sont adaptatifs selon la puissance de l appareil.
"""

from __future__ import annotations
import enum
from dataclasses import dataclass, field
from typing import Optional


class PersonaState(enum.Enum):
    IDLE = "IDLE"
    LISTENING = "LISTENING"
    THINKING = "THINKING"
    EXECUTING = "EXECUTING"
    WAITING_APPROVAL = "WAITING_APPROVAL"
    SPEAKING = "SPEAKING"
    SUCCESS = "SUCCESS"
    ERROR = "ERROR"


class EffectLevel(enum.Enum):
    FULL = "FULL"
    REDUCED = "REDUCED"
    MINIMAL = "MINIMAL"


@dataclass
class HolographicPersona:
    """Persona holographique d AETHRA."""
    name: str = "AETHRA"
    state: PersonaState = PersonaState.IDLE
    effect_level: EffectLevel = EffectLevel.FULL
    adaptive: bool = True
    metadata: dict = field(default_factory=dict)

    def set_state(self, new_state: PersonaState) -> None:
        self.state = new_state

    def set_effect_level(self, level: EffectLevel) -> None:
        self.effect_level = level

    def adapt_to_performance(self, cpu_percent: float, mem_percent: float,
                             battery_low: bool = False) -> None:
        """Ajuste les effets selon la performance de l appareil."""
        if not self.adaptive:
            return
        if battery_low or cpu_percent > 90 or mem_percent > 90:
            self.effect_level = EffectLevel.MINIMAL
        elif cpu_percent > 70 or mem_percent > 70:
            self.effect_level = EffectLevel.REDUCED
        else:
            self.effect_level = EffectLevel.FULL

    def should_show_effects(self) -> bool:
        return self.effect_level != EffectLevel.MINIMAL


persona = HolographicPersona()
