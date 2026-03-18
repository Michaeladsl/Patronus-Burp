package com.patronus

import burp.api.montoya.BurpExtension
import burp.api.montoya.MontoyaApi

class PatronusBurp : BurpExtension {

    override fun initialize(api: MontoyaApi) {
        api.extension().setName("Patronus")

        val logging = api.logging()
        logging.logToOutput(Banner.TEXT)

        val config = PatronusConfig.load()
        val redactor = Redactor(config)
        val sessionManager = SessionManager(config)
        val exportHandler = ExportHandler(sessionManager, config, logging)

        val httpLogger = HttpLogger(api, redactor, sessionManager, logging)
        api.http().registerHttpHandler(httpLogger)

        // registerSuiteTab takes (caption: String, component: Component)
        val tab = PatronusTab(sessionManager, exportHandler, config, api)
        api.userInterface().registerSuiteTab("Patronus", tab.buildPanel())

        api.extension().registerUnloadingHandler {
            logging.logToOutput("[Patronus] Unloading - auto-saving session...")
            exportHandler.exportJson()
            logging.logToOutput("[Patronus] Session saved to: ${config.outputDir}")
        }

        logging.logToOutput("[Patronus] Loaded - session: ${sessionManager.currentEngagement}")
        logging.logToOutput("[Patronus] Output dir: ${config.outputDir}")
        logging.logToOutput("[Patronus] Browse any target through Burp - requests will appear in the Patronus tab")
    }
}
