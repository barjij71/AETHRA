"""Ù†Ø¸Ø§Ù… Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª ÙÙŠ AETHRA.

ÙŠØ¯Ø¹Ù… Ø§Ù„Ø¯Ø±Ø¯Ø´Ø©:
PDF / TXT / Markdown / JSON / CSV / Images / ZIP / APK / Source Code / Documents

Ø§Ù„Ù…Ø³Ø§Ø±:
Attachment -> Metadata -> Validation -> Safe Parsing -> Context Extraction -> AI/Skill Usage

Ù„Ø§ ÙŠØ¹ØªØ¨Ø± Ø§Ù„Ù…Ù„Ù Ø¢Ù…Ù†Ø§ Ù„Ø§Ø¹ØªÙ…Ø§Ø¯Ù‡ Ø¹Ù„Ù‰ Ø§Ù„Ø§Ù…ØªØ¯Ø§Ø¯ ÙÙ‚Ø·.
"""

from __future__ import annotations
import enum
import hashlib
import mimetypes
import os
import time
from dataclasses import dataclass, field
from typing import Any, Dict, List, Optional


class AttachmentType(enum.Enum):
    """Ø£Ù†ÙˆØ§Ø¹ Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª Ø§Ù„Ù…Ø¯Ø¹ÙˆÙ…Ø©."""
    TEXT = "text"
    MARKDOWN = "markdown"
    PDF = "pdf"
    JSON = "json"
    CSV = "csv"
    IMAGE = "image"
    ZIP = "zip"
    APK = "apk"
    SOURCE_CODE = "source_code"
    DOCUMENT = "document"
    UNKNOWN = "unknown"


@dataclass
class Attachment:
    """Ù…Ø±ÙÙ‚ Ù…Ø¹ metadata ÙˆØ§Ù„ØªØ­Ù‚Ù‚."""
    id: str
    filename: str
    file_path: str
    file_size: int
    mime_type: str
    attachment_type: AttachmentType
    sha256: str
    uploaded_at: float
    uploaded_by: str
    is_safe: bool = False
    validation_errors: List[str] = field(default_factory=list)
    extracted_content: Optional[str] = None
    context_summary: Optional[str] = None
    metadata: Dict[str, Any] = field(default_factory=dict)

    def to_dict(self) -> Dict[str, Any]:
        return {
            "id": self.id,
            "filename": self.filename,
            "file_size": self.file_size,
            "mime_type": self.mime_type,
            "attachment_type": self.attachment_type.value,
            "sha256": self.sha256,
            "uploaded_at": self.uploaded_at,
            "uploaded_by": self.uploaded_by,
            "is_safe": self.is_safe,
            "validation_errors": self.validation_errors,
            "context_summary": self.context_summary,
        }


