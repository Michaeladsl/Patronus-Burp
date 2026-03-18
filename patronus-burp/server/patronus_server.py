#!/usr/bin/env python3
"""
Patronus Burp - Standalone Web Server
======================================
Run with:  python patronus_server.py
Serves at: http://localhost:5001

Watches ~/.patronus/burp_sessions/ for JSON exports from the Burp extension
and provides a full web UI to review, filter, search, and manage sessions.
"""

import os
import json
import time
import threading
from pathlib import Path
from datetime import datetime
from flask import Flask, render_template, jsonify, request, abort, send_file

# - Config -

SESSIONS_DIR = Path.home() / ".patronus" / "burp_sessions"
HOST = "127.0.0.1"
PORT = 5001
AUTO_RELOAD_INTERVAL = 5  # seconds between checking for new session files

app = Flask(__name__, template_folder="templates", static_folder="static")
SESSIONS_DIR.mkdir(parents=True, exist_ok=True)

@app.template_filter("format_time")
def format_time(ts):
    try:
        return datetime.fromtimestamp(int(ts)).strftime("%Y-%m-%d %H:%M")
    except Exception:
        return ""

# - Session cache (hot-reloads when files change) -

_session_cache = {}
_cache_lock = threading.Lock()
_last_scan = 0


def scan_sessions():
    """Scan sessions dir and reload any changed/new files into cache."""
    global _last_scan
    now = time.time()
    if now - _last_scan < AUTO_RELOAD_INTERVAL:
        return
    _last_scan = now

    with _cache_lock:
        current_files = set(SESSIONS_DIR.glob("*.json"))
        cached_files = set(_session_cache.keys())

        # Load new or modified files
        for path in current_files:
            mtime = path.stat().st_mtime
            if path not in _session_cache or _session_cache[path].get("_mtime") != mtime:
                try:
                    data = json.loads(path.read_text(encoding="utf-8"))
                    data["_mtime"] = mtime
                    data["_filename"] = path.name
                    data["_path"] = str(path)
                    _session_cache[path] = data
                except Exception as e:
                    print(f"[Patronus] Could not load {path.name}: {e}")

        # Remove deleted files
        for path in cached_files - current_files:
            del _session_cache[path]


def get_all_sessions():
    scan_sessions()
    with _cache_lock:
        sessions = list(_session_cache.values())
    return sorted(sessions, key=lambda s: s.get("summary", {}).get("startTime", 0), reverse=True)


def get_session(filename):
    scan_sessions()
    with _cache_lock:
        for path, data in _session_cache.items():
            if path.name == filename:
                return data
    # Try loading directly if not cached yet
    path = SESSIONS_DIR / filename
    if path.exists() and path.suffix == ".json":
        try:
            data = json.loads(path.read_text(encoding="utf-8"))
            data["_filename"] = filename
            return data
        except Exception:
            pass
    return None


def safe_filename(name):
    return "".join(c for c in name if c.isalnum() or c in "._- ").strip()


# - Routes -

@app.route("/")
def index():
    sessions = get_all_sessions()
    stats = {
        "total_sessions": len(sessions),
        "total_requests": sum(s.get("summary", {}).get("totalRequests", 0) for s in sessions),
        "total_flagged": sum(s.get("summary", {}).get("flaggedCount", 0) for s in sessions),
        "engagements": list({s.get("summary", {}).get("engagement", "Unknown") for s in sessions}),
    }
    return render_template("index.html", sessions=sessions, stats=stats)


@app.route("/session/<filename>")
def session_view(filename):
    filename = safe_filename(filename)
    session = get_session(filename)
    if not session:
        abort(404)

    requests_data = session.get("requests", [])
    summary = session.get("summary", {})
    tools = sorted(set(r.get("tool", "Unknown") for r in requests_data))
    hosts = sorted(set(r.get("host", "") for r in requests_data if r.get("host")))
    flagged = [r for r in requests_data if r.get("flagged")]

    return render_template(
        "session.html",
        session=session,
        requests=requests_data,
        summary=summary,
        tools=tools,
        hosts=hosts,
        flagged=flagged,
        filename=filename
    )


@app.route("/api/sessions")
def api_sessions():
    """JSON list of all sessions with summary data - used for live polling."""
    sessions = get_all_sessions()
    return jsonify([{
        "filename": s.get("_filename"),
        "engagement": s.get("summary", {}).get("engagement", "Unknown"),
        "totalRequests": s.get("summary", {}).get("totalRequests", 0),
        "flaggedCount": s.get("summary", {}).get("flaggedCount", 0),
        "byTool": s.get("summary", {}).get("byTool", {}),
        "startTime": s.get("summary", {}).get("startTime", 0),
        "exportTime": s.get("summary", {}).get("exportTime", 0),
    } for s in sessions])


