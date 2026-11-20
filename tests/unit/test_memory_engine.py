"""Tests MemoryEngine - memoires layer??es."""

import sys
import os
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.memory import MemoryEngine


def test_memory_write_read():
    mem = MemoryEngine()
    rid = mem.write("working", "key1", "valeur1", "test_source", relevance=0.9, confidence=0.8)
    assert rid is not None

    val = mem.read("working", "key1")
    assert val == "valeur1"

    record = mem.read_record("working", "key1")
    assert record is not None
    assert record.source == "test_source"
    assert record.relevance == 0.9
    assert record.confidence == 0.8
    print("  OK: test_memory_write_read")


def test_memory_layer_isolation():
    mem = MemoryEngine()
    mem.write("conversation", "k", "val_conv", "src")
    mem.write("working", "k", "val_work", "src")

    assert mem.read("conversation", "k") == "val_conv"
    assert mem.read("working", "k") == "val_work"
    print("  OK: test_memory_layer_isolation")


def test_memory_delete_and_clear():
    mem = MemoryEngine()
    mem.write("task", "t1", "v1", "src")
    mem.write("task", "t2", "v2", "src")

    assert mem.delete("task", "t1") == True
    assert mem.read("task", "t1") is None
    assert mem.read("task", "t2") == "v2"

    count = mem.clear_layer("task")
    assert count == 1
    assert mem.read("task", "t2") is None
    print("  OK: test_memory_delete_and_clear")


if __name__ == "__main__":
    test_memory_write_read()
    test_memory_layer_isolation()
    test_memory_delete_and_clear()
    print("
=== Tous les tests MemoryEngine sont reussis ===")
