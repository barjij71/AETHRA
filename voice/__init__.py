"""Voice system - Ù†Ø¸Ø§Ù… Ø§Ù„ØµÙˆØª ÙÙŠ AETHRA.

Ø§Ù„Ø¨Ù†ÙŠØ©:
Wake Word -> VAD -> STT -> AETHRA Intent -> TaskEngine -> Provider/Agent -> Response -> TTS -> Avatar Synchronization

ÙŠØ¬Ø¨ Ø£Ù† ØªÙƒÙˆÙ† STT/TTS/Wake Word Ù‚Ø§Ø¨Ù„Ø© Ù„Ù„Ø§Ø³ØªØ¨Ø¯Ø§Ù„ØŒ Ù…Ø¹ Ø¯Ø¹Ù… local/streaming engines.
Ù„Ø§ ØªØ±Ø¨Ø· Core Ø¨Ù…Ø­Ø±Ùƒ ØµÙˆØª ÙˆØ§Ø­Ø¯.
"""

from voice.stt import STTEngine, STTResult
from voice.tts import TTSEngine, TTSResult
from voice.wake_word import WakeWordEngine, WakeWordEvent

__all__ = [
    "STTEngine", "STTResult",
    "TTSEngine", "TTSResult",
    "WakeWordEngine", "WakeWordEvent",
]
