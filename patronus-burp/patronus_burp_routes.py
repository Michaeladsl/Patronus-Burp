"""
patronus_burp_routes.py
=======================
Drop this file into your Patronus directory and add one import to server.py:

    from patronus_burp_routes import register_burp_routes
    register_burp_routes(app)

This adds two new Flask routes:
  GET /burp                  → Lists all Burp session JSON files
  GET /burp/<filename>       → Serves a specific session as a parsed view
  GET /burp/raw/<filename>   → Returns the raw JSON

The Patronus web UI can then show terminal recordings AND Burp sessions
side by side in one unified view.
"""

import os
import json
from flask import Blueprint, render_template_string, jsonify, abort, send_file
from pathlib import Path

BURP_SESSIONS_DIR = Path.home() / ".patronus" / "burp_sessions"

burp_bp = Blueprint("burp", __name__)


def register_burp_routes(app):
    """Call this from server.py to register all Burp routes."""
    BURP_SESSIONS_DIR.mkdir(parents=True, exist_ok=True)
    app.register_blueprint(burp_bp)
    print(f"[Patronus] Burp routes registered — sessions dir: {BURP_SESSIONS_DIR}")


@burp_bp.route("/burp")
def burp_index():
    """List all available Burp session files."""
    sessions = []
    for f in sorted(BURP_SESSIONS_DIR.glob("*.json"), reverse=True):
        try:
            data = json.loads(f.read_text())
            summary = data.get("summary", {})
            sessions.append({
                "filename": f.name,
                "engagement": summary.get("engagement", f.stem),
                "total": summary.get("totalRequests", 0),
                "flagged": summary.get("flaggedCount", 0),
                "by_tool": summary.get("byTool", {}),
            })
        except Exception:
            sessions.append({"filename": f.name, "engagement": f.stem, "total": "?", "flagged": 0, "by_tool": {}})

    return render_template_string(BURP_INDEX_TEMPLATE, sessions=sessions)


@burp_bp.route("/burp/raw/<filename>")
def burp_raw(filename):
    """Return the raw JSON for a session file."""
    safe = Path(filename).name  # prevent path traversal
    path = BURP_SESSIONS_DIR / safe
    if not path.exists() or path.suffix != ".json":
        abort(404)
    return jsonify(json.loads(path.read_text()))


@burp_bp.route("/burp/<filename>")
def burp_session(filename):
    """Render a Burp session file as an interactive HTML page."""
    safe = Path(filename).name
    path = BURP_SESSIONS_DIR / safe
    if not path.exists() or path.suffix != ".json":
        abort(404)

    data = json.loads(path.read_text())
    requests = data.get("requests", [])
    summary = data.get("summary", {})
    tools = sorted(set(r.get("tool", "Unknown") for r in requests))

    return render_template_string(
        BURP_SESSION_TEMPLATE,
        requests=requests,
        summary=summary,
        tools=tools,
        filename=filename
    )


# ── Minimal templates ──────────────────────────────────────────────────────

BURP_INDEX_TEMPLATE = """
<!DOCTYPE html>
<html>
<head><title>Patronus — Burp Sessions</title>
<style>
  body { font-family: monospace; background: #0d1117; color: #c9d1d9; padding: 32px; }
  h1 { color: #f0a500; margin-bottom: 24px; }
  .card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px 20px;
          margin-bottom: 12px; display: flex; justify-content: space-between; align-items: center; }
  .card:hover { border-color: #f0a500; }
  a { color: #58a6ff; text-decoration: none; }
  .badge { background: #21262d; border: 1px solid #30363d; border-radius: 4px; padding: 2px 8px; font-size: 0.85em; }
  .flag { color: #f0a500; }
</style></head>
<body>
<h1>🛡 Patronus — Burp Sessions</h1>
{% if sessions %}
  {% for s in sessions %}
  <div class="card">
    <div>
      <a href="/burp/{{ s.filename }}"><b>{{ s.engagement }}</b></a>
      <span style="color:#8b949e; font-size:0.85em"> — {{ s.filename }}</span>
    </div>
    <div>
      <span class="badge">{{ s.total }} requests</span>
      {% if s.flagged %}<span class="badge flag">⚑ {{ s.flagged }} flagged</span>{% endif %}
      {% for tool, count in s.by_tool.items() %}
        <span class="badge">{{ tool }}: {{ count }}</span>
      {% endfor %}
      <a href="/burp/raw/{{ s.filename }}" style="margin-left:8px; font-size:0.8em;">JSON</a>
    </div>
  </div>
  {% endfor %}
{% else %}
  <p style="color:#8b949e">No Burp sessions found in {{ sessions_dir }}.<br>
  Run the Patronus Burp extension and click "Export JSON" to generate a session file.</p>
{% endif %}
</body></html>
"""

