import ast
import unittest
from pathlib import Path


class OptionalAdapterBoundaryTests(unittest.TestCase):
    def test_core_integration_does_not_import_hermes_at_module_scope(self):
        source = Path("core/integration.py").read_text(encoding="utf-8")
        module = ast.parse(source)
        for node in module.body:
            if isinstance(node, ast.ImportFrom):
                self.assertNotEqual(node.module, "ui.hermes_api")
            if isinstance(node, ast.Import):
                self.assertFalse(any(alias.name == "ui.hermes_api" for alias in node.names))
        self.assertIn("def hermes_api", source)

    def test_external_adapters_do_not_self_approve(self):
        hermes_source = Path("ui/hermes_api.py").read_text(encoding="utf-8")
        web_source = Path("web_demo.py").read_text(encoding="utf-8")
        task_runner_source = Path("tools/task_runner.py").read_text(encoding="utf-8")
        self.assertNotIn("approve_gate(", hermes_source)
        self.assertNotIn("approve_gate(", web_source)
        self.assertNotIn('type("Gate"', task_runner_source)


if __name__ == "__main__":
    unittest.main()
