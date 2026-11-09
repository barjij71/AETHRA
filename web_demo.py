#!/usr/bin/env python3
"""AETHRA Web Demo - ÙˆØ§Ø¬Ù‡Ø© ÙˆÙŠØ¨ ØªØ¬Ø±ÙŠØ¨ÙŠØ© Ù„Ù„Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ø£ÙˆÙ„.

ØªØ´ØºÙŠÙ„:
    python3 web_demo.py

Ø«Ù… Ø§ÙØªØ­ Ø§Ù„Ù…ØªØµÙØ­ Ø¹Ù„Ù‰:
    http://localhost:5000

Ø£Ùˆ Ù…Ù† Ø®Ù„Ø§Ù„ instabox:
    instabox-port publish 5000
"""

from __future__ import annotations
import sys
import os

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from flask import Flask, render_template_string, request, jsonify, redirect, url_for

from core.integration import aethra
from core.task import TaskStatus
from devices.notification import notification_manager, NotificationType, NotificationPriority
from devices.registry import DeviceRegistry
from devices.android_adapter import AndroidRuntimeAdapter, LinuxAdapter

app = Flask(__name__)

# HTML í…œí”ŒÙŠØª Ù…Ø¹ Ø¯Ø¹Ù… Ø§Ù„Ø¹Ø±Ø¨ÙŠØ© Ø§Ù„ÙƒØ§Ù…Ù„
HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AETHRA Demo</title>
    <style>
        * { box-sizing: border-box; }
        body { font-family: "Segoe UI", Tahoma, Geneva, Verdana, sans-serif; background: #f0f2f5; margin: 0; padding: 20px; direction: rtl; }
        .container { max-width: 900px; margin: 0 auto; background: white; border-radius: 8px; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #2c3e50; text-align: center; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
        .nav { margin-bottom: 20px; display: flex; gap: 10px; flex-wrap: wrap; }
        .nav a { padding: 8px 16px; background: #3498db; color: white; border-radius: 4px; text-decoration: none; font-size: 14px; }
        .nav a:hover { background: #2980b9; }
        .nav a.active { background: #2c3e50; }
        .form-group { margin-bottom: 15px; }
        label { display: block; margin-bottom: 5px; font-weight: bold; color: #34495e; }
        input[type="text"], textarea, select { width: 100%; padding: 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
        button { background: #3498db; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; font-size: 14px; margin-right: 10px; }
        button:hover { background: #2980b9; }
        button.secondary { background: #95a5a6; }
        button.secondary:hover { background: #7f8c8d; }
        button.success { background: #27ae60; }
        button.success:hover { background: #219a52; }
        button.danger { background: #e74c3c; }
        button.danger:hover { background: #c0392b; }
        .result { margin-top: 20px; padding: 15px; border-radius: 4px; display: none; }
        .result.success { background: #d4edda; border: 1px solid #c3e6cb; color: #155724; display: block; }
        .result.error { background: #f8d7da; border: 1px solid #f5c6cb; color: #721c24; display: block; }
        .result.info { background: #d1ecf1; border: 1px solid #bee5eb; color: #0c5460; display: block; }
        .steps { margin-top: 15px; }
        .step { padding: 10px; margin: 5px 0; border-radius: 4px; font-size: 13px; border-left: 4px solid #3498db; }
        .step.completed { background: #e8f5e9; border-left-color: #4caf50; }
        .step.failed { background: #ffebee; border-left-color: #f44336; }
        .step.pending { background: #fff3e0; border-left-color: #ff9800; }
        .tasks, .notifications { margin-top: 20px; }
        .task-item, .notif-item { padding: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center; }
        .status-badge { padding: 4px 8px; border-radius: 12px; font-size: 12px; font-weight: bold; }
        .status-QUEUED { background: #fff3e0; color: #e65100; }
        .status-RUNNING { background: #e3f2fd; color: #1565c0; }
        .status-COMPLETED { background: #e8f5e9; color: #2e7d32; }
        .status-FAILED { background: #ffebee; color: #c62828; }
        .status-CANCELLED { background: #eceff1; color: #546e7a; }
        .notif-INFO { border-left: 4px solid #2196f3; }
        .notif-WARNING { border-left: 4px solid #ff9800; }
        .notif-ERROR { border-left: 4px solid #f44336; }
        .notif-SUCCESS { border-left: 4px solid #4caf50; }
        .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: 15px; margin: 20px 0; }
        .stat-card { background: #f8f9fa; padding: 15px; border-radius: 8px; text-align: center; border: 1px solid #dee2e6; }
        .stat-card h3 { margin: 0; font-size: 24px; color: #3498db; }
        .stat-card p { margin: 5px 0 0; color: #666; font-size: 14px; }
        .back { display: inline-block; margin-top: 20px; color: #3498db; text-decoration: none; }
        .back:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        <h1>ðŸ¤– AETHRA Demo</h1>
        
        <div class="nav">
            <a href="/">Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
            <a href="/tasks">Ø§Ù„Ù…Ù‡Ø§Ù…</a>
            <a href="/notifications">Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª</a>
            <a href="/dashboard">Ù„ÙˆØ­Ø© Ø§Ù„ØªØ­ÙƒÙ…</a>
            <a href="/audit">Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚</a>
            <a href="/status">Ø§Ù„Ø­Ø§Ù„Ø©</a>
        </div>

        <div class="form-group">
            <label>Ø£Ø¯Ø®Ù„ Ø·Ù„Ø¨Ùƒ Ø£Ùˆ Ø£Ù…Ø± Ù†Ø¸Ø§Ù…:</label>
            <form method="POST" action="/chat">
                <input type="text" name="message" placeholder="Ù…Ø«Ø§Ù„: Ù…Ø±Ø­Ø¨Ø§Ù‹ØŒ Ø£Ùˆ /shell echo hello" required>
                <button type="send">Ø¥Ø±Ø³Ø§Ù„</button>
            </form>
        </div>

        {% if response %}
        <div class="result {{ 'success' if response.success else 'error' }}">
            <strong>Ø§Ù„Ù†ØªÙŠØ¬Ø©:</strong> {{ response.message }}
            {% if response.steps %}
            <div class="steps">
                <strong>Ø®Ø·ÙˆØ§Øª Ø§Ù„ØªÙ†ÙÙŠØ°:</strong>
                {% for step in response.steps %}
                <div class="step {{ step.status }}">
                    {{ step.step_type }}: {{ step.label }}
                    {% if step.output %} - Ù…Ø®Ø±Ø¬Ø§Øª: {{ step.output[:100] }}{% endif %}
                    {% if step.error %} - Ø®Ø·Ø£: {{ step.error[:100] }}{% endif %}
                </div>
                {% endfor %}
            </div>
            {% endif %}
        </div>
        {% endif %}

        <div class="tasks">
            <h3>Ø¢Ø®Ø± Ø§Ù„Ù…Ù‡Ø§Ù…:</h3>
            {% for task in tasks %}
            <div class="task-item">
                <span>{{ task.name }}</span>
                <span class="status-badge status-{{ task.status }}">{{ task.status }}</span>
            </div>
            {% endfor %}
        </div>
    </div>
</body>
</html>
"""

@app.route("/")
def index():
    """Ø§Ù„ØµÙØ­Ø© Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ©."""
    response = None
    tasks = []
    try:
        all_tasks = aethra.task_engine.list_tasks()
        tasks = all_tasks[-5:]
    except Exception:
        pass
    return render_template_string(HTML_TEMPLATE, response=response, tasks=tasks)


@app.route("/chat", methods=["POST"])
def chat():
    """Ù…Ø¹Ø§Ù„Ø¬Ø© Ø±Ø³Ø§Ù„Ø© Ø§Ù„Ø¯Ø±Ø¯Ø´Ø©."""
    message = request.form.get("message", "").strip()
    if not message:
        return redirect(url_for("index"))

    response = None
    tasks = []
    try:
        resp = aethra.hermes_api.send_message("web_demo", message, actor="web_user")
        response = {
            "success": resp.success,
            "message": resp.message,
            "steps": [
                {
                    "step_type": s.step_type,
                    "label": s.label,
                    "status": s.status,
                    "output": s.output,
                    "error": s.error,
                }
                for s in resp.steps
            ],
        }
        all_tasks = aethra.task_engine.list_tasks()
        tasks = all_tasks[-5:]
    except Exception as e:
        response = {
            "success": False,
            "message": f"Ø®Ø·Ø£: {str(e)}",
            "steps": [],
        }

    return render_template_string(HTML_TEMPLATE, response=response, tasks=tasks)


@app.route("/tasks")
def tasks_page():
    """ØµÙØ­Ø© Ø§Ù„Ù…Ù‡Ø§Ù…."""
    all_tasks = aethra.task_engine.list_tasks()
    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ù…Ù‡Ø§Ù… - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 700px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .task { padding: 15px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center; background: #fff; margin: 8px 0; border-radius: 6px; }
            .task:last-child { border-bottom: none; }
            .status { padding: 6px 12px; border-radius: 20px; font-size: 13px; font-weight: bold; }
            .status-QUEUED { background: #fff3e0; color: #e65100; }
            .status-RUNNING { background: #e3f2fd; color: #1565c0; }
            .status-COMPLETED { background: #e8f5e9; color: #2e7d32; }
            .status-FAILED { background: #ffebee; color: #c62828; }
            .status-CANCELLED { background: #eceff1; color: #546e7a; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“‹ Ø§Ù„Ù…Ù‡Ø§Ù…</h1>
            {% for t in tasks %}
            <div class="task">
                <div>
                    <strong>{{ t.name }}</strong><br>
                    <small>Ø§Ù„Ù…Ø¹Ø±Ù: {{ t.id[:8] }}</small>
                </div>
                <span class="status status-{{ t.status }}">{{ t.status }}</span>
            </div>
            {% endfor %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, tasks=all_tasks)


@app.route("/notifications")
def notifications_page():
    """ØµÙØ­Ø© Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª."""
    notifs = notification_manager.get_notifications()
    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .notif { padding: 15px; border-bottom: 1px solid #eee; margin: 10px 0; border-radius: 6px; }
            .notif:last-child { border-bottom: none; }
            .notif-header { display: flex; justify-content: space-between; margin-bottom: 8px; }
            .notif-title { font-weight: bold; font-size: 16px; }
            .notif-type { padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; color: white; }
            .notif-message { margin: 8px 0; color: #555; }
            .notif-meta { font-size: 12px; color: #888; margin-top: 8px; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ”” Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª</h1>
            {% for n in notifs %}
            <div class="notif notif-{{ n.type.value }}">
                <div class="notif-header">
                    <span class="notif-title">{{ n.title }}</span>
                    <span class="notif-type">{{ n.type.value }}</span>
                </div>
                <div class="notif-message">{{ n.message }}</div>
                <div class="notif-meta">
                    Ø§Ù„Ù…ØµØ¯Ø±: {{ n.source }} | Ø§Ù„Ø£ÙˆÙ„ÙˆÙŠØ©: {{ n.priority.value }} | Ø§Ù„ÙˆÙ‚Øª: {{ "%.0f"|format(n.timestamp) }}
                    {% if n.read %} | âœ“ Ù…Ù‚Ø±ÙˆØ¡{% endif %}
                </div>
            </div>
            {% endfor %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, notifs=notifs)


@app.route("/dashboard")
def dashboard_page():
    """ØµÙØ­Ø© Ù„ÙˆØ­Ø© Ø§Ù„ØªØ­ÙƒÙ…."""
    stats = {}
    snap = None
    health = {}
    try:
        from core.security_policy import security_policy
        stats = security_policy.get_stats()
    except Exception:
        pass
    try:
        from core.monitoring import system_monitor, health_checker
        snap = system_monitor.get_latest_snapshot()
        health = health_checker.run_all_checks()
    except Exception:
        pass
    try:
        notif_stats = notification_manager.get_stats()
    except Exception:
        notif_stats = {}
    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ù„ÙˆØ­Ø© Ø§Ù„ØªØ­ÙƒÙ… - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 900px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .stats-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(200px, 1fr)); gap: 20px; margin: 20px 0; }
            .stat-card { background: #e8f4fd; padding: 20px; border-radius: 8px; text-align: center; border: 1px solid #bee5eb; }
            .stat-card h3 { margin: 0; font-size: 28px; color: #2980b9; }
            .stat-card p { margin: 5px 0 0; color: #666; font-size: 14px; }
            .metric { background: #f8f9fa; padding: 15px; border-radius: 6px; margin: 10px 0; border-left: 4px solid #3498db; }
            .health-item { padding: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; }
            .health-ok { color: #27ae60; font-weight: bold; }
            .health-fail { color: #e74c3c; font-weight: bold; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“Š Ù„ÙˆØ­Ø© Ø§Ù„ØªØ­ÙƒÙ…</h1>
            
            <div class="stats-grid">
                <div class="stat-card">
                    <h3>{{ notif_stats.total if notif_stats else 0 }}</h3>
                    <p>Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø¥Ø¬Ù…Ø§Ù„ÙŠØ©</p>
                </div>
                <div class="stat-card">
                    <h3>{{ notif_stats.unread if notif_stats else 0 }}</h3>
                    <p>Ø¥Ø´Ø¹Ø§Ø±Ø§Øª ØºÙŠØ± Ù…Ù‚Ø±ÙˆØ¡Ø©</p>
                </div>
                <div class="stat-card">
                    <h3>{{ stats.total_events if stats else 0 }}</h3>
                    <p>Ø£Ø­Ø¯Ø§Ø« ØªØ¯Ù‚ÙŠÙ‚</p>
                </div>
            </div>

            {% if snap %}
            <h3>Ù…Ø±Ø§Ù‚Ø¨Ø© Ø§Ù„Ù…ÙˆØ§Ø±Ø¯:</h3>
            <div class="metric"><strong>ÙˆØ­Ø¯Ø© Ø§Ù„Ù…Ø¹Ø§Ù„Ø¬Ø©:</strong> {{ snap.cpu_percent }}%</div>
            <div class="metric"><strong>Ø§Ù„Ø°Ø§ÙƒØ±Ø©:</strong> {{ snap.memory_percent }}% ({{ snap.memory_available_mb }} Ù…ÙŠØ¬Ø§Ø¨Ø§ÙŠØª Ù…ØªØ§Ø­Ø©)</div>
            <div class="metric"><strong>Ø§Ù„Ù‚Ø±Øµ:</strong> {{ snap.disk_percent }}% ({{ snap.disk_free_gb }} Ø¬ÙŠØ¬Ø§Ø¨Ø§ÙŠØª Ø­Ø±Ø©)</div>
            {% endif %}

            {% if health %}
            <h3>ÙØ­ÙˆØµØ§Øª Ø§Ù„ØµØ­Ø©:</h3>
            {% for name, r in health.items() %}
            <div class="health-item">
                <span>{{ name }}</span>
                <span class="{% if r.healthy %}health-ok{% else %}health-fail{% endif %}">
                    {% if r.healthy %}Ø³Ù„ÙŠÙ…{% else %}ÙØ´Ù„{% endif %}
                </span>
            </div>
            {% endfor %}
            {% endif %}

            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, stats=stats, snap=snap, health=health, notif_stats=notif_stats)


@app.route("/audit")
def audit_page():
    """ØµÙØ­Ø© Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚."""
    try:
        from core.security_policy import security_policy
        records = security_policy.get_audit_log(limit=20)
    except Exception:
        records = []
    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚ - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 900px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            table { width: 100%; border-collapse: collapse; }
            th, td { padding: 10px; text-align: right; border-bottom: 1px solid #ddd; }
            th { background: #3498db; color: white; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“‹ Ø§Ù„Ø³Ø¬Ù„ Ø§Ù„ØªØ¯Ù‚ÙŠÙ‚</h1>
            <table>
                <tr><th>Ø§Ù„ÙˆÙ‚Øª</th><th>Ø§Ù„Ù†ÙˆØ¹</th><th>Ø§Ù„Ù…ÙØ¹Ù„</th><th>Ø§Ù„Ù…ÙˆØ±Ø¯</th><th>Ø§Ù„Ù‚Ø±Ø§Ø±</th></tr>
                {% for r in records %}
                <tr>
                    <td>{{ "%.0f"|format(r.timestamp) }}</td>
                    <td>{{ r.event_type.value }}</td>
                    <td>{{ r.actor }}</td>
                    <td>{{ r.resource }}</td>
                    <td>{{ r.decision.value if r.decision else "-" }}</td>
                </tr>
                {% endfor %}
            </table>
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, records=records)


@app.route("/status")
def status_page():
    """ØµÙØ­Ø© Ø­Ø§Ù„Ø© Ø§Ù„Ù†Ø¸Ø§Ù…."""
    snap = None
    try:
        from core.monitoring import system_monitor
        snap = system_monitor.get_latest_snapshot()
    except Exception:
        snap = None
    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ø­Ø§Ù„Ø© - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 600px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .metric { background: #e8f4fd; padding: 15px; border-radius: 6px; margin: 10px 0; border-left: 4px solid #3498db; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“Š Ø­Ø§Ù„Ø© Ø§Ù„Ù†Ø¸Ø§Ù…</h1>
            {% if snap %}
            <div class="metric"><strong>ÙˆØ­Ø¯Ø© Ø§Ù„Ù…Ø¹Ø§Ù„Ø¬Ø©:</strong> {{ snap.cpu_percent }}%</div>
            <div class="metric"><strong>Ø§Ù„Ø°Ø§ÙƒØ±Ø©:</strong> {{ snap.memory_percent }}% ({{ snap.memory_available_mb }} Ù…ÙŠØ¬Ø§Ø¨Ø§ÙŠØª Ù…ØªØ§Ø­Ø©)</div>
            <div class="metric"><strong>Ø§Ù„Ù‚Ø±Øµ:</strong> {{ snap.disk_percent }}% ({{ snap.disk_free_gb }} Ø¬ÙŠØ¬Ø§Ø¨Ø§ÙŠØª Ø­Ø±Ø©)</div>
            {% else %}
            <p>Ù„Ø§ ØªÙˆØ¬Ø¯ Ø¨ÙŠØ§Ù†Ø§Øª Ù…Ø±Ø§Ù‚Ø¨Ø©.</div>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, snap=snap)


# ===== Ù†Ù‚Ø§Ø· Ù†Ù‡Ø§ÙŠØ© Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª =====

@app.route("/notifications/api")
def notifications_api():
    """Ø¬Ù„Ø¨ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª Ø¨ØµÙŠØºØ© JSON."""
    notifs = aethra.notification_manager.get_notifications()
    return jsonify([
        {
            "id": n.id,
            "type": n.type.value,
            "title": n.title,
            "message": n.message,
            "priority": n.priority.value,
            "timestamp": n.timestamp,
            "read": n.read,
            "acknowledged": n.acknowledged,
            "source": n.source,
        }
        for n in notifs
    ])


@app.route("/notifications/create", methods=["POST"])
def notifications_create():
    """Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø± Ø¬Ø¯ÙŠØ¯."""
    title = request.form.get("title", "").strip()
    message = request.form.get("message", "").strip()
    priority = request.form.get("priority", "NORMAL").strip()
    if not title or not message:
        return jsonify({"success": False, "error": "Ø§Ù„Ø¹Ù†ÙˆØ§Ù† ÙˆØ§Ù„Ù…essage Ù…Ø·Ù„ÙˆØ¨Ø§Ù†"}), 400

    try:
        prio = NotificationPriority[priority.upper()]
    except KeyError:
        prio = NotificationPriority.NORMAL

    nid = aethra.notification_manager.create_notification(
        type=NotificationType.INFO,
        title=title,
        message=message,
        priority=prio,
        source="web_demo",
    )
    return jsonify({"success": True, "id": nid})


@app.route("/notifications/<nid>/read", methods=["POST"])
def notifications_read(nid):
    """ØªØ­Ø¯ÙŠØ¯ Ø¥Ø´Ø¹Ø§Ø± ÙƒÙ…Ù‚Ø±ÙˆØ¡."""
    success = aethra.notification_manager.mark_read(nid)
    return jsonify({"success": success})


# ===== ØµÙØ­Ø© Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ø£Ø¬Ù‡Ø²Ø© =====

@app.route("/devices")
def devices_page():
    """ØµÙØ­Ø© Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ø£Ø¬Ù‡Ø²Ø©."""
    devices = []
    try:
        from core.integration import aethra as _aethra
        # Ø¥Ù†Ø´Ø§Ø¡ Ø³Ø¬Ù„ Ø£Ø¬Ù‡Ø²Ø© Ù…Ø¤Ù‚Øª Ù„Ù„Ø¹Ø±Ø¶
        reg = DeviceRegistry()
        android = AndroidRuntimeAdapter()
        linux = LinuxAdapter()
        reg.register(type('DeviceEntry', (), {
            'identity': type('DeviceIdentity', (), {
                'device_id': android.device_id,
                'name': android.identity.name if hasattr(android, 'identity') else 'Android Device',
                'platform': 'android',
                'is_paired': True,
            })(),
            'capabilities': android.get_capabilities(),
            'is_online': android.is_available(),
        })())
        reg.register(type('DeviceEntry', (), {
            'identity': type('DeviceIdentity', (), {
                'device_id': linux.device_id,
                'name': 'Linux Device',
                'platform': 'linux',
                'is_paired': True,
            })(),
            'capabilities': linux.get_capabilities(),
            'is_online': linux.is_available(),
        })())
        devices = reg.list()
    except Exception as e:
        devices = []
        error = str(e)

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ø£Ø¬Ù‡Ø²Ø© - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .device { padding: 15px; border-bottom: 1px solid #eee; margin: 10px 0; border-radius: 6px; background: #fff; }
            .device:last-child { border-bottom: none; }
            .device-header { display: flex; justify-content: space-between; margin-bottom: 8px; }
            .device-name { font-weight: bold; font-size: 16px; }
            .device-platform { padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; color: white; }
            .platform-android { background: #4caf50; }
            .platform-linux { background: #2196f3; }
            .platform-windows { background: #9c27b0; }
            .device-cap { margin: 5px 0; font-size: 13px; color: #555; }
            .status-online { color: #27ae60; font-weight: bold; }
            .status-offline { color: #e74c3c; font-weight: bold; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“± Ø§Ù„Ø£Ø¬Ù‡Ø²Ø© Ø§Ù„Ù…ØªØµÙ„Ø©</h1>
            {% if devices %}
            {% for d in devices %}
            <div class="device">
                <div class="device-header">
                    <span class="device-name">{{ d.identity.name }}</span>
                    <span class="device-platform platform-{{ d.identity.platform }}">{{ d.identity.platform }}</span>
                </div>
                <div class="device-cap">
                    <span class="{% if d.is_online %}status-online{% else %}status-offline{% endif %}">
                        {% if d.is_online %}Ù…ØªØµÙ„{% else %}ØºÙŠØ± Ù…ØªØµÙ„{% endif %}
                    </span>
                </div>
                <div class="device-cap">
                    Ø§Ù„Ù‚Ø¯Ø±Ø§Øª: {{ d.capabilities.shell and 'Ù‚Ø´Ø±Ø©' or '' }} {{ d.capabilities.file_transfer and 'Ù†Ù‚Ù„ Ù…Ù„ÙØ§Øª' or '' }} {{ d.capabilities.clipboard and 'Ø­Ø§ÙØ¸Ø©' or '' }} {{ d.capabilities.camera and 'ÙƒØ§Ù…ÙŠØ±Ø§' or '' }} {{ d.capabilities.microphone and 'Ù…ÙŠÙƒØ±ÙˆÙÙˆÙ†' or '' }} {{ d.capabilities.gpu and 'GPU' or '' }}
                </div>
            </div>
            {% endfor %}
            {% else %}
            <p>Ù„Ø§ ØªÙˆØ¬Ø¯ Ø£Ø¬Ù‡Ø²Ø© Ù…ØªØµÙ„Ø©.</p>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, devices=devices, error=error)


if __name__ == "__main__":
    print("ðŸš€ AETHRA Web Demo ÙŠØ¹Ù…Ù„ Ø¹Ù„Ù‰ http://0.0.0.0:5000")
    print("   Ø§ÙØªØ­ Ø§Ù„Ù…ØªØµÙØ­ Ø¹Ù„Ù‰: http://localhost:5000")
    print("   Ø£Ùˆ Ù…Ù† Ø®Ù„Ø§Ù„ instabox: instabox-port publish 5000")
    app.run(host="0.0.0.0", port=5000, debug=True)
