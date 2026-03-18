package com.patronus

import burp.api.montoya.MontoyaApi
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Component
import java.awt.Desktop
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.BorderFactory
import javax.swing.DefaultListSelectionModel
import javax.swing.JButton
import javax.swing.JFileChooser
import javax.swing.JLabel
import javax.swing.JMenuItem
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPopupMenu
import javax.swing.JScrollPane
import javax.swing.JSeparator
import javax.swing.JTable
import javax.swing.JTextField
import javax.swing.SwingConstants
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.border.EmptyBorder
import javax.swing.table.AbstractTableModel

class PatronusTab(
    private val sessionManager: SessionManager,
    private val exportHandler: ExportHandler,
    private val config: PatronusConfig,
    private val api: MontoyaApi
) {
    private val tableModel = RequestTableModel()
    private val statsLabel = JLabel("  No requests captured yet - browse a target through Burp to start")
    private val engagementField = JTextField(sessionManager.currentEngagement, 28)
    private val statusLabel = JLabel("  Ready")
    private val refreshTimer = Timer(2000) { refreshTable() }

    fun buildPanel(): Component {
        val panel = JPanel(BorderLayout())
        panel.background = Color(13, 17, 23)
        panel.add(buildHeader(), BorderLayout.NORTH)
        panel.add(buildTable(), BorderLayout.CENTER)
        panel.add(buildStatusBar(), BorderLayout.SOUTH)
        refreshTimer.start()
        return panel
    }

    private fun buildHeader(): JPanel {
        val header = JPanel(BorderLayout())
        header.background = Color(22, 27, 34)
        header.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(0, 0, 1, 0, Color(48, 54, 61)),
            EmptyBorder(12, 20, 12, 20)
        )

        val titleRow = JPanel(FlowLayout(FlowLayout.LEFT, 0, 0))
        titleRow.isOpaque = false
        val title = JLabel("Patronus")
        title.font = Font("Segoe UI", Font.BOLD, 15)
        title.foreground = Color(240, 165, 0)
        val subtitle = JLabel("  Burp Session Recorder")
        subtitle.font = Font("Segoe UI", Font.PLAIN, 12)
        subtitle.foreground = Color(139, 148, 158)
        titleRow.add(title)
        titleRow.add(subtitle)

        val controls = JPanel(FlowLayout(FlowLayout.LEFT, 8, 0))
        controls.isOpaque = false
        controls.border = EmptyBorder(8, 0, 4, 0)

        engagementField.font = Font("Monospaced", Font.PLAIN, 12)
        engagementField.background = Color(33, 38, 45)
        engagementField.foreground = Color(201, 209, 217)
        engagementField.caretColor = Color(240, 165, 0)
        engagementField.border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(48, 54, 61)),
            EmptyBorder(4, 8, 4, 8)
        )

        val engLabel = JLabel("Engagement:")
        engLabel.foreground = Color(139, 148, 158)

        controls.add(engLabel)
        controls.add(engagementField)
        controls.add(btn("Set Engagement", Color(33, 38, 45)) {
            sessionManager.switchEngagement(engagementField.text.trim())
            setStatus("Engagement set: ${sessionManager.currentEngagement}")
        })
        controls.add(JSeparator(SwingConstants.VERTICAL).also { it.preferredSize = Dimension(1, 22) })
        controls.add(btn("Export JSON", Color(21, 65, 110)) {
            val path = exportHandler.exportJson()
            setStatus(if (path != null) "JSON saved: $path" else "Export failed - check Burp output tab")
        })
        controls.add(btn("Export HTML Report", Color(30, 80, 40)) {
            val chooser = JFileChooser(config.outputDir.expandHome())
            chooser.dialogTitle = "Save HTML Report"
            chooser.selectedFile = File("${sessionManager.currentEngagement.replace(" ", "_")}_report.html")
            if (chooser.showSaveDialog(null) == JFileChooser.APPROVE_OPTION) {
                val path = exportHandler.exportHtml(chooser.selectedFile.absolutePath)
                if (path != null) {
                    setStatus("Report saved: $path")
                    try { Desktop.getDesktop().browse(File(path).toURI()) } catch (_: Exception) {}
                } else {
                    setStatus("HTML export failed")
                }
            }
        })
        controls.add(JSeparator(SwingConstants.VERTICAL).also { it.preferredSize = Dimension(1, 22) })
        controls.add(btn("Clear Session", Color(60, 30, 30)) {
            val ok = JOptionPane.showConfirmDialog(
                null,
                "Clear all requests in '${sessionManager.currentEngagement}'?",
                "Confirm Clear",
                JOptionPane.YES_NO_OPTION
            )
            if (ok == JOptionPane.YES_OPTION) {
                sessionManager.clearCurrentSession()
                refreshTable()
                setStatus("Session cleared")
            }
        })

        statsLabel.font = Font("Segoe UI", Font.PLAIN, 11)
        statsLabel.foreground = Color(139, 148, 158)

        val left = JPanel(BorderLayout())
        left.isOpaque = false
        left.add(titleRow, BorderLayout.NORTH)
        left.add(controls, BorderLayout.CENTER)
        left.add(statsLabel, BorderLayout.SOUTH)

        header.add(left, BorderLayout.WEST)
        return header
    }

    private fun buildTable(): JPanel {
        val table = JTable(tableModel)
        table.background = Color(13, 17, 23)
        table.foreground = Color(201, 209, 217)
        table.gridColor = Color(33, 38, 45)
        table.selectionBackground = Color(33, 38, 45)
        table.selectionForeground = Color(240, 165, 0)
        table.rowHeight = 26
        table.font = Font("Monospaced", Font.PLAIN, 12)
        table.tableHeader.background = Color(22, 27, 34)
        table.tableHeader.foreground = Color(139, 148, 158)
        table.tableHeader.font = Font("Segoe UI", Font.BOLD, 11)
        table.autoResizeMode = JTable.AUTO_RESIZE_LAST_COLUMN

        table.columnModel.getColumn(0).preferredWidth = 25
        table.columnModel.getColumn(1).preferredWidth = 65
        table.columnModel.getColumn(2).preferredWidth = 420
        table.columnModel.getColumn(3).preferredWidth = 80
        table.columnModel.getColumn(4).preferredWidth = 55
        table.columnModel.getColumn(5).preferredWidth = 80

        val popup = JPopupMenu()
        popup.add(JMenuItem("Toggle Flag").apply {
            addActionListener {
                val r = table.selectedRow
                if (r >= 0) { sessionManager.toggleFlag(tableModel.getId(r)); refreshTable() }
            }
        })
        popup.add(JMenuItem("Add Note...").apply {
            addActionListener {
                val r = table.selectedRow
                if (r >= 0) {
                    val note = JOptionPane.showInputDialog(null, "Note:", "Add Note", JOptionPane.PLAIN_MESSAGE)
                    if (note != null) sessionManager.addNote(tableModel.getId(r), note)
                }
            }
        })
        popup.addSeparator()
        popup.add(JMenuItem("Delete Request").apply {
            addActionListener {
                val r = table.selectedRow
                if (r >= 0) {
                    sessionManager.deleteRequest(tableModel.getId(r))
                    refreshTable()
                    setStatus("Request deleted")
                }
            }
        })
        table.componentPopupMenu = popup

        val scroll = JScrollPane(table)
        scroll.border = BorderFactory.createEmptyBorder()
        scroll.viewport.background = Color(13, 17, 23)

        val panel = JPanel(BorderLayout())
        panel.background = Color(13, 17, 23)
        panel.add(scroll, BorderLayout.CENTER)
        return panel
    }

    private fun buildStatusBar(): JPanel {
        val bar = JPanel(BorderLayout())
        bar.background = Color(22, 27, 34)
        bar.border = BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, Color(48, 54, 61)),
            EmptyBorder(4, 16, 4, 16)
        )
        statusLabel.font = Font("Segoe UI", Font.PLAIN, 11)
        statusLabel.foreground = Color(139, 148, 158)
        bar.add(statusLabel, BorderLayout.WEST)
        return bar
    }

    private fun refreshTable() {
        SwingUtilities.invokeLater {
            tableModel.setRequests(sessionManager.currentRequests())
            val stats = sessionManager.stats()
            statsLabel.text = if (stats.isNotEmpty())
                "  " + stats.entries.joinToString("  |  ") { "${it.key}: ${it.value}" }
            else
                "  No requests captured yet - browse a target through Burp to start"
        }
    }

    private fun setStatus(msg: String) {
        SwingUtilities.invokeLater { statusLabel.text = "  $msg" }
    }

    private fun btn(text: String, bg: Color, action: () -> Unit) = JButton(text).apply {
        font = Font("Segoe UI", Font.PLAIN, 12)
        background = bg
        foreground = Color(201, 209, 217)
        border = BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(Color(48, 54, 61)),
            EmptyBorder(5, 12, 5, 12)
        )
        isFocusPainted = false
        addActionListener { action() }
    }
}

// -- Table model using AbstractTableModel to avoid DefaultTableModel init issues --

class RequestTableModel : AbstractTableModel() {
    private val rows: MutableList<RedactedRequest> = ArrayList()
    private val fmt = SimpleDateFormat("HH:mm:ss")
    private val columns = arrayOf("F", "Method", "URL", "Tool", "Status", "Time")

    fun setRequests(list: List<RedactedRequest>) {
        rows.clear()
        rows.addAll(list)
        fireTableDataChanged()
    }

    fun getId(row: Int): String = rows.getOrNull(row)?.id ?: ""

    override fun getColumnCount(): Int = columns.size
    override fun getColumnName(col: Int): String = columns[col]
    override fun getRowCount(): Int = rows.size
    override fun isCellEditable(row: Int, col: Int) = false
    override fun getValueAt(row: Int, col: Int): Any {
        val r = rows.getOrNull(row) ?: return ""
        return when (col) {
            0 -> if (r.flagged) "*" else ""
            1 -> r.method
            2 -> r.url
            3 -> r.tool
            4 -> if (r.responseStatusCode > 0) r.responseStatusCode.toString() else "-"
            5 -> fmt.format(Date(r.timestamp))
            else -> ""
        }
    }
}
