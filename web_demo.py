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
from flask_socketio import SocketIO, emit

from core.integration import aethra
from core.task import TaskStatus
from devices.notification import notification_manager, NotificationType, NotificationPriority
from devices.registry import DeviceRegistry
from devices.android_adapter import AndroidRuntimeAdapter, LinuxAdapter
from skills.registry import SkillRegistry
from skills.manifest import SkillManifest
from providers.registry import ProviderRegistry
from providers.mock import MockProvider
from providers.ollama import OllamaProvider

app = Flask(__name__)
app.config['SECRET_KEY'] = 'aethra-demo-secret'
socketio = SocketIO(app, cors_allowed_origins="*")

@socketio.on('connect')
def handle_connect():
    emit('connected', {'data': 'ØªÙ… Ø§Ù„Ø§ØªØµØ§Ù„ Ø¨Ø§Ù„Ø®Ø§Ø¯Ù…'})

@socketio.on('task_created')
def handle_task_created(data):
    emit('task_notification', {
        'task_id': data.get('task_id'),
        'name': data.get('name'),
        'status': data.get('status'),
        'message': f'ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© Ø¬Ø¯ÙŠØ¯Ø©: {data.get("name")}'
    }, broadcast=True)
# HTML í…œí”ŒÙŠØª Ù…Ø¹ Ø¯Ø¹Ù… Ø§Ù„Ø¹Ø±Ø¨ÙŠØ© Ø§Ù„ÙƒØ§Ù…Ù„
HTML_TEMPLATE = """
<!DOCTYPE html>
<html lang="ar" dir="rtl">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>AETHRA Demo</title>
    <link rel="icon" href="data:image/svg+xml,%3Csvg xmlns=%27http://www.w3.org/2000/svg%27 viewBox=%270 0 100 100%27%3E%3Ctext y=%27.9em%27 font-size=%2790%27%3E%3CðŸ¤–%3C/text%3E%3C/svg%3E">
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
                {% if task.status == "RUNNING" %}
                <form method="POST" action="/tasks/{{ task.id }}/cancel" style="display:inline;margin-left:10px;">
                    <button type="submit" style="background:#e74c3c;color:white;border:none;padding:6px 12px;border-radius:4px;cursor:pointer;font-size:12px;">Ø¥Ù„ØºØ§Ø¡</button>
                </form>
                {% endif %}
            </div>
            {% endfor %}
        </div>
    </div>
        <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
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
            <button onclick="markAllRead()" style="background:#3498db;color:white;border:none;padding:10px 20px;border-radius:4px;cursor:pointer;margin-left:10px;">Ã˜Â§Ã™Ë†Ã˜Â¶Ã™â€ž Ã˜Â§Ã™â€žÃ˜Â£Ã™Ë†Ã™â€žÃ™Ë†Ã™Å Ã˜Âª Ã˜ÂªÃ˜Â§Ã™â€¡Ã˜Â¡</button>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
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
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
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
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
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
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
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
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
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
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, devices=devices, error=error)


# ===== ØµÙØ­Ø© Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ù…Ù‡Ø§Ø±Ø§Øª =====

@app.route("/skills")
def skills_page():
    """ØµÙØ­Ø© Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ù…Ù‡Ø§Ø±Ø§Øª."""
    skills = []
    try:
        reg = SkillRegistry()
        # Ø¥Ø¶Ø§ÙØ© Ù…Ù‡Ø§Ø±Ø§Øª ØªØ¬Ø±ÙŠØ¨ÙŠØ© Ù„Ù„Ø¹Ø±Ø¶
        reg.register(type('Skill', (), {
            'manifest': type('Manifest', (), {
                'id': 'skill.shell',
                'name': 'Ù…Ù‡Ø§Ø±Ø© Ø§Ù„Ù‚Ø´Ø±Ø©',
                'version': '1.0.0',
                'description': 'ØªÙ†ÙÙŠØ° Ø£ÙˆØ§Ù…Ø± Ø§Ù„Ù‚Ø´Ø±Ø©',
                'author': 'AETHRA',
                'permissions': ['system.shell'],
                'capabilities': ['shell.exec'],
                'platforms': ['linux', 'android'],
                'entrypoint': 'skills/shell/main.py',
            })(),
            'state': type('State', (), {'value': 'ENABLED'})(),
        })())
        reg.register(type('Skill', (), {
            'manifest': type('Manifest', (), {
                'id': 'skill.filesystem',
                'name': 'Ù…Ù‡Ø§Ø±Ø© Ù†Ø¸Ø§Ù… Ø§Ù„Ù…Ù„ÙØ§Øª',
                'version': '0.5.0',
                'description': 'Ù‚Ø±Ø§Ø¡Ø© ÙˆÙƒØªØ§Ø¨Ø© Ø§Ù„Ù…Ù„ÙØ§Øª',
                'author': 'AETHRA',
                'permissions': ['file.read', 'file.write'],
                'capabilities': ['file.read', 'file.write'],
                'platforms': ['linux', 'windows', 'android'],
                'entrypoint': 'skills/fs/main.py',
            })(),
            'state': type('State', (), {'value': 'INSTALLED'})(),
        })())
        skills = reg.list()
    except Exception as e:
        skills = []
        error = str(e)

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ù…Ù‡Ø§Ø±Ø§Øª - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .skill { padding: 15px; border-bottom: 1px solid #eee; margin: 10px 0; border-radius: 6px; background: #fff; }
            .skill:last-child { border-bottom: none; }
            .skill-header { display: flex; justify-content: space-between; margin-bottom: 8px; }
            .skill-name { font-weight: bold; font-size: 16px; }
            .skill-version { padding: 4px 8px; border-radius: 4px; font-size: 12px; background: #ecf0f1; color: #2c3e50; }
            .skill-state { padding: 4px 8px; border-radius: 12px; font-size: 12px; font-weight: bold; }
            .state-ENABLED { background: #e8f5e9; color: #2e7d32; }
            .state-INSTALLED { background: #fff3e0; color: #e65100; }
            .state-DISABLED { background: #ffebee; color: #c62828; }
            .skill-cap { margin: 5px 0; font-size: 13px; color: #555; }
            .skill-perm { margin: 5px 0; font-size: 13px; color: #555; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ”§ Ø§Ù„Ù…Ù‡Ø§Ø±Ø§Øª Ø§Ù„Ù…Ø«Ø¨ØªØ©</h1>
            {% if skills %}
            {% for s in skills %}
            <div class="skill">
                <div class="skill-header">
                    <span class="skill-name">{{ s.manifest.name }}</span>
                    <span class="skill-version">v{{ s.manifest.version }}</span>
                </div>
                <div class="skill-cap">
                    <strong>Ø§Ù„ÙˆØµÙ:</strong> {{ s.manifest.description }}
                </div>
                <div class="skill-cap">
                    <strong>Ø§Ù„Ù…Ø¤Ù„Ù:</strong> {{ s.manifest.author }} | 
                    <strong>Ø§Ù„Ù†Ù‚Ø§Ø·:</strong> {{ s.manifest.platforms | join(', ') }}
                </div>
                <div class="skill-perm">
                    <strong>Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ§Øª:</strong> {{ s.manifest.permissions | join(', ') }}
                </div>
                <div class="skill-perm">
                    <strong>Ø§Ù„Ù‚Ø¯Ø±Ø§Øª:</strong> {{ s.manifest.capabilities | join(', ') }}
                </div>
                <span class="skill-state state-{{ s.state.value }}">{{ s.state.value }}</span>
            </div>
            {% endfor %}
            {% else %}
            <p>Ù„Ø§ ØªÙˆØ¬Ø¯ Ù…Ù‡Ø§Ø±Ø§Øª Ù…Ø«Ø¨ØªØ©.</p>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, skills=skills)


# ===== ØµÙØ­Ø© Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ù…ÙˆÙØ±Ø§Øª =====

@app.route("/providers")
def providers_page():
    """ØµÙØ­Ø© Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ù…ÙˆÙØ±Ø§Øª."""
    providers = []
    try:
        reg = ProviderRegistry()
        reg.register(MockProvider(), is_default=True)
        reg.register(OllamaProvider())
        providers = reg.list_providers()
        available = reg.list_available_providers()
    except Exception as e:
        providers = []
        available = []
        error = str(e)

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ù…ÙˆÙØ±Ø§Øª - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 700px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .provider { padding: 15px; border-bottom: 1px solid #eee; margin: 10px 0; border-radius: 6px; background: #fff; }
            .provider:last-child { border-bottom: none; }
            .provider-header { display: flex; justify-content: space-between; margin-bottom: 8px; }
            .provider-name { font-weight: bold; font-size: 16px; }
            .provider-status { padding: 4px 8px; border-radius: 12px; font-size: 12px; font-weight: bold; }
            .status-available { background: #e8f5e9; color: #2e7d32; }
            .status-unavailable { background: #ffebee; color: #c62828; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ”Œ Ø§Ù„Ù…ÙˆÙØ±Ø§Øª Ø§Ù„Ù…ØªØ§Ø­Ø©</h1>
            {% for p in providers %}
            <div class="provider">
                <div class="provider-header">
                    <span class="provider-name">{{ p.name }}</div>
                    <span class="provider-status {{ 'status-available' if p in available else 'status-unavailable' }}">
                        {% if p in available %}Ù…ØªØ§Ø­{% else %}ØºÙŠØ± Ù…ØªØ§Ø­{% endif %}
                    </span>
                </div>
                <div>
                    <strong>Ø§Ù„Ù…Ø¹Ø±Ù:</strong> {{ p.provider_id }} | 
                    <strong>Ø§Ù„Ù†Ù…Ø§Ø°Ø¬:</strong> {{ p.models | length if p.models else 0 }}
                </div>
            </div>
            {% endfor %}
            {% if not providers %}
            <p>Ù„Ø§ ØªÙˆØ¬Ø¯ Ù…ÙˆÙØ±Ø§Øª.</p>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, providers=providers, available=available)


# ===== ØµÙØ­Ø© Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© =====

@app.route("/create-task", methods=["GET", "POST"])
def create_task_page():
    """ØµÙØ­Ø© Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© Ø¬Ø¯ÙŠØ¯Ø©."""
    result = None
    if request.method == "POST":
        name = request.form.get("name", "").strip()
        command = request.form.get("command", "").strip()
        actor = request.form.get("actor", "web_user").strip()
        if not name or not command:
            result = {"success": False, "error": "Ø§Ù„Ø§Ø³Ù… ÙˆØ§Ù„Ø£Ù…Ø± Ù…Ø·Ù„ÙˆØ¨Ø§Ù†"}
        else:
            try:
                task_id = aethra.task_engine.create_task(
                    name, "shell.exec",
                    metadata={"command": command, "actor": actor},
                )
                task = aethra.task_engine.get_task(task_id)
                result = {
                    "success": True,
                    "task_id": task_id,
                    "name": name,
                    "status": task.status.value,
                }
                # Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø± ØªÙ„Ù‚Ø§Ø¦ÙŠ Ø¹Ù†Ø¯ Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø©
                try:
                    aethra.notification_manager.notify_task_completed(
                        task_id=task_id,
                        task_name=name,
                        target_device_id=None,
                    )
                except Exception:
                    pass
                # Ø¥Ø±Ø³Ø§Ù„ Ø­Ø¯Ø« WebSocket Ù„Ù„ØªØ­Ø¯ÙŠØ« Ø§Ù„Ù„Ø­Ø¸ÙŠ
                try:
                    socketio.emit('task_notification', {
                        'task_id': task_id,
                        'name': name,
                        'status': task.status.value,
                        'message': f'ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© Ø¬Ø¯ÙŠØ¯Ø©: {name}'
                    }, broadcast=True)
                except Exception:
                    pass
            except Exception as e:
                result = {"success": False, "error": str(e)}

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 600px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .form-group { margin-bottom: 15px; }
            label { display: block; margin-bottom: 5px; font-weight: bold; color: #34495e; }
            input[type="text"] { width: 100%; padding: 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
            button { background: #3498db; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; font-size: 14px; }
            button:hover { background: #2980b9; }
            .result { margin-top: 20px; padding: 15px; border-radius: 4px; display: none; }
            .result.success { background: #d4edda; border: 1px solid #c3e6cb; color: #155724; display: block; }
            .result.error { background: #f8d7da; border: 1px solid #f5c6cb; color: #721c24; display: block; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>âž• Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø© Ø¬Ø¯ÙŠØ¯Ø©</h1>
            <form method="POST">
                <div class="form-group">
                    <label>Ø§Ø³Ù… Ø§Ù„Ù…Ù‡Ù…Ø©:</label>
                    <input type="text" name="name" placeholder="Ù…Ø«Ø§Ù„: Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù‚Ø´Ø±Ø©" required>
                </div>
                <div class="form-group">
                    <label>Ø§Ù„Ø£Ù…Ø±:</label>
                    <input type="text" name="command" placeholder="Ù…Ø«Ø§Ù„: echo hello" required>
                </div>
                <div class="form-group">
                    <label>Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…:</label>
                    <input type="text" name="actor" value="web_user">
                </div>
                <button type="send">Ø¥Ù†Ø´Ø§Ø¡ Ø§Ù„Ù…Ù‡Ù…Ø©</button>
            </form>
            {% if result %}
            <div class="result {{ 'success' if result.success else 'error' }}">
                {% if result.success %}
                <strong>ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ø§Ù„Ù…Ù‡Ù…Ø©:</strong> {{ result.name }}<br>
                <strong>Ø§Ù„Ù…Ø¹Ø±Ù:</strong> {{ result.task_id[:8] }}<br>
                <strong>Ø§Ù„Ø­Ø§Ù„Ø©:</strong> {{ result.status }}
                {% else %}
                <strong>Ø®Ø·Ø£:</strong> {{ result.error }}
                {% endif %}
            </div>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, result=result)


# ===== ØµÙØ­Ø© Ø¹Ø±Ø¶ Ø§Ù„Ø°Ø§ÙƒØ±Ø© =====

@app.route("/memory")
def memory_page():
    """ØµÙØ­Ø© Ø¹Ø±Ø¶ Ø¥Ø¯Ø®Ø§Ù„Ø§Øª Ø§Ù„Ø°Ø§ÙƒØ±Ø©."""
    memories = []
    try:
        from core.integration import aethra as _aethra
        mem_engine = _aethra.memory_engine
        for layer in ["conversation", "working", "task", "long_term", "preference"]:
            entries = mem_engine.list_layer(layer)
            for e in entries[:5]:  # Ø£ÙˆÙ„ 5 Ù…Ù† ÙƒÙ„ Ø·Ø¨Ù‚Ø©
                memories.append({
                    "layer": e.layer,
                    "key": e.key,
                    "value": str(e.value)[:100],
                    "source": e.source,
                    "confidence": e.confidence,
                })
    except Exception as e:
        memories = []
        error = str(e)

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ù„Ø°Ø§ÙƒØ±Ø© - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 900px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            table { width: 100%; border-collapse: collapse; }
            th, td { padding: 10px; text-align: right; border-bottom: 1px solid #ddd; }
            th { background: #3498db; color: white; }
            .layer-badge { padding: 4px 8px; border-radius: 4px; font-size: 12px; font-weight: bold; color: white; }
            .layer-conversation { background: #2196f3; }
            .layer-working { background: #4caf50; }
            .layer-task { background: #9c27b0; }
            .layer-long_term { background: #ff9800; }
            .layer-preference { background: #795548; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ§  Ø§Ù„Ø°Ø§ÙƒØ±Ø© Ø§Ù„Ø·Ø¨Ù‚Ø§ØªÙŠØ©</h1>
            {% if memories %}
            <table>
                <tr><th>Ø§Ù„Ø·Ø¨Ù‚Ø©</th><th>Ø§Ù„Ù…ÙØªØ§Ø­</th><th>Ø§Ù„Ù‚ÙŠÙ…Ø©</th><th>Ø§Ù„Ù…ØµØ¯Ø±</th><th>Ø§Ù„Ø«Ù‚Ø©</th></tr>
                {% for m in memories %}
                <tr>
                    <td><span class="layer-badge layer-{{ m.layer }}">{{ m.layer }}</span></td>
                    <td>{{ m.key }}</td>
                    <td>{{ m.value }}</td>
                    <td>{{ m.source }}</td>
                    <td>{{ "%.2f"|format(m.confidence) }}</td>
                </tr>
                {% endfor %}
            </table>
            {% else %}
            <p>Ù„Ø§ ØªÙˆØ¬Ø¯ Ø¥Ø¯Ø®Ø§Ù„Ø§Øª Ø°Ø§ÙƒØ±Ø©.</p>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, memories=memories)


# ===== ØµÙØ­Ø© ØªÙØ§ØµÙŠÙ„ Ø§Ù„Ù…Ù‡Ù…Ø© =====

@app.route("/task/<task_id>")
def task_detail(task_id):
    """ØµÙØ­Ø© ØªÙØ§ØµÙŠÙ„ Ù…Ù‡Ù…Ø© Ù…Ø­Ø¯Ø¯Ø©."""
    task = None
    evidence = None
    try:
        task = aethra.task_engine.get_task(task_id)
        if task:
            # Ø¬Ù…Ø¹ Ø£Ø¯Ù„Ø© Ø§Ù„ØªÙ†ÙÙŠØ° Ù…Ù† Ø§Ù„Ø°Ø§ÙƒØ±Ø©
            rec = aethra.memory_engine.read_record("task", f"task:{task_id}:result")
            if rec:
                evidence = rec.value
    except Exception as e:
        task = None
        error = str(e)

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>ØªÙØ§ØµÙŠÙ„ Ø§Ù„Ù…Ù‡Ù…Ø© - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 700px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .detail { padding: 15px; border-bottom: 1px solid #eee; }
            .detail:last-child { border-bottom: none; }
            .detail-label { font-weight: bold; color: #34495e; }
            .status-badge { padding: 6px 12px; border-radius: 20px; font-size: 13px; font-weight: bold; }
            .status-QUEUED { background: #fff3e0; color: #e65100; }
            .status-RUNNING { background: #e3f2fd; color: #1565c0; }
            .status-COMPLETED { background: #e8f5e9; color: #2e7d32; }
            .status-FAILED { background: #ffebee; color: #c62828; }
            .status-CANCELLED { background: #eceff1; color: #546e7a; }
            .evidence { background: #e8f4fd; padding: 15px; border-radius: 6px; margin: 10px 0; border-left: 4px solid #3498db; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“‹ ØªÙØ§ØµÙŠÙ„ Ø§Ù„Ù…Ù‡Ù…Ø©</h1>
            {% if task %}
            <div class="detail">
                <span class="detail-label">Ø§Ù„Ù…Ø¹Ø±Ù:</span> {{ task.id }}
            </div>
            <div class="detail">
                <span class="detail-label">Ø§Ù„Ø§Ø³Ù…:</span> {{ task.name }}
            </div>
            <div class="detail">
                <span class="detail-label">Ø§Ù„Ø­Ø§Ù„Ø©:</span>
                <span class="status-badge status-{{ task.status }}">{{ task.status }}</span>
            </div>
            <div class="detail">
                <span class="detail-label">Ø§Ù„Ø¹Ø¯Ø¯ Ù…Ø­Ø§ÙˆÙ„Ø§Øª Ø¥Ø¹Ø§Ø¯Ø©:</span> {{ task.retry_count }}
            </div>
            <div class="detail">
                <span class="detail-label">Ø§Ù„ÙˆÙ‚Øª Ø§Ù„Ø¥Ù†Ø´Ø§Ø¡:</span> {{ "%.0f"|format(task.created_at) if task.created_at else "-" }}
            </div>
            <div class="detail">
                <span class="detail-label">Ø§Ù„ÙˆÙ‚Øª Ø§Ù„Ø¨Ø¯Ø¡:</span> {{ "%.0f"|format(task.started_at) if task.started_at else "-" }}
            </div>
            <div class="detail">
                <span class="detail-label">ÙˆÙ‚Øª Ø§Ù„Ø§Ù†ØªÙ‡Ø§Ø¡:</span> {{ "%.0f"|format(task.completed_at) if task.completed_at else "-" }}
            </div>
            {% if task.error %}
            <div class="detail" style="color: #c62828;">
                <span class="detail-label">Ø§Ù„Ø®Ø·Ø£:</span> {{ task.error }}
            </div>
            {% endif %}
            {% if evidence %}
            <div class="evidence">
                <span class="detail-label">Ø£Ø¯Ù„Ø© Ø§Ù„ØªÙ†ÙÙŠØ°:</span>
                <pre>{{ evidence }}</pre>
            </div>
            {% endif %}
            {% else %}
            <p>Ø§Ù„Ù…Ù‡Ù…Ø© ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯Ø©.</p>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, task=task, evidence=evidence)


# ===== ØµÙØ­Ø© Ø³Ø¬Ù„ Ø§Ù„Ø£Ø­Ø¯Ø§Ø« =====

@app.route("/events")
def events_page():
    """ØµÙØ­Ø© Ø³Ø¬Ù„ Ø§Ù„Ø£Ø­Ø¯Ø§Ø« Ù…Ù† EventBus."""
    events = []
    try:
        from core.integration import aethra as _aethra
        event_bus = _aethra.event_bus
        recent = event_bus.get_history(limit=30)
        for e in recent:
            events.append({
                "type": e.type,
                "source": e.source,
                "timestamp": e.timestamp,
                "payload": str(e.payload)[:100] if e.payload else "",
            })
    except Exception as e:
        events = []
        error = str(e)

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø³Ø¬Ù„ Ø§Ù„Ø£Ø­Ø¯Ø§Ø« - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 900px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            table { width: 100%; border-collapse: collapse; }
            th, td { padding: 8px; text-align: right; border-bottom: 1px solid #ddd; }
            th { background: #3498db; color: white; }
            .event-type { font-weight: bold; color: #2980b9; }
            .event-source { color: #7f8c8d; font-size: 12px; }
            .event-payload { color: #555; font-size: 12px; max-width: 300px; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ“¡ Ø³Ø¬Ù„ Ø§Ù„Ø£Ø­Ø¯Ø§Ø« (EventBus)</h1>
            {% if events %}
            <table>
                <tr><th>Ø§Ù„ÙˆÙ‚Øª</th><th>Ø§Ù„Ù†ÙˆØ¹</th><th>Ø§Ù„Ù…ØµØ¯Ø±</th><th>Ø§Ù„Ø­Ù…ÙˆÙ„Ø©</th></tr>
                {% for e in events %}
                <tr>
                    <td>{{ "%.0f"|format(e.timestamp) }}</td>
                    <td class="event-type">{{ e.type }}</td>
                    <td class="event-source">{{ e.source }}</td>
                    <td class="event-payload">{{ e.payload }}</td>
                </tr>
                {% endfor %}
            </table>
            {% else %}
            <p>Ù„Ø§ ØªÙˆØ¬Ø¯ Ø£Ø­Ø¯Ø§Ø«.</p>
            {% endif %}
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, events=events)


# ===== ØµÙØ­Ø© ØªÙƒÙˆÙŠÙ† Ø§Ù„Ù…ÙˆÙØ±Ø§Øª =====

@app.route("/providers/config", methods=["GET", "POST"])
def providers_config():
    """ØµÙØ­Ø© ØªÙƒÙˆÙŠÙ† API Keys Ù„Ù„Ù…ÙˆÙØ±Ø§Øª."""
    providers_info = [
        {"id": "openai", "name": "OpenAI", "env_var": "OPENAI_API_KEY", "models": ["gpt-4o", "gpt-4o-mini", "gpt-3.5-turbo"]},
        {"id": "anthropic", "name": "Anthropic", "env_var": "ANTHROPIC_API_KEY", "models": ["claude-3-5-sonnet", "claude-3-haiku"]},
        {"id": "gemini", "name": "Google Gemini", "env_var": "GEMINI_API_KEY", "models": ["gemini-1.5-pro", "gemini-1.5-flash"]},
        {"id": "openrouter", "name": "OpenRouter", "env_var": "OPENROUTER_API_KEY", "models": ["openrouter/auto"]},
        {"id": "ollama", "name": "Ollama (Ù…Ø­Ù„ÙŠ)", "env_var": "OLLAMA_BASE_URL", "models": ["llama3", "codellama", "mistral"]},
    ]

    message = None
    if request.method == "POST":
        provider_id = request.form.get("provider_id", "").strip()
        api_key = request.form.get("api_key", "").strip()
        if provider_id and api_key:
            import os as _os
            _os.environ[provider_id.upper() + "_API_KEY"] = api_key
            message = {"success": True, "provider": provider_id, "message": "ØªÙ… Ø­ÙØ¸ Ù…ÙØªØ§Ø­ API Ø¨Ù†Ø¬Ø§Ø­"}
        else:
            message = {"success": False, "error": "Ø§Ù„Ù…Ø¹Ø±Ù ÙˆØ§Ù„Ù…ÙØªØ§Ø­ Ù…Ø·Ù„ÙˆØ¨Ø§Ù†"}

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>ØªÙƒÙˆÙŠÙ† Ø§Ù„Ù…ÙˆÙØ±Ø§Øª - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 700px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .form-group { margin-bottom: 15px; }
            label { display: block; margin-bottom: 5px; font-weight: bold; color: #34495e; }
            input[type="text"], select { width: 100%; padding: 10px; border: 1px solid #ddd; border-radius: 4px; font-size: 14px; }
            button { background: #3498db; color: white; border: none; padding: 10px 20px; border-radius: 4px; cursor: pointer; font-size: 14px; }
            button:hover { background: #2980b9; }
            .result { margin-top: 20px; padding: 15px; border-radius: 4px; display: none; }
            .result.success { background: #d4edda; border: 1px solid #c3e6cb; color: #155724; display: block; }
            .result.error { background: #f8d7da; border: 1px solid #f5c6cb; color: #721c24; display: block; }
            .provider-list { margin-top: 30px; }
            .provider-item { padding: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center; }
            .provider-item:last-child { border-bottom: none; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ”§ ØªÙƒÙˆÙŠÙ† Ø§Ù„Ù…ÙˆÙØ±Ø§Øª</h1>
            
            <form method="POST">
                <div class="form-group">
                    <label>Ø§Ø®ØªØ± Ø§Ù„Ù…Ø²ÙˆØ¯:</label>
                    <select name="provider_id" required>
                        {% for p in providers %}
                        <option value="{{ p.id }}">{{ p.name }}</option>
                        {% endfor %}
                    </select>
                </div>
                <div class="form-group">
                    <label>Ù…ÙØªØ§Ø­ API:</label>
                    <input type="text" name="api_key" placeholder="Ø£Ø¯Ø®Ù„ Ù…ÙØªØ§Ø­ API" required>
                </div>
                <button type="send">Ø­ÙØ¸ Ø§Ù„Ù…ÙØªØ§Ø­</button>
            </form>
            
            {% if message %}
            <div class="result {{ 'success' if message.success else 'error' }}">
                {% if message.success %}
                <strong>ØªÙ… Ø§Ù„Ø­ÙØ¸:</strong> {{ message.message }} ({{ message.provider }})
                {% else %}
                <strong>Ø®Ø·Ø£:</strong> {{ message.error }}
                {% endif %}
            </div>
            {% endif %}

            <div class="provider-list">
                <h3>Ø§Ù„Ù…ÙˆÙØ±Ø§Øª Ø§Ù„Ù…Ø¯Ø¹ÙˆÙ…Ø©:</h3>
                {% for p in providers %}
                <div class="provider-item">
                    <span>{{ p.name }}</span>
                    <span>
                        <strong>Ù…ØªØºÙŠØ± Ø§Ù„Ø¨ÙŠØ¦Ø©:</strong> {{ p.env_var }} |
                        <strong>Ø§Ù„Ù†Ù…Ø§Ø°Ø¬:</strong> {{ p.models | join(', ') }}
                    </span>
                </div>
                {% endfor %}
            </div>
            
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, providers=providers_info, message=message)


# ===== ØµÙØ­Ø© Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù†Ø¸Ø§Ù… Ø§Ù„ÙƒØ§Ù…Ù„ =====

@app.route("/system-test")
def system_test_page():
    """ØµÙØ­Ø© Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù†Ø¸Ø§Ù… Ø§Ù„ÙƒØ§Ù…Ù„ Ù…Ù† Ø§Ù„Ù†Ù‡Ø§ÙŠØ© Ù„Ù„Ù†Ù‡Ø§ÙŠØ©."""
    test_result = None
    try:
        from core.integration import aethra as _aethra
        from core.task import TaskStatus
        
        # Ø§Ù„Ø®Ø·ÙˆØ© 1: Ø¥Ù†Ø´Ø§Ø¡ Ù…Ù‡Ù…Ø©
        task_id = _aethra.task_engine.create_task(
            "system_test_task", "shell.exec",
            metadata={"command": "echo 'AETHRA System Test Success'", "actor": "system_test"},
        )
        task = _aethra.task_engine.get_task(task_id)
        
        # Ø§Ù„Ø®Ø·ÙˆØ© 2: Ø·Ù„Ø¨ Ø§Ù„ØªÙ†ÙÙŠØ° Ø¹Ø¨Ø± ToolDispatcher (ÙŠÙ…Ø± Ø¹Ø¨Ø± Policy/Permission)
        gate = _aethra.tool_dispatcher.request_execution(
            tool_id="shell.exec",
            params={"command": "echo 'AETHRA System Test Success'"},
            actor="system_test",
        )
        _aethra.tool_dispatcher.approve_gate(gate.tool_id, "system_test")
        
        # Ø§Ù„Ø®Ø·ÙˆØ© 3: Ø§Ù„ØªÙ†ÙÙŠØ°
        exec_result = _aethra.tool_dispatcher.execute(
            tool_id="shell.exec",
            params={"command": "echo 'AETHRA System Test Success'"},
            actor="system_test",
            gate_id=gate.tool_id,
        )
        
        # Ø§Ù„Ø®Ø·ÙˆØ© 4: Ø§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ù†ØªÙŠØ¬Ø©
        verif_result = _aethra.verification_engine.verify(
            action="system_test",
            actual=exec_result.output,
            expected="AETHRA System Test Success",
            action_type="shell_output",
        )
        
        # Ø§Ù„Ø®Ø·ÙˆØ© 5: Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø±
        _aethra.notification_manager.notify_task_completed(
            task_id=task_id,
            task_name="system_test_task",
        )
        
        # Ø§Ù„Ø®Ø·ÙˆØ© 6: ØªØ­Ø¯ÙŠØ« Ø§Ù„Ù…Ù‡Ù…Ø©
        if exec_result.success:
            task.mark_completed(exec_result.output)
        
        test_result = {
            "success": exec_result.success and verif_result.passed,
            "task_id": task_id,
            "task_name": "system_test_task",
            "task_status": task.status.value,
            "command_output": exec_result.output.strip() if exec_result.output else "",
            "verification_passed": verif_result.passed,
            "verification_message": verif_result.message,
            "notification_created": True,
            "steps": [
                {"step": "Ø¥Ù†Ø´Ø§Ø¡ Ø§Ù„Ù…Ù‡Ù…Ø©", "status": "âœ“ Ù…ÙƒØªÙ…Ù„", "detail": f"Ø§Ù„Ù…Ø¹Ø±Ù: {task_id[:8]}"},
                {"step": "Ø·Ù„Ø¨ Ø§Ù„ØªÙ†ÙÙŠØ°", "status": "âœ“ Ù…ÙƒØªÙ…Ù„", "detail": "ØªÙ… Ø§Ù„Ù…Ø±ÙˆØ± Ø¹Ø¨Ø± Policy/Permission"},
                {"step": "Ø§Ù„ØªÙ†ÙÙŠØ°", "status": "âœ“ Ù…ÙƒØªÙ…Ù„" if exec_result.success else "âœ— ÙØ´Ù„", "detail": exec_result.output.strip() if exec_result.output else exec_result.error},
                {"step": "Ø§Ù„ØªØ­Ù‚Ù‚", "status": "âœ“ Ù…ÙƒØªÙ…Ù„" if verif_result.passed else "âœ— ÙØ´Ù„", "detail": verif_result.message},
                {"step": "Ø¥Ù†Ø´Ø§Ø¡ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±", "status": "âœ“ Ù…ÙƒØªÙ…Ù„", "detail": "ØªÙ… Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø± Ø¥ØªÙ…Ø§Ù… Ø§Ù„Ù…Ù‡Ù…Ø©"},
            ],
        }
    except Exception as e:
        test_result = {
            "success": False,
            "error": str(e),
        }

    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù†Ø¸Ø§Ù… - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 700px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .test-result { padding: 20px; border-radius: 8px; margin: 20px 0; }
            .test-result.success { background: #d4edda; border: 1px solid #c3e6cb; color: #155724; }
            .test-result.failed { background: #f8d7da; border: 1px solid #f5c6cb; color: #721c24; }
            .test-step { padding: 10px; border-bottom: 1px solid #eee; display: flex; justify-content: space-between; align-items: center; }
            .test-step:last-child { border-bottom: none; }
            .step-status { font-weight: bold; }
            .step-detail { color: #555; font-size: 13px; }
            .btn { background: #3498db; color: white; border: none; padding: 12px 24px; border-radius: 4px; cursor: pointer; font-size: 14px; margin: 10px 0; }
            .btn:hover { background: #2980b9; }
            .back { display: inline-block; color: #3498db; text-decoration: none; margin-top: 20px; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ§ª Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù†Ø¸Ø§Ù… Ø§Ù„ÙƒØ§Ù…Ù„</h1>
            
            <button class="btn" onclick="location.href='location.href'">Ø¥Ø¹Ø§Ø¯Ø© Ø§Ù„ØªØ´ØºÙŠÙ„</button>
            
            {% if test_result %}
            {% if test_result.success %}
            <div class="test-result success">
                <h2>âœ… Ù†Ø¬Ø­ Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù†Ø¸Ø§Ù… Ø¨Ø§Ù„ÙƒØ§Ù…Ù„!</h2>
                <p><strong>Ø§Ù„Ù…Ù‡Ù…Ø©:</strong> {{ test_result.task_name }}</p>
                <p><strong>Ø§Ù„Ù…Ø¹Ø±Ù:</strong> {{ test_result.task_id[:8] }}</p>
                <p><strong>Ø§Ù„Ø­Ø§Ù„Ø©:</strong> {{ test_result.task_status }}</p>
                <p><strong>Ù…Ø®Ø±Ø¬Ø§Øª Ø§Ù„Ø£Ù…Ø±:</strong> {{ test_result.command_output }}</p>
                <p><strong>Ø§Ù„ØªØ­Ù‚Ù‚:</strong> {{ test_result.verification_message }}</p>
                <p><strong>Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±:</strong> ØªÙ… Ø¥Ù†Ø´Ø§Ø¤Ù‡ Ø¨Ù†Ø¬Ø§Ø­</p>
            </div>
            {% else %}
            <div class="test-result failed">
                <h2>âŒ ÙØ´Ù„ Ø§Ø®ØªØ¨Ø§Ø± Ø§Ù„Ù†Ø¸Ø§Ù…</h2>
                <p><strong>Ø§Ù„Ø®Ø·Ø£:</strong> {{ test_result.error }}</p>
            </div>
            {% endif %}
            
            <h3>Ø®Ø·ÙˆØ§Øª Ø§Ù„Ø§Ø®ØªØ¨Ø§Ø±:</h3>
            {% for step in test_result.steps %}
            <div class="test-step">
                <span>{{ step.step }}</span>
                <span class="step-status">{{ step.status }}</span>
                <span class="step-detail">{{ step.detail }}</span>
            </div>
            {% endfor %}
            {% endif %}
            
            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
            <script>
            setInterval(function() {
                fetch(window.location.href)
                    .then(response => response.text())
                    .then(html => {
                        const parser = new DOMParser();
                        const doc = parser.parseFromString(html, 'text/html');
                        const newTasks = doc.querySelectorAll('.task-item');
                        const oldTasks = document.querySelectorAll('.task-item');
                        if (newTasks.length === oldTasks.length) {
                            newTasks.forEach((newTask, idx) => {
                                if (newTask.innerHTML !== oldTasks[idx].innerHTML) {
                                    oldTasks[idx].innerHTML = newTask.innerHTML;
                                }
                            });
                        }
                    })
                    .catch(() => {});
            }, 5000);
        </script>
        </body>
    </html>
    """, test_result=test_result)


