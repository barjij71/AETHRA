import sys
sys.path.insert(0, "/data/workspace/aethra-src")

from skills.plugin_manager import (
    PluginManager, Plugin, PluginManifest, PluginState, PluginType,
    PluginHook, plugin_manager,
)

# Ø§Ø®ØªØ¨Ø§Ø± PluginManifest
m = PluginManifest(
    id="plugin.osint",
    name="OSINT Tool",
    version="1.0.0",
    description="Ø£Ø¯Ø§Ø© OSINT Ù„Ù„Ø¨Ø­Ø«",
    author="community",
    plugin_type=PluginType.OSINT,
    capabilities=["web_search", "domain_lookup"],
)
assert m.id == "plugin.osint"
assert m.plugin_type == PluginType.OSINT
print("OK: PluginManifest")

# Ø§Ø®ØªØ¨Ø§Ø± Plugin
p = Plugin(manifest=m)
assert p.state == PluginState.DISCOVERED
p.transition(PluginState.VALID)
assert p.state == PluginState.VALID
p.transition(PluginState.INSTALLED)
assert p.state == PluginState.INSTALLED
p.transition(PluginState.ENABLED)
assert p.state == PluginState.ENABLED
print("OK: Plugin state transitions")

# Ø§Ø®ØªØ¨Ø§Ø± PluginManager
pm = PluginManager()
pm.register(p)
assert pm.get("plugin.osint") is not None
assert pm.get("nonexistent") is None
assert len(pm.list()) == 1
assert len(pm.list_by_type(PluginType.OSINT)) == 1
assert len(pm.list_by_state(PluginState.ENABLED)) == 1
print("OK: PluginManager register/list")

# Ø§Ø®ØªØ¨Ø§Ø± hooks
hook = pm.create_hook("tool:execute")
results = hook.emit("test_data")
assert results == []  # Ù„Ø§ Ù…Ø¹Ø§Ù„Ø¬ÙŠÙ† Ù…Ø³Ø¬Ù„ÙŠÙ†
print("OK: PluginHook create/emit (no handlers)")

# ØªØ³Ø¬ÙŠÙ„ Ù…Ø¹Ø§Ù„Ø¬
def my_handler(data):
    return "handled: " + str(data)
hook.register(my_handler)
results = hook.emit("hello")
assert results == ["handled: hello"]
print("OK: PluginHook register/emit")

# Ø§Ø®ØªØ¨Ø§Ø± emit_hook Ø¹Ø¨Ø± manager
pm.emit_hook("tool:execute", "world")
print("OK: PluginManager emit_hook")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØªØ«Ø¨ÙŠØª Ø¹Ø¨Ø± Ø§Ù„Ù…Ø«Ø¨Øª Ø§Ù„ÙˆÙ‡Ù…ÙŠ
def fake_installer(plugin):
    return True
pm.set_installer(fake_installer)
p2 = Plugin(manifest=PluginManifest(
    id="plugin.test2", name="Test2", version="1.0", description="t",
    author="a", plugin_type=PluginType.TOOL,
))
pm.register(p2)
assert pm.install(p2) == True
assert p2.state == PluginState.INSTALLED
assert p2.installed_at is not None
print("OK: PluginManager install")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØªÙØ¹ÙŠÙ„
assert pm.enable("plugin.test2") == True
assert pm.get("plugin.test2").state == PluginState.ENABLED
print("OK: PluginManager enable")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„ØªØ´ØºÙŠÙ„
result = pm.run("plugin.test2", "data")
assert isinstance(result, list)
print("OK: PluginManager run")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø¥Ø­ØµØ§Ø¦ÙŠØ§Øª
stats = pm.get_stats()
assert stats["total"] >= 2
assert "by_state" in stats
assert "by_type" in stats
print("OK: PluginManager stats:", stats["total"], "plugins")

# Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø¥Ø²Ø§Ù„Ø©
assert pm.remove("plugin.test2") == True
assert pm.get("plugin.test2") is None
print("OK: PluginManager remove")

# Ø§Ù„Ù…Ø«ÙŠÙ„ Ø§Ù„Ø¹Ø§Ù…
assert plugin_manager is not None
print("OK: Global plugin_manager available")

print("=== Plugin Architecture tests: PASSED ===")
