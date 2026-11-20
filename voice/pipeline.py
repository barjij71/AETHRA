"""Voice Pipeline Ù…ØªÙƒØ§Ù…Ù„.

Ø§Ù„Ù…Ø³Ø§Ø±:
Wake Word -> VAD -> STT -> AETHRA Intent -> TaskEngine -> Provider/Agent -> Response -> TTS -> Avatar Synchronization

ÙƒÙ„ Ù…ÙƒÙˆÙ† Ù‚Ø§Ø¨Ù„ Ù„Ù„Ø§Ø³ØªØ¨Ø¯Ø§Ù„ØŒ ÙˆÙ„Ø§ ÙŠÙØ±Ø¨Ø· Core Ø¨Ù…Ø­Ø±Ùƒ ØµÙˆØª ÙˆØ§Ø­Ø¯.
"""

from __future__ import annotations
import time
import threading
from dataclasses import dataclass, field
from typing import Any, Callable, Dict, List, Optional

from core.task import TaskEngine, TaskStatus
from core.integration import aethra
from core.memory import MemoryEngine
from core.policy import PermissionBroker, RiskLevel
from voice.stt import STTEngine, STTResult
from voice.tts import TTSEngine, TTSResult
from voice.wake_word import WakeWordEngine, WakeWordEvent
from providers.registry import ProviderRegistry
from providers.mock import MockProvider


@dataclass
class VoiceSession:
    """Ø¬Ù„Ø³Ø© ØµÙˆØªÙŠØ© ÙˆØ§Ø­Ø¯Ø©."""
    session_id: str
    started_at: float
    last_activity: float
    is_active: bool = True
    wake_word_detected: bool = False
    transcript: str = ""
    response_text: str = ""
    audio_data: Optional[bytes] = None


@dataclass
class VoicePipelineResult:
    """Ù†ØªÙŠØ¬Ø© Ù…Ø³Ø§Ø± Ø§Ù„ØµÙˆØª Ø§Ù„ÙƒØ§Ù…Ù„."""
    session_id: str
    wake_word: bool
    transcript: str
    intent: str
    response: str
    success: bool
    error: Optional[str] = None
    duration_seconds: float = 0.0
    metadata: Dict[str, Any] = field(default_factory=dict)


