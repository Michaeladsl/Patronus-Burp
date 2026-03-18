package com.patronus

import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SessionManager
 *
 * Thread-safe store for all captured requests in the current session.
 * Organises requests by engagement name (set from the Patronus UI tab).
 *
 * Supports:
 *   - Multiple named engagements within one Burp session
 *   - Per-tool filtering (Proxy, Repeater, Intruder, etc.)
 *   - Flagging interesting requests for client reporting
 *   - Live stats for the UI tab
 */
class SessionManager(val config: PatronusConfig) {

    // All sessions, keyed by engagement name
    private val sessions = mutableMapOf<String, EngagementSession>()
    private val lock = Any()

    // Current active engagement - set from UI tab
    @Volatile
    var currentEngagement: String = defaultEngagementName()
        set(value) {
            field = value.trim().ifBlank { defaultEngagementName() }
            synchronized(lock) {
                if (!sessions.containsKey(field)) {
                    sessions[field] = EngagementSession(name = field)
                }
            }
        }

    init {
        // Create the default session on startup
        sessions[currentEngagement] = EngagementSession(name = currentEngagement)
    }

    /** Add a completed request+response entry to the active session. */
    fun addRequest(entry: RedactedRequest) {
        synchronized(lock) {
            sessions.getOrPut(currentEngagement) {
                EngagementSession(name = currentEngagement)
            }.requests.add(entry)
        }
    }

    /** Get all requests for the current engagement. */
    fun currentRequests(): List<RedactedRequest> =
        synchronized(lock) { sessions[currentEngagement]?.requests?.toList() ?: emptyList() }

    /** Get all requests across ALL engagements. */
    fun allRequests(): List<RedactedRequest> =
        synchronized(lock) { sessions.values.flatMap { it.requests } }

    /** Get requests for a specific tool in the current engagement. */
    fun requestsByTool(tool: String): List<RedactedRequest> =
        currentRequests().filter { it.tool.equals(tool, ignoreCase = true) }

    /** Get all engagement names. */
    fun engagementNames(): List<String> =
        synchronized(lock) { sessions.keys.toList() }

    /** Get the current session object. */
    fun currentSession(): EngagementSession? =
        synchronized(lock) { sessions[currentEngagement] }

    /** Flag or unflag a request by ID. */
    fun toggleFlag(requestId: String) {
        synchronized(lock) {
            sessions.values.forEach { session ->
                val idx = session.requests.indexOfFirst { it.id == requestId }
                if (idx >= 0) {
                    val req = session.requests[idx]
                    session.requests[idx] = req.copy(flagged = !req.flagged)
                }
            }
        }
    }

    /** Add a note to a request by ID. */
    fun addNote(requestId: String, note: String) {
        synchronized(lock) {
            sessions.values.forEach { session ->
                val idx = session.requests.indexOfFirst { it.id == requestId }
                if (idx >= 0) {
                    session.requests[idx] = session.requests[idx].copy(notes = note)
                }
            }
        }
    }

    /** Delete a single request by ID. */
    fun deleteRequest(requestId: String) {
        synchronized(lock) {
            sessions.values.forEach { it.requests.removeIf { r -> r.id == requestId } }
        }
    }

    /** Clear all requests in the current engagement. */
    fun clearCurrentSession() {
        synchronized(lock) { sessions[currentEngagement]?.requests?.clear() }
    }

    /** Live stats for the UI. */
    fun stats(): Map<String, Int> {
        val reqs = currentRequests()
        return reqs.groupBy { it.tool }.mapValues { it.value.size }
    }

    /** Switch to a different engagement (or create it if it doesn't exist). */
    fun switchEngagement(name: String) {
        currentEngagement = name
    }

    private fun defaultEngagementName(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd")
        return "Engagement-${fmt.format(Date())}"
    }
}
