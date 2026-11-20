import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from providers.mock import MockProvider
from providers.provider_factory import ProviderFactory
from providers.base import ProviderRequest

# Ø§Ø®ØªØ¨Ø§Ø± MockProvider Ù…Ø¨Ø§Ø´Ø±Ø©
mock = MockProvider(should_fail=False)
req = ProviderRequest(model="mock-model", messages=[{"role": "user", "content": "Ù…Ø±Ø­Ø¨Ø§"}])
resp = mock.complete(req)
assert "Ø¥ÙŠØ«Ø±Ø§" in resp.content or "Ù…Ø±Ø­Ø¨Ø§" in resp.content
assert resp.provider == "mock"
print("OK: MockProvider basic response")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ÙØ´Ù„ Ø§Ù„Ù…Ø­Ø§ÙƒÙŠ
mock_fail = MockProvider(should_fail=True)
try:
    mock_fail.complete(req)
    assert False, "ÙŠØ¬Ø¨ Ø£Ù† ÙŠÙØ´Ù„"
except RuntimeError:
    print("OK: MockProvider simulated failure")

# Ø§Ø®ØªØ¨Ø§Ø± ProviderFactory
p = ProviderFactory.create("mock")
assert p.provider_id == "mock"
print("OK: ProviderFactory.create mock")

# Ø§Ø®ØªØ¨Ø§Ø± Ù„Ù„ØªØ³Ø¬ÙŠÙ„ Ø§Ù„Ù…Ø®ØµØµ
class CustomProvider(MockProvider):
    def __init__(self):
        super().__init__(provider_id="custom", name="Custom")

ProviderFactory.register_custom("custom", CustomProvider)
cp = ProviderFactory.create_custom("custom")
assert cp.provider_id == "custom"
print("OK: ProviderFactory custom registration")

print("=== Mock Provider tests: PASSED ===")
