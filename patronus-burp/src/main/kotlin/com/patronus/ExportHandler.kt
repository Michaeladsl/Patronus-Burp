package com.patronus

import com.google.gson.GsonBuilder
import burp.api.montoya.logging.Logging
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date

/**
 * ExportHandler
 *
 * Handles two export formats:
 *
 * 1. JSON export → ~/.patronus/burp_sessions/<engagement>_<timestamp>.json
 *    Feeds directly into the existing Patronus Flask web UI (server.py).
 *    Your server.py just needs a new route to serve files from this directory.
 *
 * 2. HTML report → Standalone self-contained report for client delivery.
 *    No server required. Styled with embedded CSS, filterable by tool.
 */
class ExportHandler(
    private val sessionManager: SessionManager,
    private val config: PatronusConfig,
    private val logging: Logging
) {

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val timestampFmt = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss")

    // ── JSON Export ────────────────────────────────────────────────────────

    /**
     * Export current session to JSON.
     * Returns the path of the written file, or null on failure.
     */
    fun exportJson(customPath: String? = null): String? {
        return try {
            val outputDir = File(config.outputDir.expandHome()).also { it.mkdirs() }
            val timestamp = timestampFmt.format(Date())
            val engagement = sessionManager.currentEngagement.sanitizeFilename()
            val file = File(customPath ?: "${outputDir.absolutePath}/${engagement}_${timestamp}.json")

            val session = sessionManager.currentSession()
            val summary = ExportSummary(
                engagement = sessionManager.currentEngagement,
                totalRequests = session?.requestCount() ?: 0,
                byTool = sessionManager.stats(),
                flaggedCount = session?.flagged()?.size ?: 0,
                startTime = session?.startTime ?: System.currentTimeMillis()
            )

            val export = mapOf(
                "summary" to summary,
                "requests" to sessionManager.currentRequests()
            )

            file.writeText(gson.toJson(export))
            logging.logToOutput("[Patronus] JSON exported: ${file.absolutePath}")
            file.absolutePath

        } catch (e: Exception) {
            logging.logToError("[Patronus] JSON export failed: ${e.message}")
            null
        }
    }

    // ── HTML Report Export ─────────────────────────────────────────────────

    /**
     * Export a standalone HTML report.
     * Returns the path of the written file, or null on failure.
     */
    fun exportHtml(customPath: String? = null): String? {
        return try {
            val outputDir = File(config.outputDir.expandHome()).also { it.mkdirs() }
            val timestamp = timestampFmt.format(Date())
            val engagement = sessionManager.currentEngagement.sanitizeFilename()
            val file = File(customPath ?: "${outputDir.absolutePath}/${engagement}_${timestamp}.html")

            file.writeText(buildHtmlReport())
            logging.logToOutput("[Patronus] HTML report exported: ${file.absolutePath}")
            file.absolutePath

        } catch (e: Exception) {
            logging.logToError("[Patronus] HTML export failed: ${e.message}")
            null
        }
    }

    private fun buildHtmlReport(): String {
        val requests = sessionManager.currentRequests()
        val tools = requests.map { it.tool }.distinct().sorted()
        val engagement = sessionManager.currentEngagement
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date())
        val flagged = requests.filter { it.flagged }

        return buildString {
            appendLine("""<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>Patronus Report — $engagement</title>
<style>
  * { box-sizing: border-box; margin: 0; padding: 0; }
  body { font-family: 'Segoe UI', system-ui, sans-serif; background: #0d1117; color: #c9d1d9; }
  header { background: #161b22; border-bottom: 1px solid #30363d; padding: 20px 40px; display: flex; align-items: center; gap: 16px; }
  header h1 { font-size: 1.4rem; color: #f0a500; }
  header span { color: #8b949e; font-size: 0.9rem; }
  .badge { background: #21262d; border: 1px solid #30363d; border-radius: 20px; padding: 4px 12px; font-size: 0.8rem; }
  .container { max-width: 1400px; margin: 0 auto; padding: 24px 40px; }
  .stats-bar { display: flex; gap: 12px; flex-wrap: wrap; margin-bottom: 28px; }
  .stat-card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; padding: 16px 20px; min-width: 140px; }
  .stat-card .label { font-size: 0.75rem; color: #8b949e; text-transform: uppercase; letter-spacing: 0.05em; }
  .stat-card .value { font-size: 1.6rem; font-weight: 700; color: #f0a500; margin-top: 4px; }
  .filter-bar { display: flex; gap: 8px; flex-wrap: wrap; margin-bottom: 20px; }
  .filter-btn { background: #21262d; border: 1px solid #30363d; border-radius: 6px; padding: 6px 14px;
                color: #c9d1d9; cursor: pointer; font-size: 0.85rem; transition: all 0.15s; }
  .filter-btn:hover, .filter-btn.active { background: #f0a500; color: #0d1117; border-color: #f0a500; }
  .request-card { background: #161b22; border: 1px solid #30363d; border-radius: 8px; margin-bottom: 12px; overflow: hidden; }
  .request-card.flagged { border-color: #f0a500; }
  .req-header { display: flex; align-items: center; gap: 10px; padding: 12px 16px; cursor: pointer;
                background: #1c2128; transition: background 0.1s; }
  .req-header:hover { background: #21262d; }
  .method { font-weight: 700; font-size: 0.8rem; padding: 3px 8px; border-radius: 4px; min-width: 60px; text-align: center; }
  .GET    { background: #1f6feb33; color: #58a6ff; border: 1px solid #1f6feb; }
  .POST   { background: #2ea04326; color: #3fb950; border: 1px solid #2ea043; }
  .PUT    { background: #9e6a0326; color: #e3b341; border: 1px solid #9e6a03; }
  .DELETE { background: #da363326; color: #f85149; border: 1px solid #da3633; }
  .PATCH  { background: #6e40c926; color: #bc8cff; border: 1px solid #6e40c9; }
  .url { flex: 1; font-family: monospace; font-size: 0.85rem; color: #c9d1d9; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .tool-tag { font-size: 0.75rem; padding: 2px 8px; border-radius: 4px; background: #21262d; color: #8b949e; border: 1px solid #30363d; }
  .status-ok  { color: #3fb950; }
  .status-err { color: #f85149; }
  .status-rdr { color: #e3b341; }
  .flag-icon { color: #f0a500; }
  .req-body { display: none; padding: 16px; border-top: 1px solid #30363d; }
  .req-body.open { display: block; }
  .section-label { font-size: 0.75rem; color: #8b949e; text-transform: uppercase; letter-spacing: 0.05em; margin-bottom: 6px; margin-top: 14px; }
  .section-label:first-child { margin-top: 0; }
  pre { background: #0d1117; border: 1px solid #30363d; border-radius: 6px; padding: 12px;
        font-size: 0.8rem; overflow-x: auto; white-space: pre-wrap; word-break: break-all; color: #c9d1d9; }
  .notes-box { background: #161b22; border: 1px solid #f0a50033; border-radius: 6px; padding: 10px 12px;
               font-size: 0.85rem; color: #e3b341; margin-top: 10px; }
  footer { text-align: center; padding: 32px; color: #484f58; font-size: 0.8rem; border-top: 1px solid #21262d; margin-top: 40px; }
  .section-title { font-size: 1rem; font-weight: 600; color: #f0f6fc; margin: 28px 0 12px; display: flex; align-items: center; gap: 8px; }
  .section-title::after { content: ''; flex: 1; height: 1px; background: #30363d; }
</style>
</head>
<body>
<header>
  <h1>🛡 Patronus</h1>
  <span>$engagement</span>
  <span class="badge">Generated: $timestamp</span>
  <span class="badge">${requests.size} requests</span>
</header>
<div class="container">
  <div class="stats-bar">
    <div class="stat-card"><div class="label">Total Requests</div><div class="value">${requests.size}</div></div>
    <div class="stat-card"><div class="label">Flagged</div><div class="value">${flagged.size}</div></div>""")

            tools.forEach { tool ->
                val count = requests.count { it.tool == tool }
                appendLine("""    <div class="stat-card"><div class="label">$tool</div><div class="value">$count</div></div>""")
            }

            appendLine("""  </div>
  <div class="filter-bar">
    <button class="filter-btn active" onclick="filterTool('all')">All</button>
    <button class="filter-btn" onclick="filterTool('flagged')">⚑ Flagged</button>""")

            tools.forEach { tool ->
                appendLine("""    <button class="filter-btn" onclick="filterTool('${tool.lowercase()}')">${tool}</button>""")
            }

            appendLine("  </div>")

            // Flagged section first
            if (flagged.isNotEmpty()) {
                appendLine("""  <div class="section-title">⚑ Flagged for Review</div>""")
                flagged.forEach { req -> appendRequestCard(req) }
            }

            // All requests grouped by tool
            tools.forEach { tool ->
                val toolRequests = requests.filter { it.tool == tool }
                appendLine("""  <div class="section-title">$tool</div>""")
                toolRequests.forEach { req -> appendRequestCard(req) }
            }

            appendLine("""
</div>
<footer>Generated by Patronus Burp Extension · ${requests.size} requests captured · $timestamp</footer>
<script>
function filterTool(tool) {
  document.querySelectorAll('.filter-btn').forEach(b => b.classList.remove('active'));
  event.target.classList.add('active');
  document.querySelectorAll('.request-card').forEach(card => {
    if (tool === 'all') card.style.display = '';
    else if (tool === 'flagged') card.style.display = card.classList.contains('flagged') ? '' : 'none';
    else card.style.display = card.dataset.tool === tool ? '' : 'none';
  });
}
function toggleCard(id) {
  const body = document.getElementById('body-' + id);
  body.classList.toggle('open');
}
</script>
</body>
</html>""")
        }
    }

    private fun StringBuilder.appendRequestCard(req: RedactedRequest) {
        val statusClass = when {
            req.responseStatusCode in 200..299 -> "status-ok"
            req.responseStatusCode in 300..399 -> "status-rdr"
            req.responseStatusCode >= 400       -> "status-err"
            else                               -> ""
        }
        val flagClass = if (req.flagged) " flagged" else ""
        val flagIcon = if (req.flagged) """<span class="flag-icon">⚑</span>""" else ""
        val methodClass = req.method.uppercase()
        val safeId = req.id.replace("-", "")

        appendLine("""  <div class="request-card$flagClass" data-tool="${req.tool.lowercase()}" id="card-$safeId">
    <div class="req-header" onclick="toggleCard('$safeId')">
      <span class="method $methodClass">${req.method}</span>
      <span class="url">${req.url.escapeHtml()}</span>
      <span class="tool-tag">${req.tool}</span>
      ${if (req.responseStatusCode > 0) """<span class="$statusClass">${req.responseStatusCode}</span>""" else ""}
      $flagIcon
    </div>
    <div class="req-body" id="body-$safeId">""")

        if (req.headers.isNotEmpty()) {
            appendLine("""      <div class="section-label">Request Headers</div>
      <pre>${req.headers.joinToString("\n").escapeHtml()}</pre>""")
        }
        if (req.body.isNotBlank()) {
            appendLine("""      <div class="section-label">Request Body</div>
      <pre>${req.body.escapeHtml()}</pre>""")
        }
        if (req.responseHeaders.isNotEmpty()) {
            appendLine("""      <div class="section-label">Response Headers (${req.responseStatusCode})</div>
      <pre>${req.responseHeaders.joinToString("\n").escapeHtml()}</pre>""")
        }
        if (req.responseBody.isNotBlank()) {
            appendLine("""      <div class="section-label">Response Body</div>
      <pre>${req.responseBody.take(4000).escapeHtml()}${if (req.responseBody.length > 4000) "\n[truncated]" else ""}</pre>""")
        }
        if (req.notes.isNotBlank()) {
            appendLine("""      <div class="notes-box">📝 ${req.notes.escapeHtml()}</div>""")
        }

        appendLine("    </div>\n  </div>")
    }

    private fun String.escapeHtml() = this
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun String.sanitizeFilename() =
        replace(Regex("[^a-zA-Z0-9_\\-]"), "_").take(60)
}