# ===== Ù†Ù‚Ø·Ø© Ù†Ù‡Ø§ÙŠØ© ØµØ­Ø© Ø§Ù„Ù†Ø¸Ø§Ù… =====

@app.route("/api/health")
def api_health():
    """Ù†Ù‚Ø·Ø© Ù†Ù‡Ø§ÙŠØ© Ù„Ø­Ø§Ù„Ø© Ø§Ù„Ù†Ø¸Ø§Ù… Ø¨ØµÙŠØºØ© JSON."""
    health = {
        "status": "healthy",
        "components": {},
        "timestamp": None,
    }
    try:
        import time
        health["timestamp"] = time.time()
        
        # ÙØ­Øµ Core
        try:
            _aethra.task_engine.list_tasks()
            health["components"]["core"] = "healthy"
        except Exception as e:
            health["components"]["core"] = f"unhealthy: {str(e)}"
            health["status"] = "degraded"
        
        # ÙØ­Øµ Ø§Ù„Ø£Ø¯ÙˆØ§Øª
        try:
            _aethra.tool_registry.list()
            health["components"]["tools"] = "healthy"
        except Exception as e:
            health["components"]["tools"] = f"unhealthy: {str(e)}"
            health["status"] = "degraded"
        
        # ÙØ­Øµ Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ§Øª
        try:
            _aethra.permission_broker.get_pending_approvals()
            health["components"]["policy"] = "healthy"
        except Exception as e:
            health["components"]["policy"] = f"unhealthy: {str(e)}"
            health["status"] = "degraded"
        
        # ÙØ­Øµ Ø§Ù„Ù…ÙˆÙØ±Ø§Øª
        try:
            _aethra.provider_registry.list_providers()
            health["components"]["providers"] = "healthy"
        except Exception as e:
            health["components"]["providers"] = f"unhealthy: {str(e)}"
            health["status"] = "degraded"
        
        # ÙØ­Øµ Ø§Ù„Ø°Ø§ÙƒØ±Ø©
        try:
            _aethra.memory_engine.get_stats()
            health["components"]["memory"] = "healthy"
        except Exception as e:
            health["components"]["memory"] = f"unhealthy: {str(e)}"
            health["status"] = "degraded"
        
        # ÙØ­Øµ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª
        try:
            _aethra.notification_manager.get_stats()
            health["components"]["notifications"] = "healthy"
        except Exception as e:
            health["components"]["notifications"] = f"unhealthy: {str(e)}"
            health["status"] = "degraded"
            
    except Exception as e:
        health["status"] = "unhealthy"
        health["error"] = str(e)
    
    return jsonify(health)


