package com.custom.tunnel

import android.text.Html
import android.text.Spanned
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object TunnelLogger {
    private val _logs = MutableStateFlow<Spanned>(Html.fromHtml("", Html.FROM_HTML_MODE_LEGACY))
    val logs = _logs.asStateFlow()
    private val buffer = StringBuilder()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun log(message: String, colorHex: String = "#D0D7DE") {
        val time = timeFormat.format(Date())
        buffer.append("<font color='#7D8590'>[$time]</font> <font color='$colorHex'>$message</font><br>")
        _logs.value = Html.fromHtml(buffer.toString(), Html.FROM_HTML_MODE_LEGACY)
    }

    fun rawHtml(html: String) {
        buffer.append("$html<br>")
        _logs.value = Html.fromHtml(buffer.toString(), Html.FROM_HTML_MODE_LEGACY)
    }

    fun clear() {
        buffer.clear()
        _logs.value = Html.fromHtml("", Html.FROM_HTML_MODE_LEGACY)
    }
}
