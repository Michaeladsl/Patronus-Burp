package com.patronus

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.io.File

/**
 * PatronusConfig
 *
 * Loads configuration from ~/.patronus/burp_config.json
 * Creates a default config file if none exists.
 *
 * Example burp_config.json:
 * {
 *   "outputDir": "~/.patronus/burp_sessions",
 *   "autoExportOnUnload": true,
 *   "redactIpAddresses": false,
 *   "customPatterns": ["my-secret=[^&\\s]+", "client-token=[^&\\s]+"],
 *   "excludedHosts": ["burpsuite.collaborator.net", "portswigger.net"],
 *   "logResponseBodies": true,
 *   "maxBodySizeBytes": 102400
 * }
 */
data class PatronusConfig(
    val outputDir: String = defaultOutputDir(),
    val autoExportOnUnload: Boolean = true,
    val redactIpAddresses: Boolean = false,
    val customPatterns: List<String> = emptyList(),
    val excludedHosts: List<String> = listOf(
        "burpsuite.collaborator.net",
        "portswigger.net",
        "burp.collaboratorpayloads.net"
    ),
    val logResponseBodies: Boolean = true,
    val maxBodySizeBytes: Int = 102400  // 100KB cap per body
) {
    companion object {
        private val gson = Gson()

        fun load(): PatronusConfig {
            val configFile = File(configFilePath())

            return if (configFile.exists()) {
                try {
                    gson.fromJson(configFile.readText(), PatronusConfig::class.java)
                } catch (e: Exception) {
                    println("[Patronus] Warning: Could not parse config file, using defaults. Error: ${e.message}")
                    createDefault()
                }
            } else {
                createDefault()
            }
        }

        private fun createDefault(): PatronusConfig {
            val config = PatronusConfig()
            val configFile = File(configFilePath())
            configFile.parentFile.mkdirs()

            // Write default config so user can edit it
            configFile.writeText(
                gson.toJson(config).also {
                    println("[Patronus] Created default config at: ${configFile.absolutePath}")
                }
            )

            // Ensure output dir exists
            File(config.outputDir.expandHome()).mkdirs()

            return config
        }

        private fun configFilePath() =
            "${System.getProperty("user.home")}/.patronus/burp_config.json"

        private fun defaultOutputDir() =
            "${System.getProperty("user.home")}/.patronus/burp_sessions"
    }
}

/** Expand ~ to home directory */
fun String.expandHome(): String =
    if (startsWith("~")) replaceFirst("~", System.getProperty("user.home")) else this