# ===== Ù†Ù‚Ø·Ø© Ù†Ù‡Ø§ÙŠØ© ØªØ­Ø¯ÙŠØ¯ Ø§Ù„ÙƒÙ„ ÙƒÙ…Ù‚Ø±ÙˆØ¡ =====

@app.route("/notifications/mark-all-read", methods=["POST"])
def notifications_mark_all_read():
    """ØªØ­Ø¯ÙŠØ¯ Ø¬Ù…ÙŠØ¹ Ø§Ù„Ø¥Ø´Ø¹Ø§Ø±Ø§Øª ÙƒÙ…Ù‚Ø±ÙˆØ¡Ø©."""
    try:
        notifs = aethra.notification_manager.get_notifications()
        count = 0
        for n in notifs:
            if not n.read:
                _aethra.notification_manager.mark_read(n.id)
                count += 1
        return jsonify({"success": True, "marked": count})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


if __name__ == "__main__":
    print("ðŸš€ AETHRA Web Demo ÙŠØ¹Ù…Ù„ Ø¹Ù„Ù‰ http://0.0.0.0:5000")
    print("   Ø§ÙØªØ­ Ø§Ù„Ù…ØªØµÙØ­ Ø¹Ù„Ù‰: http://localhost:5000")
    print("   Ø£Ùˆ Ù…Ù† Ø®Ù„Ø§Ù„ instabox: instabox-port publish 5000")
    socketio.run(app, host="0.0.0.0", port=5000, debug=True)