@app.route("/api/session/<filename>")
def api_session(filename):
    """Full JSON for a single session - used for live request table updates."""
    filename = safe_filename(filename)
    session = get_session(filename)
    if not session:
        abort(404)
    # Strip internal keys before returning
    clean = {k: v for k, v in session.items() if not k.startswith("_")}
    return jsonify(clean)


@app.route("/api/session/<filename>/request/<request_id>/flag", methods=["POST"])
def flag_request(filename, request_id):
    """Toggle flag on a request and persist to the JSON file."""
    filename = safe_filename(filename)
    path = SESSIONS_DIR / filename
    if not path.exists():
        abort(404)
    data = json.loads(path.read_text(encoding="utf-8"))
    for req in data.get("requests", []):
        if req.get("id") == request_id:
            req["flagged"] = not req.get("flagged", False)
            break
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    # Invalidate cache
    with _cache_lock:
        for p in list(_session_cache.keys()):
            if p.name == filename:
                del _session_cache[p]
    return jsonify({"ok": True})


@app.route("/api/session/<filename>/request/<request_id>/note", methods=["POST"])
def add_note(filename, request_id):
    """Save a note to a request and persist."""
    filename = safe_filename(filename)
    path = SESSIONS_DIR / filename
    if not path.exists():
        abort(404)
    note = request.json.get("note", "")
    data = json.loads(path.read_text(encoding="utf-8"))
    for req in data.get("requests", []):
        if req.get("id") == request_id:
            req["notes"] = note
            break
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    with _cache_lock:
        for p in list(_session_cache.keys()):
            if p.name == filename:
                del _session_cache[p]
    return jsonify({"ok": True})


@app.route("/api/session/<filename>/request/<request_id>/severity", methods=["POST"])
def set_severity(filename, request_id):
    """Set severity on a request and persist."""
    filename = safe_filename(filename)
    path = SESSIONS_DIR / filename
    if not path.exists():
        abort(404)
    severity = request.json.get("severity", "")
    data = json.loads(path.read_text(encoding="utf-8"))
    for req in data.get("requests", []):
        if req.get("id") == request_id:
            req["severity"] = severity
            break
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    with _cache_lock:
        for p in list(_session_cache.keys()):
            if p.name == filename:
                del _session_cache[p]
    return jsonify({"ok": True})


@app.route("/api/session/<filename>/request/<request_id>/delete", methods=["POST"])
def delete_request(filename, request_id):
    """Delete a request from a session file."""
    filename = safe_filename(filename)
    path = SESSIONS_DIR / filename
    if not path.exists():
        abort(404)
    data = json.loads(path.read_text(encoding="utf-8"))
    data["requests"] = [r for r in data.get("requests", []) if r.get("id") != request_id]
    data.setdefault("summary", {})["totalRequests"] = len(data["requests"])
    path.write_text(json.dumps(data, indent=2), encoding="utf-8")
    with _cache_lock:
        for p in list(_session_cache.keys()):
            if p.name == filename:
                del _session_cache[p]
    return jsonify({"ok": True})


@app.route("/api/session/<filename>/delete", methods=["POST"])
def delete_session(filename):
    """Delete an entire session file."""
    filename = safe_filename(filename)
    path = SESSIONS_DIR / filename
    if path.exists():
        path.unlink()
    with _cache_lock:
        for p in list(_session_cache.keys()):
            if p.name == filename:
                del _session_cache[p]
    return jsonify({"ok": True})



@app.route("/playlist/<filename>")
def playlist_view(filename):
    filename = safe_filename(filename)
    session = get_session(filename)
    if not session:
        abort(404)
    requests_data = session.get("requests", [])
    summary = session.get("summary", {})
    tools = sorted(set(r.get("tool", "Unknown") for r in requests_data))
    return render_template(
        "playlist.html",
        session=session,
        requests=requests_data,
        summary=summary,
        tools=tools,
        filename=filename
    )

@app.route("/download/<filename>")
def download(filename):
    """Download raw JSON session file."""
    filename = safe_filename(filename)
    path = SESSIONS_DIR / filename
    if not path.exists():
        abort(404)
    return send_file(str(path), as_attachment=True)


# - Entry point -

if __name__ == "__main__":
    print(f"""
  ____       _
 |  _ \\ __ _| |_ _ __ ___  _ __  _   _ ___
 | |_) / _` | __| '__/ _ \\| '_ \\| | | / __|
 |  __/ (_| | |_| | | (_) | | | | |_| \\__ \\
 |_|   \\__,_|\\__|_|  \\___/|_| |_|\\__,_|___/  Burp Server

  Watching: {SESSIONS_DIR}
  Server:   http://{HOST}:{PORT}
  """)
    app.run(host=HOST, port=PORT, debug=False)
