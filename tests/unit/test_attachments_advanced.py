"""Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù…Ø±ÙÙ‚Ø§Øª Ø§Ù„Ù…ØªÙ‚Ø¯Ù…Ø©."""

import sys
import os
import tempfile
import zipfile
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from tools.attachment_advanced import AdvancedAttachmentManager, advanced_attachment_manager
from tools.attachment import AttachmentType


def test_pdf_placeholder():
    """PDF Ø¨Ø¯ÙˆÙ† Ù…ÙƒØªØ¨Ø© PyPDF2 ÙŠØ¹ÙŠØ¯ Ø±Ø³Ø§Ù„Ø© ØªÙˆØ¶ÙŠØ­ÙŠØ©."""
    mgr = AdvancedAttachmentManager()
    # Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù„Ù PDF ÙˆÙ‡Ù…ÙŠ
    tmpdir = tempfile.mkdtemp()
    pdf_file = os.path.join(tmpdir, "test.pdf")
    with open(pdf_file, "wb") as f:
        f.write(b"%PDF-1.4 fake pdf content")

    try:
        att = mgr.register_attachment(pdf_file, uploaded_by="user1")
        content = mgr.extract_content(att.id)
        assert content is not None
        assert "PDF" in content or "pdf" in content.lower()
        print("  OK: PDF placeholder")
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)


def test_image_info():
    """Ù…Ø¹Ù„ÙˆÙ…Ø§Øª Ø§Ù„ØµÙˆØ±Ø© Ø¨Ø¯ÙˆÙ† Ù…ÙƒØªØ¨Ø© Pillow."""
    mgr = AdvancedAttachmentManager()
    tmpdir = tempfile.mkdtemp()
    img_file = os.path.join(tmpdir, "test.png")
    with open(img_file, "wb") as f:
        f.write(b"fake png data")

    try:
        att = mgr.register_attachment(img_file, uploaded_by="user1")
        content = mgr.extract_content(att.id)
        assert content is not None
        print("  OK: Image info")
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)


def test_zip_listing():
    """Ø¥listing Ù…Ø­ØªÙˆÙŠØ§Øª ZIP."""
    mgr = AdvancedAttachmentManager()
    tmpdir = tempfile.mkdtemp()
    zip_file = os.path.join(tmpdir, "test.zip")
    with zipfile.ZipFile(zip_file, "w") as zf:
        zf.writestr("file1.txt", "content1")
        zf.writestr("file2.txt", "content2")

    try:
        att = mgr.register_attachment(zip_file, uploaded_by="user1")
        assert att.attachment_type == AttachmentType.ZIP
        content = mgr.extract_content(att.id)
        assert content is not None
        assert "file1.txt" in content
        print("  OK: ZIP listing")
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)


def test_apk_info():
    """Ù…Ø¹Ù„ÙˆÙ…Ø§Øª APK."""
    mgr = AdvancedAttachmentManager()
    tmpdir = tempfile.mkdtemp()
    apk_file = os.path.join(tmpdir, "test.apk")
    with zipfile.ZipFile(apk_file, "w") as zf:
        zf.writestr("AndroidManifest.xml", "<manifest/>")
        zf.writestr("classes.dex", "dex data")

    try:
        att = mgr.register_attachment(apk_file, uploaded_by="user1")
        assert att.attachment_type == AttachmentType.APK
        content = mgr.extract_content(att.id)
        assert content is not None
        assert "manifest" in content.lower() or "APK" in content
        print("  OK: APK info")
    finally:
        import shutil
        shutil.rmtree(tmpdir, ignore_errors=True)


if __name__ == "__main__":
    test_pdf_placeholder()
    test_image_info()
    test_zip_listing()
    test_apk_info()
    print("\n=== Advanced Attachments: PASSED ===")