BURP_SESSION_TEMPLATE = """
<!DOCTYPE html>
<html>
<head><title>Patronus — {{ summary.get('engagement','Session') }}</title>
<style>
  body { font-family: monospace; background: #0d1117; color: #c9d1d9; padding: 0; margin: 0; }
  header { background: #161b22; padding: 16px 32px; border-bottom: 1px solid #30363d;
           display: flex; align-items: center; gap: 16px; }
  header h1 { color: #f0a500; font-size: 1.2rem; margin: 0; }
  .container { padding: 24px 32px; }
  .req { background: #161b22; border: 1px solid #30363d; border-radius: 6px; margin-bottom: 8px; }
  .req.flagged { border-color: #f0a500; }
  .req-head { padding: 10px 14px; cursor: pointer; display: flex; gap: 10px; align-items: center; }
  .req-head:hover { background: #1c2128; }
  .method { font-weight: bold; padding: 2px 8px; border-radius: 3px; font-size: 0.8em;
            background: #1f6feb33; color: #58a6ff; border: 1px solid #1f6feb; }
  .url { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .body { display: none; padding: 12px 14px; border-top: 1px solid #30363d; }
  pre { background: #0d1117; padding: 10px; border-radius: 4px; font-size: 0.8em; overflow-x: auto; white-space: pre-wrap; }
  .badge { background: #21262d; border: 1px solid #30363d; border-radius: 4px; padding: 2px 6px; font-size: 0.78em; }
  a { color: #58a6ff; }
</style></head>
<body>
<header>
  <h1>🛡 Patronus</h1>
  <span>{{ summary.get('engagement','') }}</span>
  <span class="badge">{{ summary.get('totalRequests',0) }} requests</span>
  {% for tool in tools %}<span class="badge">{{ tool }}</span>{% endfor %}
  <a href="/burp" style="margin-left:auto; font-size:0.85em;">← All Sessions</a>
</header>
<div class="container">
{% for req in requests %}
  <div class="req {% if req.flagged %}flagged{% endif %}">
    <div class="req-head" onclick="this.nextSibling.style.display=this.nextSibling.style.display=='block'?'none':'block'">
      <span class="method">{{ req.method }}</span>
      <span class="url">{{ req.url }}</span>
      <span class="badge">{{ req.tool }}</span>
      {% if req.responseStatusCode %}<span class="badge">{{ req.responseStatusCode }}</span>{% endif %}
      {% if req.flagged %}<span style="color:#f0a500">⚑</span>{% endif %}
    </div>
    <div class="body">
      {% if req.headers %}<b style="color:#8b949e;font-size:0.75em">REQUEST HEADERS</b><pre>{{ req.headers|join('\n') }}</pre>{% endif %}
      {% if req.body %}<b style="color:#8b949e;font-size:0.75em">REQUEST BODY</b><pre>{{ req.body }}</pre>{% endif %}
      {% if req.responseHeaders %}<b style="color:#8b949e;font-size:0.75em">RESPONSE HEADERS</b><pre>{{ req.responseHeaders|join('\n') }}</pre>{% endif %}
      {% if req.responseBody %}<b style="color:#8b949e;font-size:0.75em">RESPONSE BODY</b><pre>{{ req.responseBody[:3000] }}</pre>{% endif %}
      {% if req.notes %}<div style="color:#e3b341;margin-top:6px">📝 {{ req.notes }}</div>{% endif %}
    </div>
  </div>
{% endfor %}
</div>
</body></html>
"""
