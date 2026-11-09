"""Tests VerificationEngine - evidence et postconditions."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.verification import VerificationEngine


def test_verification_pass():
    verif = VerificationEngine()
    result = verif.verify("creer_fichier", "/tmp/test.txt", "/tmp/test.txt")
    assert result.passed == True
    assert result.postcondition_met == True
    assert result.evidence.matched == True
    print("  OK: test_verification_pass")


def test_verification_fail():
    verif = VerificationEngine()
    result = verif.verify("creer_fichier", "/tmp/autre.txt", "/tmp/test.txt")
    assert result.passed == False
    assert result.postcondition_met == False
    assert result.evidence.matched == False
    print("  OK: test_verification_fail")


def test_custom_verifier():
    verif = VerificationEngine()

    def contains_verifier(actual, expected):
        return expected in str(actual)

    verif.register_verifier("file.content", contains_verifier)
    result = verif.verify(
        "verifier_contenu",
        "Bonjour le monde",
        "Bonjour",
        action_type="file.content"
    )
    assert result.passed == True
    print("  OK: test_custom_verifier")


if __name__ == "__main__":
    test_verification_pass()
    test_verification_fail()
    test_custom_verifier()
    print("
=== Tous les tests VerificationEngine sont reussis ===")
