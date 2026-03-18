package com.patronus

/**
 * Redactor
 *
 * Applies regex-based redaction to HTTP request/response content.
 * Mirrors the logic in Patronus's redact.py but operates on live HTTP data.
 *
 * Built-in patterns cover common pentest sensitive data:
 *   - Auth headers (Authorization, Cookie, Set-Cookie)
 *   - Tokens and API keys in headers and body params
 *   - Passwords in form bodies and JSON
 *   - AWS/GCP/Azure credential patterns
 *   - JWT tokens
 *   - Private keys (PEM snippets)
 *
 * Users can add custom patterns via ~/.patronus/burp_config.json
 */
class Redactor(private val config: PatronusConfig) {

    companion object {
        const val REDACTED = "[REDACTED]"

        // ── Header-level patterns ──────────────────────────────────────────
        private val HEADER_PATTERNS = listOf(
            // Auth headers - redact value only, keep header name
            Regex("""(?i)(authorization:\s*)(.+)"""),
            Regex("""(?i)(cookie:\s*)(.+)"""),
            Regex("""(?i)(set-cookie:\s*)(.+?)(;|$)"""),
            Regex("""(?i)(x-api-key:\s*)(.+)"""),
            Regex("""(?i)(x-auth-token:\s*)(.+)"""),
            Regex("""(?i)(x-access-token:\s*)(.+)"""),
            Regex("""(?i)(proxy-authorization:\s*)(.+)"""),
        )

        // ── Body/param patterns ────────────────────────────────────────────
        private val BODY_PATTERNS = listOf(
            // URL-encoded form params
            Regex("""(?i)(password|passwd|pwd)=([^&\s\n"]+)"""),
            Regex("""(?i)(secret|api[_-]?key|apikey|access[_-]?key)=([^&\s\n"]+)"""),
            Regex("""(?i)(token|auth[_-]?token|session[_-]?id|sessionid)=([^&\s\n"]+)"""),
            Regex("""(?i)(client[_-]?secret|client[_-]?key)=([^&\s\n"]+)"""),

            // JSON body values
            Regex("""(?i)("password"\s*:\s*)"([^"]+)""""),
            Regex("""(?i)("secret"\s*:\s*)"([^"]+)""""),
            Regex("""(?i)("token"\s*:\s*)"([^"]+)""""),
            Regex("""(?i)("api_?key"\s*:\s*)"([^"]+)""""),
            Regex("""(?i)("access_?key"\s*:\s*)"([^"]+)""""),

            // Bearer tokens
            Regex("""(Bearer\s+)[A-Za-z0-9\-._~+/]+=*"""),

            // JWT tokens (three base64 segments)
            Regex("""eyJ[A-Za-z0-9\-_]+\.eyJ[A-Za-z0-9\-_]+\.[A-Za-z0-9\-_.+/]*"""),

            // AWS credentials
            Regex("""(AKIA|ASIA|AROA|AIDA)[A-Z0-9]{16}"""),
            Regex("""(?i)(aws[_-]?secret[_-]?access[_-]?key\s*[=:]\s*)([A-Za-z0-9/+]{40})"""),

            // Private key headers (PEM)
            Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----[\s\S]*?-----END [A-Z ]*PRIVATE KEY-----"""),
        )

        // ── IP address pattern (optional) ──────────────────────────────────
        private val IP_PATTERN = Regex("""\b(\d{1,3}\.){3}\d{1,3}\b""")
    }

    // Compile user-defined custom patterns once on init
    private val customPatterns: List<Regex> = config.customPatterns.mapNotNull {
        try { Regex(it) } catch (e: Exception) {
            println("[Patronus] Warning: Invalid custom pattern '$it': ${e.message}")
            null
        }
    }

    /**
     * Redact a list of raw header strings.
     * Returns headers with sensitive values replaced.
     */
    fun redactHeaders(headers: List<String>): List<String> {
        return headers.map { header ->
            var redacted = header
            HEADER_PATTERNS.forEach { pattern ->
                redacted = pattern.replace(redacted) { match ->
                    // Keep group 1 (header name), replace group 2 (value)
                    "${match.groupValues[1]}$REDACTED"
                }
            }
            customPatterns.forEach { redacted = redacted.replace(it, REDACTED) }
            redacted
        }
    }

    /**
     * Redact a request/response body string.
     */
    fun redactBody(body: String): String {
        if (body.isBlank()) return body

        // Cap body size to avoid processing huge binary responses
        val workingBody = if (body.length > config.maxBodySizeBytes) {
            body.substring(0, config.maxBodySizeBytes) + "\n[... TRUNCATED BY PATRONUS ...]"
        } else body

        var redacted = workingBody

        BODY_PATTERNS.forEach { pattern ->
            redacted = pattern.replace(redacted) { match ->
                // For two-group patterns (key=value), keep the key
                if (match.groupValues.size > 2 && match.groupValues[1].isNotEmpty()) {
                    "${match.groupValues[1]}$REDACTED"
                } else {
                    REDACTED
                }
            }
        }

        // Optional: redact IP addresses
        if (config.redactIpAddresses) {
            redacted = IP_PATTERN.replace(redacted, REDACTED)
        }

        customPatterns.forEach { redacted = redacted.replace(it, REDACTED) }

        return redacted
    }

    /**
     * Redact a full URL - removes sensitive query parameters.
     */
    fun redactUrl(url: String): String {
        val sensitiveParams = setOf(
            "password", "passwd", "pwd", "token", "api_key", "apikey",
            "secret", "access_key", "auth", "session_id", "sessionid",
            "client_secret", "private_key"
        )

        return try {
            val uri = java.net.URI(url)
            val query = uri.query ?: return url
            val redactedQuery = query.split("&").joinToString("&") { param ->
                val key = param.substringBefore("=").lowercase()
                if (sensitiveParams.any { key.contains(it) }) {
                    "${param.substringBefore("=")}=$REDACTED"
                } else param
            }
            url.replace(query, redactedQuery)
        } catch (e: Exception) {
            url // Return original if URL parsing fails
        }
    }
}
