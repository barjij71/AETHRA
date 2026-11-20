"""Tests PersistenceManager - sauvegarde/restauration."""

import sys
import os
import tempfile
import shutil
sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))

from core.persistence import PersistenceManager


def test_persistence_save_load():
    tmpdir = tempfile.mkdtemp()
    try:
        pm = PersistenceManager(storage_dir=tmpdir)
        pm.save_task("task_1", {"status": "RUNNING", "progress": 50})
        loaded = pm.load_task("task_1")
        assert loaded is not None
        assert loaded["status"] == "RUNNING"
        assert loaded["progress"] == 50
        print("  OK: test_persistence_save_load")
    finally:
        shutil.rmtree(tmpdir)


def test_persistence_list_and_delete():
    tmpdir = tempfile.mkdtemp()
    try:
        pm = PersistenceManager(storage_dir=tmpdir)
        pm.save_task("task_a", {"x": 1})
        pm.save_task("task_b", {"x": 2})

        files = pm.list_saved_tasks()
        assert len(files) == 2

        assert pm.delete_task("task_a") == True
        assert pm.load_task("task_a") is None
        assert pm.load_task("task_b") is not None
        print("  OK: test_persistence_list_and_delete")
    finally:
        shutil.rmtree(tmpdir)


if __name__ == "__main__":
    test_persistence_save_load()
    test_persistence_list_and_delete()
    print("
=== Tous les tests Persistence sont reussis ===")
