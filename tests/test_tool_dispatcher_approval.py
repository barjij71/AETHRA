import unittest

from core.policy import Decision, PermissionBroker, PolicyRule, RiskLevel
from tools.dispatcher import ToolDispatcher
from tools.tool import Tool, ToolCategory, ToolRisk, ToolResult


class FakeToolRegistry:
    def __init__(self, *tools):
        self._tools = {tool.id: tool for tool in tools}

    def get(self, tool_id):
        return self._tools.get(tool_id)


class ToolDispatcherApprovalTests(unittest.TestCase):
    def make_dispatcher(self, tool, permission_broker=None):
        calls = []
        dispatcher = ToolDispatcher(
            FakeToolRegistry(tool),
            permission_broker or PermissionBroker(),
        )
        dispatcher.register_executor(
            tool.id,
            lambda _tool, params: (
                calls.append(params["command"]),
                ToolResult(success=True, output="executed"),
            )[1],
        )
        return dispatcher, calls

    def shell_tool(self):
        return Tool(
            id="shell.exec",
            name="Shell",
            description="Sensitive shell execution",
            category=ToolCategory.SYSTEM,
            risk=ToolRisk.SENSITIVE,
            requires_approval=True,
        )

    def test_sensitive_gate_is_not_auto_approved_or_mutable_by_caller(self):
        dispatcher, calls = self.make_dispatcher(self.shell_tool())
        params = {"command": "echo approved"}
        gate = dispatcher.request_execution("shell.exec", params, actor="user")

        self.assertFalse(gate.approved)
        gate.approved = True  # Mutating the returned snapshot must not approve the stored gate.
        result = dispatcher.execute("shell.exec", params, actor="user", gate_id=gate.id)

        self.assertFalse(result.success)
        self.assertEqual([], calls)

    def test_approval_is_bound_to_actor_payload_and_consumed_once(self):
        dispatcher, calls = self.make_dispatcher(self.shell_tool())
        params = {"command": "echo approved"}
        gate = dispatcher.request_execution("shell.exec", params, actor="user")

        self.assertFalse(dispatcher.approve_gate(gate.id, "system"))
        self.assertTrue(dispatcher.approve_gate(gate.id, "human-reviewer"))

        wrong_actor = dispatcher.execute("shell.exec", params, actor="other-user", gate_id=gate.id)
        wrong_payload = dispatcher.execute(
            "shell.exec",
            {"command": "echo changed"},
            actor="user",
            gate_id=gate.id,
        )
        self.assertFalse(wrong_actor.success)
        self.assertFalse(wrong_payload.success)
        self.assertEqual([], calls)

        success = dispatcher.execute("shell.exec", params, actor="user", gate_id=gate.id)
        replay = dispatcher.execute("shell.exec", params, actor="user", gate_id=gate.id)
        self.assertTrue(success.success)
        self.assertFalse(replay.success)
        self.assertEqual(["echo approved"], calls)

    def test_core_policy_deny_prevents_gate_creation(self):
        broker = PermissionBroker()
        broker.add_rule(PolicyRule(
            id="deny-shell",
            name="Deny shell",
            capability="tool.shell.exec",
            risk_level=RiskLevel.SENSITIVE,
            decision=Decision.DENY,
        ))
        dispatcher, calls = self.make_dispatcher(self.shell_tool(), broker)

        with self.assertRaises(PermissionError):
            dispatcher.request_execution(
                "shell.exec",
                {"command": "echo denied"},
                actor="user",
            )
        self.assertEqual([], calls)

    def test_safe_execution_still_requires_a_dispatcher_issued_gate(self):
        safe_tool = Tool(
            id="file.read",
            name="Read",
            description="Read a bounded file",
            category=ToolCategory.FILE,
            risk=ToolRisk.SAFE,
            requires_approval=False,
        )
        dispatcher, calls = self.make_dispatcher(safe_tool)
        gate = dispatcher.request_execution("file.read", {"command": "read"}, actor="user")

        self.assertTrue(gate.approved)
        without_gate = dispatcher.execute("file.read", {"command": "read"}, actor="user")
        with_gate = dispatcher.execute("file.read", {"command": "read"}, actor="user", gate_id=gate.id)
        self.assertFalse(without_gate.success)
        self.assertTrue(with_gate.success)
        self.assertEqual(["read"], calls)


if __name__ == "__main__":
    unittest.main()
