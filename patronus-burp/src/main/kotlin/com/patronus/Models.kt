package com.patronus

/**
 * RedactedRequest - Immutable snapshot of a redacted HTTP request.
 */
data class RedactedRequest(
    val id: String,
    val timestamp: Long,
    val engagementTag: String,
    val tool: String,
    val method: String,
    val url: String,
    val host: String,
    val port: Int,
    val protocol: String,
    val headers: List<String>,        // Each header as a redacted string
    val body: String,                 // Redacted body
    val responseStatusCode: Int = 0,
    val responseHeaders: List<String> = emptyList(),
    val responseBody: String = "",
    val notes: String = "",           // User-added notes from the UI tab
    val flagged: Boolean = false      // User can flag interesting requests
)

/**
 * EngagementSession - Groups all requests under a named pentest engagement.
 */
data class EngagementSession(
    val name: String,
    val startTime: Long = System.currentTimeMillis(),
    val requests: MutableList<RedactedRequest> = mutableListOf()
) {
    fun requestCount() = requests.size
    fun byTool(tool: String) = requests.filter { it.tool == tool }
    fun flagged() = requests.filter { it.flagged }
    fun tools() = requests.map { it.tool }.distinct().sorted()
}

/**
 * ExportSummary - Stats shown in the UI and report header.
 */
data class ExportSummary(
    val engagement: String,
    val totalRequests: Int,
    val byTool: Map<String, Int>,
    val flaggedCount: Int,
    val startTime: Long,
    val exportTime: Long = System.currentTimeMillis()
)
