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

app = Flask(__name__)

# HTML í…œí”ŒÙŠØª Ø¨Ø³ÙŠØ·
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
        .container { max-width: 800px; margin: 0 auto; background: white; border-radius: 8px; padding: 20px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }
        h1 { color: #2c3e50; text-align: center; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
        .form-group { margin-bottom: 15px; }
        label { display: block; margin-bottom: 5px; font-weight: bold; color: #34495e; }
        input[type="text"], textarea { width: 100%; padding: 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
        button { background: #3498db; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; font-size: 14px; }
        button:hover { background: #2980b9; }
        button.secondary { background: #95a5a6; }
        button.secondary:hover { background: #7f8c8d; }
        .result { margin-top: 20px; padding: 15px; border-radius: 4px; display: none; }
        .result.success { background: #d4edda; border: 1px solid #c3e6cb; color: #155724; display: block; }
        .result.error { background: #f8d7da; border: 1px solid #f5c6cb; color: #721c24; display: block; }
        .steps { margin-top: 15px; }
        .step { padding: 10px; margin: 5px 0; border-radius: 4px; font-size: 13px; }
        .step.completed { background: #e8f5e9; border-left: 4px solid #4caf50; }
        .step.failed { background: #ffebee; border-left: 4px solid #f44336; }
        .step.pending { background: #fff3e0; border-left: 4px solid #ff9800; }
        .tasks { margin-top: 20px; }
        .task-item { padding: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center; }
        .status-badge { padding: 4px 8px; border-radius: 12px; font-size: 12px; font-weight: bold; }
        .status-QUEUED { background: #fff3e0; color: #e65100; }
        .status-RUNNING { background: #e3f2fd; color: #1565c0; }
        .status-COMPLETED { background: #e8f5e9; color: #2e7d32; }
        .status-FAILED { background: #ffebee; color: #c62828; }
        .nav { margin-bottom: 20px; }
        .nav a { margin-right: 15px; text-decoration: none; color: #3498db; }
        .nav a:hover { text-decoration: underline; }
    </style>
</head>
<body>
    <div class="container">
        <h1>ðŸ¤– AETHRA Demo</h1>
        
        <div class="nav">
            <a href="/">Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
            <a href="/tasks">Ø§Ù„Ù…Ù‡Ø§Ù…</a>
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
        tasks = all_tasks[-5:]  # Ø¢Ø®Ø± 5 Ù…Ù‡Ø§Ù…
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
            .container { max-width: 600px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .task { padding: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; }
            .status { padding: 4px 8px; border-radius: 12px; font-size: 12px; font-weight: bold; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“‹ Ø§Ù„Ù…Ù‡Ø§Ù…</h1>
            {% for t in tasks %}
            <div class="task">
                <span>{{ t.name }}</span>
                <span class="status status-{{ t.status }}">{{ t.status }}</span>
            </div>
            {% endfor %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
    </body>
    </html>
    """, tasks=all_tasks)


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
            .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
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
        from core.monitoring import system_monitor, health_checker
        snap = system_monitor.get_latest_snapshot()
        health = health_checker.run_all_checks()
    except Exception:
        snap = None
        health = {}
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
            .metric { background: #e8f4fd; padding: 15px; border-radius: 6px; margin: 10px 0; }
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


if __name__ == "__main__":
    print("ðŸš€ AETHRA Web Demo ÙŠØ¹Ù…Ù„ Ø¹Ù„Ù‰ http://0.0.0.0:5000")
    print("   Ø§ÙØªØ­ Ø§Ù„Ù…ØªØµÙØ­ Ø¹Ù„Ù‰: http://localhost:5000")
    print("   Ø£Ùˆ Ù…Ù† Ø®Ù„Ø§Ù„ instabox: instabox-port publish 5000")
    app.run(host="0.0.0.0", port=5000, debug=True)