@app.route("/tasks/<task_id>/cancel", methods=["POST"])
def cancel_task(task_id):
    """Ø¥Ù„ØºØ§Ø¡ Ù…Ù‡Ù…Ø© Ù‚ÙŠØ¯ Ø§Ù„ØªÙ†ÙÙŠØ°."""
    try:
        task = _aethra.task_engine.get_task(task_id)
        if task is None:
            return jsonify({"success": False, "error": "Ø§Ù„Ù…Ù‡Ù…Ø© ØºÙŠØ± Ù…ÙˆØ¬ÙˆØ¯Ø©"}), 404
        
        if task.status in [TaskStatus.COMPLETED, TaskStatus.FAILED, TaskStatus.CANCELLED]:
            return jsonify({"success": False, "error": "Ø§Ù„Ù…Ù‡Ù…Ø© Ø§Ù†ØªÙ‡Øª Ø¨Ø§Ù„ÙØ¹Ù„ ÙˆÙ„Ø§ ÙŠÙ…ÙƒÙ† Ø¥Ù„ØºØ§Ø¤Ù‡Ø§"}), 400
        
        task.status = TaskStatus.CANCELLED
        task.completed_at = __import__("time").time()
        
        # Ø¥Ù†Ø´Ø§Ø¡ Ø¥Ø´Ø¹Ø§Ø± Ø¨Ø§Ù„Ø¥Ù„ØºØ§Ø¡
        _aethra.notification_manager.notify_task_failed(
            task_id=task_id,
            task_name=task.name,
            error="ØªÙ… Ø§Ù„Ø¥Ù„ØºØ§Ø¡ Ù…Ù† Ù‚Ø¨Ù„ Ø§Ù„Ù…Ø³ØªØ®Ø¯Ù…",
        )
        
        return jsonify({"success": True, "task_id": task_id, "status": "CANCELLED"})
    except Exception as e:
        return jsonify({"success": False, "error": str(e)}), 500


