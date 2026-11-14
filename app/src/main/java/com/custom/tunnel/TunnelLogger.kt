package com.custom.tunnel

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelLogger {
    private val logs = mutableListOf<String>()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    var onLogUpdated: (() -> Unit)? = null

    @Synchronized
    fun log(message: String, colorHex: String = "#E0E0E0") {
        val time = timeFormat.format(Date())
        val escaped = message.replace("<", "&lt;").replace(">", "&gt;")
        logs.add("<font color='#757575'>[$time]</font> <font color='$colorHex'>$escaped</font>")
        if (logs.size > 300) logs.removeAt(0)
        onLogUpdated?.invoke()
    }

    @Synchronized
    fun rawHtml(html: String) {
        val time = timeFormat.format(Date())
        logs.add("<font color='#757575'>[$time]</font> $html")
        if (logs.size > 300) logs.removeAt(0)
        onLogUpdated?.invoke()
    }

    @Synchronized
    fun clear() {
        logs.clear()
        onLogUpdated?.invoke()
    }

    @Synchronized
    fun getHtmlLogs(): String = logs.joinToString("<br/>")
}