class VoicePipeline:
    """Ù…Ø³Ø§Ø± Ø§Ù„ØµÙˆØª Ø§Ù„Ù…ØªÙƒØ§Ù…Ù„ Ù„Ù€ AETHRA."""

    def __init__(
        self,
        stt_engine: Optional[STTEngine] = None,
        tts_engine: Optional[TTSEngine] = None,
        wake_word_engine: Optional[WakeWordEngine] = None,
        task_engine: Optional[TaskEngine] = None,
        memory_engine: Optional[MemoryEngine] = None,
        provider_registry: Optional[ProviderRegistry] = None,
    ) -> None:
        self.stt_engine = stt_engine or STTEngine("mock")
        self.tts_engine = tts_engine or TTSEngine("mock")
        self.wake_word_engine = wake_word_engine or WakeWordEngine("Ù…Ø±Ø­Ø¨Ø§ Ø¥ÙŠØ«Ø±Ø§")
        self.task_engine = task_engine or aethra.task_engine
        self.memory_engine = memory_engine or aethra.memory_engine
        self.provider_registry = provider_registry or ProviderRegistry()

        from providers.mock import MockProvider
        self.provider_registry.register(MockProvider(), is_default=True)

        self._sessions: Dict[str, VoiceSession] = {}
        self._lock = threading.RLock()
        self._wake_callbacks: List[Callable[[WakeWordEvent], None]] = []

        self.wake_word_engine.set_callback(self._on_wake_word)

    def _on_wake_word(self, event: WakeWordEvent) -> None:
        session_id = "voice_" + str(int(time.time() * 1000))
        session = VoiceSession(
            session_id=session_id,
            started_at=time.time(),
            last_activity=time.time(),
            wake_word_detected=True,
        )
        with self._lock:
            self._sessions[session_id] = session
        for cb in self._wake_callbacks:
            cb(event)

    def register_wake_callback(self, callback: Callable[[WakeWordEvent], None]) -> None:
        with self._lock:
            self._wake_callbacks.append(callback)

    def process_voice_input(self, audio_data: bytes, language: str = "ar") -> VoicePipelineResult:
        start = time.time()
        session_id = "voice_" + str(int(time.time() * 1000))

        try:
            wake_detected = True

            stt_result = self.stt_engine.transcribe(audio_data, language)
            transcript = stt_result.text

            self.memory_engine.write(
                layer="conversation",
                key="voice:" + session_id + ":transcript",
                value={"text": transcript, "language": language, "confidence": stt_result.confidence},
                source="voice_pipeline",
                relevance=0.9,
                confidence=stt_result.confidence,
                privacy_level="internal",
            )

            intent_task = self.task_engine.create_task(
                "voice_intent:" + transcript[:50],
                "default",
                metadata={"transcript": transcript, "language": language, "source": "voice", "actor": "voice_user"},
            )
            intent_task_obj = self.task_engine.get_task(intent_task)

            provider = self.provider_registry.route()
            from providers.base import ProviderRequest
            req = ProviderRequest(
                model="mock-model",
                messages=[
                    {"role": "system", "content": "Ø£Ù†Øª Ø¥ÙŠØ«Ø±Ø§ØŒ Ù…Ø³Ø§Ø¹Ø¯ Ø°ÙƒÙŠ Ù…Ø³ØªÙ‚Ù„. Ø£Ø¬Ø¨ Ø¨Ø§Ù„Ø¹Ø±Ø¨ÙŠØ© Ø¨Ø´ÙƒÙ„ Ù…Ø®ØªØµØ± ÙˆÙ…ÙÙŠØ¯."},
                    {"role": "user", "content": transcript},
                ],
                temperature=0.7,
            )
            response = provider.complete(req)
            response_text = response.content

            self.memory_engine.write(
                layer="conversation",
                key="voice:" + session_id + ":response",
                value={"text": response_text, "provider": response.provider, "latency": response.latency_seconds},
                source="voice_pipeline",
                relevance=0.9,
                confidence=1.0,
                privacy_level="internal",
            )

            tts_result = self.tts_engine.synthesize(response_text)

            intent_task_obj.mark_completed(response_text)

            voice_session = VoiceSession(
                session_id=session_id,
                started_at=start,
                last_activity=time.time(),
                wake_word_detected=wake_detected,
                transcript=transcript,
                response_text=response_text,
            )
            with self._lock:
                self._sessions[session_id] = voice_session

            duration = time.time() - start

            return VoicePipelineResult(
                session_id=session_id,
                wake_word=wake_detected,
                transcript=transcript,
                intent="voice_command",
                response=response_text,
                success=True,
                duration_seconds=duration,
                metadata={
                    "stt_confidence": stt_result.confidence,
                    "provider": response.provider,
                    "tts_duration": tts_result.duration_seconds,
                },
            )

        except Exception as exc:
            duration = time.time() - start
            return VoicePipelineResult(
                session_id=session_id,
                wake_word=False,
                transcript="",
                intent="",
                response="",
                success=False,
                error=str(exc),
                duration_seconds=duration,
            )

    def process_text_input(self, text: str, language: str = "ar") -> VoicePipelineResult:
        start = time.time()
        session_id = "text_" + str(int(time.time() * 1000))

        try:
            self.memory_engine.write(
                layer="conversation",
                key="text:" + session_id + ":input",
                value={"text": text, "language": language},
                source="voice_pipeline",
                relevance=0.9,
                confidence=1.0,
                privacy_level="internal",
            )

            intent_task = self.task_engine.create_task(
                "text_intent:" + text[:50],
                "default",
                metadata={"text": text, "language": language, "source": "text", "actor": "text_user"},
            )
            intent_task_obj = self.task_engine.get_task(intent_task)

            provider = self.provider_registry.route()
            from providers.base import ProviderRequest
            req = ProviderRequest(
                model="mock-model",
                messages=[
                    {"role": "system", "content": "Ø£Ù†Øª Ø¥ÙŠØ«Ø±Ø§ØŒ Ù…Ø³Ø§Ø¹Ø¯ Ø°ÙƒÙŠ Ù…Ø³ØªÙ‚Ù„. Ø£Ø¬Ø¨ Ø¨Ø§Ù„Ø¹Ø±Ø¨ÙŠØ© Ø¨Ø´ÙƒÙ„ Ù…Ø®ØªØµØ± ÙˆÙ…ÙÙŠØ¯."},
                    {"role": "user", "content": text},
                ],
                temperature=0.7,
            )
            response = provider.complete(req)
            response_text = response.content

            self.memory_engine.write(
                layer="conversation",
                key="text:" + session_id + ":response",
                value={"text": response_text, "provider": response.provider},
                source="voice_pipeline",
                relevance=0.9,
                confidence=1.0,
                privacy_level="internal",
            )

            intent_task_obj.mark_completed(response_text)

            text_session = VoiceSession(
                session_id=session_id,
                started_at=start,
                last_activity=time.time(),
                wake_word_detected=False,
                transcript=text,
                response_text=response_text,
            )
            with self._lock:
                self._sessions[session_id] = text_session

            duration = time.time() - start

            return VoicePipelineResult(
                session_id=session_id,
                wake_word=False,
                transcript=text,
                intent="text_command",
                response=response_text,
                success=True,
                duration_seconds=duration,
            )

        except Exception as exc:
            duration = time.time() - start
            return VoicePipelineResult(
                session_id=session_id,
                wake_word=False,
                transcript=text,
                intent="",
                response="",
                success=False,
                error=str(exc),
                duration_seconds=duration,
            )

    def get_session(self, session_id: str) -> Optional[VoiceSession]:
        with self._lock:
            return self._sessions.get(session_id)

    def list_sessions(self) -> List[VoiceSession]:
        with self._lock:
            return list(self._sessions.values())


voice_pipeline = VoicePipeline()
