package com.patronus

import burp.api.montoya.MontoyaApi
import burp.api.montoya.core.ToolType
import burp.api.montoya.http.handler.*
import burp.api.montoya.logging.Logging
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * HttpLogger
 *
 * Registers as an HTTP handler for ALL Burp tools:
 *   Proxy, Repeater, Intruder, Scanner, Organizer, Extensions, etc.
 *
 * On each request:
 *   1. Checks if the host is excluded (e.g. Collaborator, internal)
 *   2. Redacts headers, body, and URL
 *   3. Stores in SessionManager with tool source label
 *
 * On each response:
 *   1. Attaches redacted response to the pending request entry
 */
class HttpLogger(
    private val api: MontoyaApi,
    private val redactor: Redactor,
    private val sessionManager: SessionManager,
    private val logging: Logging
) : HttpHandler {

    // Temp store to pair responses with their requests
    private val pendingRequests = ConcurrentHashMap<String, RedactedRequest>()

    override fun handleHttpRequestToBeSent(request: HttpRequestToBeSent): RequestToBeSentAction {
        try {
            val host = request.httpService().host()

            // Skip excluded hosts (Collaborator, etc.)
            if (sessionManager.config.excludedHosts.any { host.contains(it, ignoreCase = true) }) {
                return RequestToBeSentAction.continueWith(request)
            }

            val toolName = request.toolSource().toolType().displayName()
            val requestId = UUID.randomUUID().toString()

            val redactedHeaders = redactor.redactHeaders(
                request.headers().map { it.toString() }
            )
            val redactedBody = redactor.redactBody(request.bodyToString())
            val redactedUrl = redactor.redactUrl(request.url())

            val entry = RedactedRequest(
                id = requestId,
                timestamp = System.currentTimeMillis(),
                engagementTag = sessionManager.currentEngagement,
                tool = toolName,
                method = request.method(),
                url = redactedUrl,
                host = host,
                port = request.httpService().port(),
                protocol = if (request.httpService().secure()) "https" else "http",
                headers = redactedHeaders,
                body = redactedBody
            )

            // Park request until we get the response
            pendingRequests[requestId] = entry

            // Annotate Proxy history entry with engagement tag for visibility
            if (request.toolSource().toolType() == ToolType.PROXY) {
                request.annotations().setNotes("[Patronus] ${sessionManager.currentEngagement}")
            }

        } catch (e: Exception) {
            logging.logToError("[Patronus] Error capturing request: ${e.message}")
        }

        return RequestToBeSentAction.continueWith(request)
    }

    override fun handleHttpResponseReceived(response: HttpResponseReceived): ResponseReceivedAction {
        try {
            val host = response.initiatingRequest().httpService().host()

            if (sessionManager.config.excludedHosts.any { host.contains(it, ignoreCase = true) }) {
                return ResponseReceivedAction.continueWith(response)
            }

            // Match response to its pending request via initiating request URL
            val matchingKey = pendingRequests.entries.firstOrNull { (_, req) ->
                req.url == redactor.redactUrl(response.initiatingRequest().url()) &&
                req.method == response.initiatingRequest().method()
            }?.key

            val responseHeaders = redactor.redactHeaders(
                response.headers().map { it.toString() }
            )
            val responseBody = if (sessionManager.config.logResponseBodies) {
                redactor.redactBody(response.bodyToString())
            } else {
                "[Response body logging disabled in config]"
            }

            if (matchingKey != null) {
                val pending = pendingRequests.remove(matchingKey)!!
                val complete = pending.copy(
                    responseStatusCode = response.statusCode().toInt(),
                    responseHeaders = responseHeaders,
                    responseBody = responseBody
                )
                sessionManager.addRequest(complete)
            } else {
                // No matching pending request found — log response-only entry
                val orphanEntry = RedactedRequest(
                    id = UUID.randomUUID().toString(),
                    timestamp = System.currentTimeMillis(),
                    engagementTag = sessionManager.currentEngagement,
                    tool = response.initiatingRequest().let { "Unknown" },
                    method = response.initiatingRequest().method(),
                    url = redactor.redactUrl(response.initiatingRequest().url()),
                    host = host,
                    port = response.initiatingRequest().httpService().port(),
                    protocol = if (response.initiatingRequest().httpService().secure()) "https" else "http",
                    headers = emptyList(),
                    body = "",
                    responseStatusCode = response.statusCode().toInt(),
                    responseHeaders = responseHeaders,
                    responseBody = responseBody
                )
                sessionManager.addRequest(orphanEntry)
            }

        } catch (e: Exception) {
            logging.logToError("[Patronus] Error capturing response: ${e.message}")
        }

        return ResponseReceivedAction.continueWith(response)
    }
}

/** Extension: human-readable tool name */
private fun ToolType.displayName(): String = when (this) {
    ToolType.PROXY      -> "Proxy"
    ToolType.REPEATER   -> "Repeater"
    ToolType.INTRUDER   -> "Intruder"
    ToolType.SCANNER    -> "Scanner"
    ToolType.ORGANIZER  -> "Organizer"
    ToolType.EXTENSIONS -> "Extension"
    ToolType.SEQUENCER  -> "Sequencer"
    else                -> "Burp"
}
