# Patronus Burp Extension

Burp Suite companion to [Patronus](https://github.com/Michaeladsl/Patronus).

Automatically captures, redacts, and organises all HTTP traffic from every Burp tool — Proxy, Repeater, Intruder, Scanner — and exports structured reports for pentest client deliverables.

---

## Features

- **Captures all tools** — Proxy, Repeater, Intruder, Scanner, Organizer
- **Auto-redaction** — auth headers, cookies, tokens, passwords, JWTs, AWS keys, and more
- **Engagement tagging** — name and switch between pentest engagements in-session
- **Flag requests** — mark interesting requests for the report
- **Add notes** — annotate individual requests from within Burp
- **JSON export** — feeds directly into the existing Patronus Flask web UI
- **Standalone HTML report** — self-contained, filterable by tool, for client delivery
- **Custom patterns** — define your own regex patterns in `~/.patronus/burp_config.json`

---

## Requirements

- Burp Suite Professional or Community (2022.9.5+)
- Java 17+ (or the JDK bundled with Burp)
- Gradle 8.5+ (only needed to build from source)

---

## Build

```bash
git clone <this-repo>
cd patronus-burp
./gradlew shadowJar
```

On Windows:
```cmd
gradlew.bat shadowJar
```

The compiled JAR will be at:
```
build/libs/patronus-burp.jar
```

> **Note:** You do NOT need Gradle installed system-wide. The `gradlew` wrapper
> downloads the correct version automatically on first run.

---

## Install into Burp

1. Open Burp Suite
2. Go to **Extensions → Add**
3. Extension type: **Java**
4. Select `build/libs/patronus-burp.jar`
5. Click **Next** — you should see the Patronus banner in the output tab
6. A **Patronus** tab will appear in the Burp top navigation bar

---

## Configuration

On first load, Patronus creates a default config file at:
```
~/.patronus/burp_config.json
```

Edit it to customise behaviour:

```json
{
  "outputDir": "~/.patronus/burp_sessions",
  "autoExportOnUnload": true,
  "redactIpAddresses": false,
  "customPatterns": [
    "my-internal-token=[^&\\s]+",
    "X-Client-Secret: .+"
  ],
  "excludedHosts": [
    "burpsuite.collaborator.net",
    "portswigger.net"
  ],
  "logResponseBodies": true,
  "maxBodySizeBytes": 102400
}
```

| Option | Default | Description |
|--------|---------|-------------|
| `outputDir` | `~/.patronus/burp_sessions` | Where JSON exports are saved |
| `autoExportOnUnload` | `true` | Auto-save JSON when Burp unloads the extension |
| `redactIpAddresses` | `false` | Redact IPv4 addresses from all traffic |
| `customPatterns` | `[]` | Additional regex patterns to redact |
| `excludedHosts` | Collaborator etc. | Hosts whose traffic is never logged |
| `logResponseBodies` | `true` | Set to false to skip capturing response bodies |
| `maxBodySizeBytes` | `102400` | Max body size captured (100KB default) |

---

## Usage

### During a pentest

1. Load the extension into Burp
2. In the **Patronus** tab, set your engagement name (e.g. `ClientName-WebApp-2025`)
3. Use Burp normally — all traffic is captured and redacted automatically
4. Right-click any request in the Patronus table to flag it or add a note
5. Click **Export HTML Report** to generate a client-ready report

### Viewing in the Patronus web UI

If you're already using Patronus for terminal recordings, you can unify both views:

1. Copy `patronus_burp_routes.py` into your Patronus directory
2. Add to the bottom of `server.py`:
   ```python
   from patronus_burp_routes import register_burp_routes
   register_burp_routes(app)
   ```
3. Run `python3 patronus.py` as normal
4. Visit `http://localhost:5000/burp` to see all Burp sessions

---

## Project Structure

```
patronus-burp/
├── build.gradle.kts              # Kotlin + Shadow JAR build config
├── settings.gradle.kts           # Project name
├── gradle/wrapper/               # Gradle wrapper (no system install needed)
│   └── gradle-wrapper.properties
├── patronus_burp_routes.py       # Flask routes to add to existing Patronus server.py
└── src/main/kotlin/com/patronus/
    ├── PatronusBurp.kt           # Entry point — BurpExtension.initialize()
    ├── PatronusConfig.kt         # Config loader (reads ~/.patronus/burp_config.json)
    ├── Models.kt                 # Data classes: RedactedRequest, EngagementSession
    ├── Redactor.kt               # Redaction engine — headers, body, URL params
    ├── HttpLogger.kt             # HTTP handler — hooks into all Burp tools
    ├── SessionManager.kt         # Thread-safe request store, engagement management
    ├── ExportHandler.kt          # JSON + HTML report generation
    ├── PatronusTab.kt            # Burp UI tab (Swing)
    └── Banner.kt                 # ASCII banner shown on load
```

---

## Built-in Redaction Patterns

The following are redacted automatically. All redacted values are replaced with `[REDACTED]`.

**Headers:**
- `Authorization: *`
- `Cookie: *`
- `Set-Cookie: *`
- `X-Api-Key: *`
- `X-Auth-Token: *`
- `Proxy-Authorization: *`

**Body / Params:**
- `password=`, `passwd=`, `pwd=`
- `secret=`, `api_key=`, `token=`, `session_id=`
- JSON fields: `"password":`, `"token":`, `"api_key":`, `"secret":`
- Bearer tokens
- JWT tokens (three-segment base64)
- AWS access key IDs (`AKIA...`)
- PEM private key blocks

Add your own patterns in `~/.patronus/burp_config.json` under `customPatterns`.

---

## Integration with Original Patronus

```
Terminal session  →  Patronus (existing)  →  ~/.patronus/splits/
                                                     ↓
Burp Suite        →  PatronusBurp (this)  →  ~/.patronus/burp_sessions/
                                                     ↓
                                         Shared Flask web UI
                                         → /          (terminal recordings)
                                         → /burp       (Burp sessions index)
                                         → /burp/<file> (session detail view)
```

---

## License

GPL-2.0 — same as Patronus.
