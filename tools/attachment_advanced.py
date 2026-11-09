"""Ø¯Ø¹Ù… Ù…ØªÙ‚Ø¯Ù… Ù„Ù„Ù…Ø±ÙÙ‚Ø§Øª: PDFØŒ ØµÙˆØ±ØŒ ZIPØŒ APKØŒ Ø§Ù„Ù…Ø³ØªÙ†Ø¯Ø§Øª."""

from __future__ import annotations
import hashlib
import io
import os
import zipfile
from typing import Any, Dict, List, Optional

from tools.attachment import Attachment, AttachmentManager, AttachmentType


class AdvancedAttachmentManager(AttachmentManager):
    """Ù…Ø¯ÙŠØ± Ù…Ø±ÙÙ‚Ø§Øª Ù…ØªÙ‚Ø¯Ù… ÙŠØ¯Ø¹Ù… PDFØŒ ØµÙˆØ±ØŒ ZIPØŒ APK."""

    def _parse_file(self, attachment: Attachment) -> Optional[str]:
        """ÙŠØ­Ù„Ù„ Ø§Ù„Ù…Ù„Ù ÙˆÙŠØ®Ø±Ø¬ Ù…Ø­ØªÙˆÙ‰ Ù†ØµÙŠ - Ø¯Ø¹Ù… Ù…ØªÙ‚Ø¯Ù…."""
        try:
            if attachment.attachment_type == AttachmentType.PDF:
                return self._parse_pdf(attachment)
            elif attachment.attachment_type == AttachmentType.IMAGE:
                return self._parse_image(attachment)
            elif attachment.attachment_type == AttachmentType.ZIP:
                return self._parse_zip(attachment)
            elif attachment.attachment_type == AttachmentType.APK:
                return self._parse_apk(attachment)
            elif attachment.attachment_type in (AttachmentType.TEXT, AttachmentType.MARKDOWN, AttachmentType.SOURCE_CODE):
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
            else:
                return "[Ù†ÙˆØ¹ Ù…Ù„Ù ØºÙŠØ± Ù…Ø¯Ø¹ÙˆÙ… Ù„Ù„Ø§Ø³ØªØ®Ø±Ø§Ø¬: " + attachment.attachment_type.value + "]"
        except Exception as exc:
            return "[Ø®Ø·Ø£ ÙÙŠ Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ø§Ù„Ù…Ø­ØªÙˆÙ‰: " + str(exc) + "]"

    def _parse_pdf(self, attachment: Attachment) -> Optional[str]:
        """Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ù†Øµ Ù…Ù† PDF."""
        try:
            import PyPDF2
            text = []
            with open(attachment.file_path, "rb") as f:
                reader = PyPDF2.PdfReader(f)
                for i, page in enumerate(reader.pages):
                    text.append("--- ØµÙØ­Ø© " + str(i + 1) + " ---")
                    text.append(page.extract_text() or "")
            return "\n".join(text)
        except ImportError:
            return "[PDF - Ù…ÙƒØªØ¨Ø© PyPDF2 ØºÙŠØ± Ù…Ø«Ø¨ØªØ©]"
        except Exception as exc:
            return "[Ø®Ø·Ø£ ÙÙŠ Ù‚Ø±Ø§Ø¡Ø© PDF: " + str(exc) + "]"

    def _parse_image(self, attachment: Attachment) -> Optional[str]:
        """Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ù…Ø¹Ù„ÙˆÙ…Ø§Øª Ù…Ù† ØµÙˆØ±Ø© (ÙŠØªØ·Ù„Ø¨ OCR Ù„Ù„Ù†Øµ)."""
        try:
            from PIL import Image
            with Image.open(attachment.file_path) as img:
                info = {
                    "format": img.format,
                    "mode": img.mode,
                    "size": img.size,
                    "width": img.width,
                    "height": img.height,
                }
                return "[ØµÙˆØ±Ø©: " + str(info) + "]"
        except ImportError:
            return "[ØµÙˆØ±Ø© - Ù…ÙƒØªØ¨Ø© Pillow ØºÙŠØ± Ù…Ø«Ø¨ØªØ© Ù„Ø§Ø³ØªØ®Ø±Ø§Ø¬ Ø§Ù„Ù…Ø¹Ù„ÙˆÙ…Ø§Øª]"
        except Exception as exc:
            return "[Ø®Ø·Ø£ ÙÙŠ Ù‚Ø±Ø§Ø¡Ø© Ø§Ù„ØµÙˆØ±Ø©: " + str(exc) + "]"

    def _parse_zip(self, attachment: Attachment) -> Optional[str]:
        """Ø¥listing Ù…Ø­ØªÙˆÙŠØ§Øª Ù…Ù„Ù ZIP."""
        try:
            with zipfile.ZipFile(attachment.file_path, "r") as zf:
                names = zf.namelist()
                return "[Ù…Ù„Ù ZIP ÙŠØ­ØªÙˆÙŠ Ø¹Ù„Ù‰ " + str(len(names)) + " Ø¹Ù†ØµØ±:\n" + "\n".join(names[:50]) + "]"
        except Exception as exc:
            return "[Ø®Ø·Ø£ ÙÙŠ Ù‚Ø±Ø§Ø¡Ø© ZIP: " + str(exc) + "]"

    def _parse_apk(self, attachment: Attachment) -> Optional[str]:
        """Ù…Ø¹Ù„ÙˆÙ…Ø§Øª Ø£Ø³Ø§Ø³ÙŠØ© Ø¹Ù† Ù…Ù„Ù APK."""
        try:
            with zipfile.ZipFile(attachment.file_path, "r") as zf:
                names = zf.namelist()
                has_manifest = "AndroidManifest.xml" in names
                return "[Ù…Ù„Ù APK: " + str(len(names)) + " Ø¹Ù†ØµØ±ØŒå¦æœ‰ manifest: " + str(has_manifest) + "]"
        except Exception as exc:
            return "[Ø®Ø·Ø£ ÙÙŠ Ù‚Ø±Ø§Ø¡Ø© APK: " + str(exc) + "]"


# instance Ø¹Ø§Ù…Ø©
advanced_attachment_manager = AdvancedAttachmentManager(max_file_size_mb=100)
