"""Tests PermissionBroker - politiques de securite."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.policy import PermissionBroker, RiskLevel, Decision


def test_safe_low_auto_allowed():
    broker = PermissionBroker()
    req = broker.request_permission("user", "file.read", "/tmp/test.txt", RiskLevel.SAFE)
    assert broker.is_allowed(req.id) == True
    assert req.decision == Decision.ALLOW

    req2 = broker.request_permission("user", "file.write", "/tmp/test.txt", RiskLevel.LOW)
    assert broker.is_allowed(req2.id) == True
    print("  OK: test_safe_low_auto_allowed")


def test_sensitive_requires_approval():
    broker = PermissionBroker()
    req = broker.request_permission("user", "system.shutdown", "localhost", RiskLevel.SENSITIVE)
    assert req.decision == Decision.REQUIRES_APPROVAL
    assert broker.is_allowed(req.id) == False

    assert broker.approve(req.id, "admin") == True
    assert broker.is_allowed(req.id) == True
    print("  OK: test_sensitive_requires_approval")


def test_destructive_requires_approval():
    broker = PermissionBroker()
    req = broker.request_permission("user", "data.delete", "/database", RiskLevel.DESTRUCTIVE)
    assert req.decision == Decision.REQUIRES_APPROVAL
    assert broker.approve(req.id, "admin") == True
    assert broker.is_allowed(req.id) == True
    print("  OK: test_destructive_requires_approval")


def test_deny_pending():
    broker = PermissionBroker()
    req = broker.request_permission("user", "network.access", "external.api", RiskLevel.SENSITIVE)
    assert broker.deny(req.id, "admin") == True
    assert broker.is_allowed(req.id) == False
    print("  OK: test_deny_pending")


if __name__ == "__main__":
    test_safe_low_auto_allowed()
    test_sensitive_requires_approval()
    test_destructive_requires_approval()
    test_deny_pending()
    print("
=== Tous les tests PermissionBroker sont reussis ===")
