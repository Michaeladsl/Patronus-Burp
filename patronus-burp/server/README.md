# Patronus Burp - Standalone Web Server

A dedicated web UI for reviewing Burp Suite sessions captured by the Patronus Burp extension.
Runs completely independently - no connection to the original Patronus tool required.

## Setup

```bash
cd server
pip install -r requirements.txt
python patronus_server.py
```

Then open: http://localhost:5001

## Workflow

1. In Burp Suite, use the Patronus tab to capture traffic
2. Click **Export JSON** in the Patronus tab (or it auto-saves on Burp close)
3. Sessions appear automatically in the web UI - no refresh needed
4. The server watches ~/.patronus/burp_sessions/ and hot-reloads every 5 seconds

## Features

**Dashboard (/):**
- Overview of all sessions with request counts and tool breakdown
- Delete sessions directly from the list
- Live session count updates every 10 seconds

**Session view (/session/<file>):**
- Full two-pane request browser (list + detail)
- Filter by tool (Proxy, Repeater, Intruder, Scanner)
- Filter by host
- Search across URL, host, request body, response body
- Flag / unflag requests (persisted to JSON file)
- Add notes to individual requests (persisted)
- Delete individual requests
- Download raw JSON
- Auto-reloads new requests every 15 seconds while open

## Output directory

Sessions are read from:
    ~/.patronus/burp_sessions/

This is the same directory the Burp extension exports to.
To change it, edit SESSIONS_DIR at the top of patronus_server.py.