# ===== ØµÙØ­Ø© Ø­ÙˆÙ„ AETHRA =====

@app.route("/about")
def about_page():
    """ØµÙØ­Ø© Ø­ÙˆÙ„ Ù…Ø´Ø±ÙˆØ¹ AETHRA."""
    return render_template_string("""
    <!DOCTYPE html>
    <html lang="ar" dir="rtl">
    <head>
        <meta charset="UTF-8">
        <title>Ø­ÙˆÙ„ AETHRA - AETHRA Demo</title>
        <style>
            body { font-family: Arial, sans-serif; padding: 20px; direction: rtl; background: #f5f5f5; }
            .container { max-width: 700px; margin: 0 auto; background: white; padding: 20px; border-radius: 8px; box-shadow: 0 2px 5px rgba(0,0,0,0.1); }
            h1 { color: #333; border-bottom: 2px solid #3498db; padding-bottom: 10px; }
            .feature { padding: 10px; border-bottom: 1px solid #eee; margin: 10px 0; border-radius: 6px; background: #fff; }
            .feature:last-child { border-bottom: none; }
            .back { margin-top: 20px; display: inline-block; color: #3498db; text-decoration: none; }
            .badge { background: #3498db; color: white; padding: 4px 8px; border-radius: 12px; font-size: 12px; margin-right: 5px; }
        </style>
    </head>
    <body>
        <div class="container">
            <h1>ðŸ¤– Ø­ÙˆÙ„ AETHRA</h1>
            <p><strong>AETHRA</strong> Ù‡ÙŠ Ø¨ÙŠØ¦Ø© ØªÙ†ÙÙŠØ° Ø´Ø®ØµÙŠØ© Ù„Ù„Ø°ÙƒØ§Ø¡ Ø§Ù„Ø§ØµØ·Ù†Ø§Ø¹ÙŠ (Personal AI Execution Environment) ØªØ¹Ù…Ù„ Ø¹Ø¨Ø± Ø£Ø¬Ù‡Ø²Ø© Ù…ØªØ¹Ø¯Ø¯Ø© ÙˆØªØ¬Ù…Ø¹ Ø§Ù„Ù†ÙˆØ§Ø© Ø§Ù„Ø°ÙƒÙŠØ©ØŒ Ø§Ù„ÙˆÙƒÙ„Ø§Ø¡ØŒ Ø§Ù„Ù…Ù‡Ø§Ø±Ø§ØªØŒ Ø§Ù„Ø£Ø¯ÙˆØ§ØªØŒ Ø§Ù„Ø°Ø§ÙƒØ±Ø©ØŒ Ø³ÙŠØ± Ø§Ù„Ø¹Ù…Ù„ØŒ Ù…Ø²ÙˆØ¯ÙŠ Ø§Ù„Ù†Ù…Ø§Ø°Ø¬ØŒ Ø§Ù„Ø³ÙŠØ§Ø³Ø§Øª ÙˆØ§Ù„ØµÙ„Ø§Ø­ÙŠØ§ØªØŒ Ø§Ù„ØªØ­Ù‚Ù‚ØŒ ÙˆØ§Ù„ØªÙƒØ§Ù…Ù„ Ø§Ù„Ø¢Ù…Ù† Ø¨ÙŠÙ† Ø§Ù„Ø£Ø¬Ù‡Ø²Ø© ÙÙŠ Ù†Ø¸Ø§Ù… ÙˆØ§Ø­Ø¯.</p>
            
            <h3>Ø§Ù„Ù…ÙŠØ²Ø§Øª Ø§Ù„Ø±Ø¦ÙŠØ³ÙŠØ©:</h3>
            {% for feature in features %}
            <div class="feature">
                <span class="badge">{{ feature.icon }}</span> {{ feature.title }}
                <p>{{ feature.description }}</p>
            </div>
            {% endfor %}

            <h3>Ø§Ù„Ù…ÙƒÙˆÙ†Ø§Øª Ø§Ù„ØªÙ‚Ù†ÙŠØ©:</h3>
            <ul>
                <li>Core: TaskEngine, PermissionBroker, EventBus, MemoryEngine, VerificationEngine, Workflow, PersistenceManager</li>
                <li>Tools: ToolRegistry, ToolDispatcher, ShellExecutor, FileExecutor, TaskRunner</li>
                <li>Providers: OpenAI, Anthropic, Gemini, Ollama, OpenRouter, Mock</li>
                <li>Devices: Android, Termux, Linux, Windows (via NectarLink)</li>
                <li>Skills: Manifest, Registry, Validator, Installer, Trust Model</li>
                <li>Memory: Layered memory (conversation, working, task, long_term, preference)</li>
                <li>Security: Risk levels, Approval gates, Audit log</li>
                <li>Voice: STT, TTS, Wake Word, Pipeline</li>
                <li>UI: Hermes-style Chat + Terminal + streaming + tool cards</li>
            </ul>

            <a href="/" class="back">â† Ø§Ù„Ø¹ÙˆØ¯Ø© Ù„Ù„Ø±Ø¦ÙŠØ³ÙŠØ©</a>
        </div>
        <script>
            function markAllRead() {
                fetch('/notifications/mark-all-read', {method: 'POST'})
                    .then(r => r.json())
                    .then(data => {
                        if (data.success) {
                            location.reload();
                        }
                    });
            }
        </script>
    </body>
    </html>
    """, features=[
        {"icon": "ðŸ§ ", "title": "Ø§Ù„Ù†ÙˆØ§Ø© Ø§Ù„Ø°ÙƒÙŠØ© (Core)", "description": "Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ù…Ù‡Ø§Ù…ØŒ Ø³ÙŠØ± Ø§Ù„Ø¹Ù…Ù„ØŒ Ø§Ù„Ø°Ø§ÙƒØ±Ø©ØŒ Ø§Ù„ØµÙ„Ø§Ø­ÙŠØ§ØªØŒ ÙˆØ§Ù„ØªØ­Ù‚Ù‚ Ù…Ù† Ø§Ù„Ù†ØªØ§Ø¦Ø¬."},
        {"icon": "ðŸ”§", "title": "Ø§Ù„Ø£Ø¯ÙˆØ§Øª (Tools)", "description": "ØªÙ†ÙÙŠØ° Ø£ÙˆØ§Ù…Ø± Ø§Ù„Ù‚Ø´Ø±Ø©ØŒ Ø¹Ù…Ù„ÙŠØ§Øª Ø§Ù„Ù…Ù„ÙØ§ØªØŒ ÙˆØ§Ù„ØªÙƒØ§Ù…Ù„ Ù…Ø¹ Ø£Ø¯ÙˆØ§Øª Ø®Ø§Ø±Ø¬ÙŠØ©."},
        {"icon": "ðŸ¤–", "title": "Ù…Ø²ÙˆØ¯ÙŠ Ø§Ù„Ù†Ù…Ø§Ø°Ø¬ (Providers)", "description": "Ø¯Ø¹Ù… OpenAI, Anthropic, Gemini, Ollama, OpenRouter, ÙˆÙ…Ø²ÙˆØ¯ ÙˆÙ‡Ù…ÙŠ Ù„Ù„Ø§Ø®ØªØ¨Ø§Ø±."},
        {"icon": "ðŸ“±", "title": "Ø¥Ø¯Ø§Ø±Ø© Ø§Ù„Ø£Ø¬Ù‡Ø²Ø© (Device Mesh)", "description": "Ø¯Ø¹Ù… Android, Termux, Linux, Windows Ù…Ø¹ Ù‚Ø¯Ø±Ø§Øª Ù…Ø®ØªÙ„Ù‚Ø© Ù„ÙƒÙ„ Ø¬Ù‡Ø§Ø²."},
        {"icon": "ðŸŽ¤", "title": "Ø§Ù„ØµÙˆØª (Voice)", "description": "Ø¯Ø¹Ù… STT, TTS, Wake Word Ù…Ø¹ ÙˆØ§Ø¬Ù‡Ø© ØµÙˆØªÙŠØ© ØªÙØ§Ø¹Ù„ÙŠØ©."},
        {"icon": "ðŸŒ", "title": "ÙˆØ§Ø¬Ù‡Ø© Ø§Ù„ÙˆÙŠØ¨ (Web UI)", "description": "ÙˆØ§Ø¬Ù‡Ø© ÙˆÙŠØ¨ ØªÙØ§Ø¹Ù„ÙŠØ© Ù…Ø¹ Ø¯Ø¹Ù… PWAØŒ WebSocket Ù„Ù„ØªØ­Ø¯ÙŠØ«Ø§Øª Ø§Ù„Ù„Ø­Ø¸ÙŠØ©ØŒ ÙˆØ¯Ø¹Ù… Ø§Ù„Ù„ØºØ© Ø§Ù„Ø¹Ø±Ø¨ÙŠØ©."},
    ])


if __name__ == "__main__":
    print("ðŸš€ AETHRA Web Demo ÙŠØ¹Ù…Ù„ Ø¹Ù„Ù‰ http://0.0.0.0:5000")
    print("   Ø§ÙØªØ­ Ø§Ù„Ù…ØªØµÙØ­ Ø¹Ù„Ù‰: http://localhost:5000")
    print("   Ø£Ùˆ Ù…Ù† Ø®Ù„Ø§Ù„ instabox: instabox-port publish 5000")
    socketio.run(app, host="0.0.0.0", port=5000, debug=True)