class AttachmentManager:
    """ÙŠØ¯ÙŠØ± Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª: Ø§Ù„ØªØ­Ù‚Ù‚ØŒ Ø§Ù„ØªØ­Ù„ÙŠÙ„ Ø§Ù„Ø¢Ù…Ù†ØŒ Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ø§Ù„Ø³ÙŠØ§Ù‚."""

    def __init__(self, max_file_size_mb: int = 50) -> None:
        self.max_file_size_mb = max_file_size_mb
        self._attachments: Dict[str, Attachment] = {}
        self._lock = __import__("threading").RLock()

    def register_attachment(
        self,
        file_path: str,
        uploaded_by: str,
        filename: Optional[str] = None,
    ) -> Attachment:
        """ÙŠØ³Ø¬Ù„ Ù…Ø±ÙÙ‚ Ø¬Ø¯ÙŠØ¯ ÙˆÙŠØªØ­Ù‚Ù‚ Ù…Ù†Ù‡."""
        if not os.path.exists(file_path):
            raise FileNotFoundError("File not found: " + file_path)

        file_size = os.path.getsize(file_path)
        max_bytes = self.max_file_size_mb * 1024 * 1024
        if file_size > max_bytes:
            raise ValueError("File size exceeds limit (%d MB)" % self.max_file_size_mb)

        sha256 = self._compute_sha256(file_path)

        mime_type, _ = mimetypes.guess_type(file_path)
        mime_type = mime_type or "application/octet-stream"
        attachment_type = self._detect_type(mime_type, file_path)

        attachment_id = "att_" + hashlib.sha256((file_path + str(time.time())).encode()).hexdigest()[:16]
        filename = filename or os.path.basename(file_path)

        attachment = Attachment(
            id=attachment_id,
            filename=filename,
            file_path=file_path,
            file_size=file_size,
            mime_type=mime_type,
            attachment_type=attachment_type,
            sha256=sha256,
            uploaded_at=time.time(),
            uploaded_by=uploaded_by,
        )

        self._validate_safety(attachment)

        with self._lock:
            self._attachments[attachment_id] = attachment

        return attachment

    def get(self, attachment_id: str) -> Optional[Attachment]:
        with self._lock:
            return self._attachments.get(attachment_id)

    def list(self) -> List[Attachment]:
        with self._lock:
            return list(self._attachments.values())

    def delete(self, attachment_id: str) -> bool:
        with self._lock:
            att = self._attachments.pop(attachment_id, None)
            if att:
                try:
                    os.remove(att.file_path)
                except Exception:
                    pass
                return True
            return False

    def extract_content(self, attachment_id: str) -> Optional[str]:
        """ÙŠØ³ØªØ®Ø±Ø¬ Ù…Ø­ØªÙˆÙ‰ Ù†ØµÙŠ Ù…Ù† Ø§Ù„Ù…Ø±ÙÙ‚ Ù„Ù„Ø§Ø³ØªØ®Ø¯Ø§Ù… ÙÙŠ Ø§Ù„Ø³ÙŠØ§Ù‚."""
        att = self.get(attachment_id)
        if not att or not att.is_safe:
            return None
        if att.extracted_content:
            return att.extracted_content

        content = self._parse_file(att)
        att.extracted_content = content
        return content

    def _compute_sha256(self, file_path: str) -> str:
        h = hashlib.sha256()
        with open(file_path, "rb") as f:
            for chunk in iter(lambda: f.read(8192), b""):
                h.update(chunk)
        return h.hexdigest()

    def _detect_type(self, mime_type: str, file_path: str) -> AttachmentType:
        """ÙŠØ­Ø¯Ø¯ Ù†ÙˆØ¹ Ø§Ù„Ù…Ø±ÙÙ‚ Ø¨Ù†Ø§Ø¡Ù‹ Ø¹Ù„Ù‰ MIME type ÙˆÙ„ÙŠØ³ Ø§Ù„Ø§Ù…ØªØ¯Ø§Ø¯ ÙÙ‚Ø·."""
        if mime_type.startswith("text/"):
            if file_path.endswith(".md"):
                return AttachmentType.MARKDOWN
            if file_path.endswith(".csv"):
                return AttachmentType.CSV
            return AttachmentType.TEXT
        if mime_type == "application/json":
            return AttachmentType.JSON
        if mime_type == "application/pdf":
            return AttachmentType.PDF
        if mime_type.startswith("image/"):
            return AttachmentType.IMAGE
        if mime_type == "application/zip" or file_path.endswith(".zip"):
            return AttachmentType.ZIP
        if mime_type == "application/vnd.android.package-archive" or file_path.endswith(".apk"):
            return AttachmentType.APK
        if mime_type in ("text/x-python", "text/x-java", "text/javascript", "text/x-c", "text/x-cpp"):
            return AttachmentType.SOURCE_CODE
        if mime_type in ("application/msword", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"):
            return AttachmentType.DOCUMENT
        return AttachmentType.UNKNOWN

    def _validate_safety(self, attachment: Attachment) -> None:
        """ÙŠØªØ­Ù‚Ù‚ Ù…Ù† Ø£Ù…Ø§Ù† Ø§Ù„Ù…Ø±ÙÙ‚. Ù„Ø§ ÙŠØ¹ØªÙ…Ø¯ Ø¹Ù„Ù‰ Ø§Ù„Ø§Ù…ØªØ¯Ø§Ø¯ ÙÙ‚Ø·."""
        errors = []

        if attachment.file_size == 0:
            errors.append("Empty file")

        dangerous_mimes = [
            "application/x-executable",
            "application/x-msdownload",
            "application/x-sh",
            "application/x-shellscript",
        ]
        if attachment.mime_type in dangerous_mimes:
            errors.append("Dangerous MIME type: " + attachment.mime_type)

        dangerous_names = [".exe", ".bat", ".sh", ".cmd", ".com", ".scr"]
        for dn in dangerous_names:
            if attachment.filename.lower().endswith(dn):
                errors.append("Dangerous file extension: " + dn)

        attachment.validation_errors = errors
        attachment.is_safe = len(errors) == 0

    def _parse_file(self, attachment: Attachment) -> Optional[str]:
        """ÙŠØ­Ù„Ù„ Ø§Ù„Ù…Ù„Ù ÙˆÙŠØ®Ø±Ø¬ Ù…Ø­ØªÙˆÙ‰ Ù†ØµÙŠ."""
        try:
            if attachment.attachment_type in (AttachmentType.TEXT, AttachmentType.MARKDOWN, AttachmentType.SOURCE_CODE):
                with open(attachment.file_path, "r", encoding="utf-8", errors="replace") as f:
                    return f.read()
            elif attachment.attachment_type == AttachmentType.JSON:
                import json
                with open(attachment.file_path, "r", encoding="utf-8") as f:
                    data = json.load(f)
                return json.dumps(data, ensure_ascii=False, indent=2)
            elif attachment.attachment_type == AttachmentType.CSV:
                import csv
                rows = []
                with open(attachment.file_path, "r", encoding="utf-8") as f:
                    reader = csv.reader(f)
                    for row in reader:
                        rows.append(", ".join(row))
                return "\n".join(rows)
            elif attachment.attachment_type == AttachmentType.PDF:
                try:
                    import PyPDF2
                    text = []
                    with open(attachment.file_path, "rb") as f:
                        reader = PyPDF2.PdfReader(f)
                        for page in reader.pages:
                            text.append(page.extract_text() or "")
                    return "\n".join(text)
                except ImportError:
                    return "[PDF - PyPDF2 library not installed]"
            elif attachment.attachment_type == AttachmentType.IMAGE:
                return "[Image - requires OCR for text extraction]"
            else:
                return "[Unsupported file type for extraction: " + attachment.attachment_type.value + "]"
        except Exception as exc:
            return "[Content extraction error: " + str(exc) + "]"

    def get_context_summary(self, attachment_id: str) -> Optional[str]:
        """ÙŠØ¹ÙŠØ¯ Ù…Ù„Ø®ØµØ§ Ù„Ù„Ø³ÙŠØ§Ù‚ Ù…Ù† Ø§Ù„Ù…Ø±ÙÙ‚."""
        att = self.get(attachment_id)
        if not att:
            return None
        if att.context_summary:
            return att.context_summary
        content = self.extract_content(attachment_id)
        if content:
            summary = content[:500]
            if len(content) > 500:
                summary += "..."
            att.context_summary = summary
            return summary
        return None
