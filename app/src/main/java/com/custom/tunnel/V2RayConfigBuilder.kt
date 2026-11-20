package com.custom.tunnel

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

object V2RayConfigBuilder {

    data class ParsedV2Ray(
        val remarks: String,
        val summaryMethod: String,
        val fullConfigJson: String
    )

    fun build(
        rawInput: String,
        bugAddress: String = "",
        customHostSni: String = "",
        socksPort: Int = 1080
    ): ParsedV2Ray {
        val trimmed = rawInput.trim()
        require(trimmed.isNotEmpty()) { "Link atau JSON V2Ray masih kosong" }

        var remarks = "V2Ray"
        var summary = "V2Ray"
        val fullConfig: JSONObject

        if (trimmed.startsWith("{")) {
            val obj = JSONObject(trimmed)
            if (obj.has("outbounds")) {
                fullConfig = obj
                val firstOut = obj.optJSONArray("outbounds")?.optJSONObject(0)
                val proto = firstOut?.optString("protocol", "v2ray")?.uppercase() ?: "V2RAY"
                val net = firstOut?.optJSONObject("streamSettings")?.optString("network", "tcp")?.uppercase() ?: "TCP"
                summary = "$proto • $net"
                remarks = obj.optString("remarks", "V2Ray JSON")
            } else {
                val proto = obj.optString("protocol", "v2ray").uppercase()
                val net = obj.optJSONObject("streamSettings")?.optString("network", "tcp")?.uppercase() ?: "TCP"
                summary = "$proto • $net"
                if (!obj.has("tag")) obj.put("tag", "proxy")
                fullConfig = wrapOutboundInFullConfig(obj)
            }
        } else if (trimmed.startsWith("vmess://", ignoreCase = true)) {
            val b64 = trimmed.substring(8).trim().replace("\\s+".toRegex(), "")
            val v = JSONObject(decodeBase64Flexible(b64))
            remarks = v.optString("ps", "").trim().ifEmpty { "VMess" }
            val add = v.optString("add", "").trim()
            val port = v.optString("port", "443").trim().toIntOrNull() ?: 443
            val id = v.optString("id", "").trim()
            val aid = v.optString("aid", "0").trim().toIntOrNull() ?: 0
            val scy = v.optString("scy", "auto").trim().ifEmpty { "auto" }
            val net = v.optString("net", "tcp").trim().ifEmpty { "tcp" }
            val type = v.optString("type", "none").trim()
            val host = v.optString("host", "").trim()
            val path = v.optString("path", "/").trim()
            val tls = v.optString("tls", "").trim()
            val sni = v.optString("sni", "").trim().ifEmpty { host.ifEmpty { add } }
            val alpn = v.optString("alpn", "").trim()
            val fp = v.optString("fp", "chrome").trim().ifEmpty { "chrome" }
            require(add.isNotEmpty() && id.isNotEmpty()) { "Data VMess tidak lengkap" }

            val userObj = JSONObject().put("id", id).put("alterId", aid).put("security", scy)
            val vnextObj = JSONObject().put("address", add).put("port", port).put("users", JSONArray().put(userObj))
            val outbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vmess")
                put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
                put("streamSettings", buildStreamSettings(net, type, host, path, tls, sni, alpn, fp, "", ""))
            }
            summary = buildSummary("VMess", net, tls)
            fullConfig = wrapOutboundInFullConfig(outbound)
        } else if (trimmed.startsWith("vless://", ignoreCase = true)) {
            val u = parseUriParts(trimmed)
            remarks = u.fragment.ifEmpty { "VLESS" }
            require(u.host.isNotEmpty() && u.userInfo.isNotEmpty()) { "Link VLESS tidak valid" }

            val net = u.params["type"] ?: u.params["net"] ?: "tcp"
            val security = u.params["security"] ?: "none"
            val headerType = u.params["headerType"] ?: "none"
            val host = u.params["host"] ?: ""
            val path = u.params["path"] ?: "/"
            val sni = u.params["sni"] ?: host.ifEmpty { u.host }
            val alpn = u.params["alpn"] ?: ""
            val fp = u.params["fp"] ?: "chrome"
            val flow = u.params["flow"] ?: ""
            val pbk = u.params["pbk"] ?: ""
            val sid = u.params["sid"] ?: ""

            val userObj = JSONObject().apply {
                put("id", u.userInfo)
                put("encryption", u.params["encryption"] ?: "none")
                if (flow.isNotEmpty()) put("flow", flow)
            }
            val vnextObj = JSONObject().put("address", u.host).put("port", if (u.port > 0) u.port else 443).put("users", JSONArray().put(userObj))
            val outbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "vless")
                put("settings", JSONObject().put("vnext", JSONArray().put(vnextObj)))
                put("streamSettings", buildStreamSettings(net, headerType, host, path, security, sni, alpn, fp, pbk, sid))
            }
            summary = buildSummary("VLESS", net, security)
            fullConfig = wrapOutboundInFullConfig(outbound)
        } else if (trimmed.startsWith("trojan://", ignoreCase = true)) {
            val u = parseUriParts(trimmed)
            remarks = u.fragment.ifEmpty { "Trojan" }
            require(u.host.isNotEmpty() && u.userInfo.isNotEmpty()) { "Link Trojan tidak valid" }

            val net = u.params["type"] ?: u.params["net"] ?: "tcp"
            val security = u.params["security"] ?: "tls"
            val headerType = u.params["headerType"] ?: "none"
            val host = u.params["host"] ?: ""
            val path = u.params["path"] ?: "/"
            val sni = u.params["sni"] ?: host.ifEmpty { u.host }
            val alpn = u.params["alpn"] ?: ""
            val fp = u.params["fp"] ?: "chrome"

            val serverObj = JSONObject().put("address", u.host).put("port", if (u.port > 0) u.port else 443).put("password", u.userInfo)
            val outbound = JSONObject().apply {
                put("tag", "proxy")
                put("protocol", "trojan")
                put("settings", JSONObject().put("servers", JSONArray().put(serverObj)))
                put("streamSettings", buildStreamSettings(net, headerType, host, path, security, sni, alpn, fp, "", ""))
            }
            summary = buildSummary("Trojan", net, security)
            fullConfig = wrapOutboundInFullConfig(outbound)
        } else if (trimmed.startsWith("ss://", ignoreCase = true)) {
            val parsedSs = parseShadowsocksUri(trimmed)
            remarks = parsedSs.first
            fullConfig = wrapOutboundInFullConfig(parsedSs.second)
            summary = "Shadowsocks • TCP"
        } else {
            throw IllegalArgumentException("Format harus vmess://, vless://, trojan://, ss://, atau JSON")
        }

        val inbounds = JSONArray().put(
            JSONObject().apply {
                put("tag", "socks-in")
                put("port", socksPort)
                put("listen", "127.0.0.1")
                put("protocol", "socks")
                put("settings", JSONObject().put("auth", "noauth").put("udp", true))
            }
        )
        fullConfig.put("inbounds", inbounds)

        val firstOutbound = fullConfig.optJSONArray("outbounds")?.optJSONObject(0)
        if (firstOutbound != null) {
            val cleanBug = bugAddress.trim()
            if (cleanBug.isNotEmpty()) {
                val settings = firstOutbound.optJSONObject("settings")
                settings?.optJSONArray("vnext")?.optJSONObject(0)?.put("address", cleanBug)
                settings?.optJSONArray("servers")?.optJSONObject(0)?.put("address", cleanBug)
            }
            val cleanHost = customHostSni.trim()
            val stream = firstOutbound.optJSONObject("streamSettings")
            if (stream != null) {
                stream.optJSONObject("tlsSettings")?.apply {
                    put("allowInsecure", true)
                    if (cleanHost.isNotEmpty()) put("serverName", cleanHost)
                }
                if (cleanHost.isNotEmpty()) {
                    stream.optJSONObject("realitySettings")?.put("serverName", cleanHost)
                    stream.optJSONObject("wsSettings")?.let { ws ->
                        val hdrs = ws.optJSONObject("headers") ?: JSONObject().also { ws.put("headers", it) }
                        hdrs.put("Host", cleanHost)
                    }
                    stream.optJSONObject("httpupgradeSettings")?.put("host", cleanHost)
                    stream.optJSONObject("xhttpSettings")?.put("host", cleanHost)
                }
            }
        }
        return ParsedV2Ray(remarks, summary, fullConfig.toString(2))
    }

    data class EditableV2Ray(
        val remarks: String = "V2Ray",
        val protocol: String = "vmess",
        val addressPort: String = "",
        val uuid: String = "",
        val security: String = "auto",
        val flow: String = "",
        val network: String = "ws",
        val path: String = "/",
        val host: String = "",
        val tls: Boolean = false,
        val sni: String = ""
    )

    fun parseToEditable(rawInput: String): EditableV2Ray {
        val parsed = build(rawInput)
        val root = JSONObject(parsed.fullConfigJson)
        val out = root.optJSONArray("outbounds")?.optJSONObject(0) ?: JSONObject()
        val proto = out.optString("protocol", "vmess").lowercase()
        val settings = out.optJSONObject("settings") ?: JSONObject()

        var addr = ""
        var port = 443
        var uuid = ""
        var sec = "auto"
        var flow = ""

        val vnext = settings.optJSONArray("vnext")?.optJSONObject(0)
        val srv = settings.optJSONArray("servers")?.optJSONObject(0)
        if (vnext != null) {
            addr = vnext.optString("address", "")
            port = vnext.optInt("port", 443)
            val u = vnext.optJSONArray("users")?.optJSONObject(0)
            if (u != null) {
                uuid = u.optString("id", "")
                sec = if (proto == "vless") u.optString("encryption", "none") else u.optString("security", "auto")
                flow = u.optString("flow", "").ifEmpty { u.optInt("alterId", 0).takeIf { it > 0 }?.toString() ?: "" }
            }
        } else if (srv != null) {
            addr = srv.optString("address", "")
            port = srv.optInt("port", 443)
            uuid = srv.optString("password", "")
            sec = srv.optString("method", "auto")
        }

        val stream = out.optJSONObject("streamSettings") ?: JSONObject()
        val net = stream.optString("network", "ws").lowercase()
        val secType = stream.optString("security", "none").lowercase()
        val isTls = secType == "tls" || secType == "reality"

        var path = "/"
        var host = ""
        when (net) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings")
                path = ws?.optString("path", "/") ?: "/"
                host = ws?.optJSONObject("headers")?.optString("Host", "") ?: ""
            }
            "httpupgrade" -> {
                val hu = stream.optJSONObject("httpupgradeSettings")
                path = hu?.optString("path", "/") ?: "/"
                host = hu?.optString("host", "") ?: ""
            }
            "xhttp" -> {
                val xh = stream.optJSONObject("xhttpSettings")
                path = xh?.optString("path", "/") ?: "/"
                host = xh?.optString("host", "") ?: ""
            }
            "grpc" -> {
                path = stream.optJSONObject("grpcSettings")?.optString("serviceName", "") ?: ""
            }
        }

        val sni = stream.optJSONObject("tlsSettings")?.optString("serverName", "")
            ?.ifEmpty { stream.optJSONObject("realitySettings")?.optString("serverName", "") ?: "" }
            ?: ""

        val addrPort = if (addr.isNotEmpty()) "$addr:$port" else ""
        return EditableV2Ray(parsed.remarks, proto, addrPort, uuid, sec, flow, net, path, host, isTls, sni)
    }

    fun buildOutboundJsonFromEditable(e: EditableV2Ray): String {
        val rawAp = e.addressPort.trim()
        val hostPart: String
        val portPart: Int
        if (rawAp.startsWith("[")) {
            hostPart = rawAp.substringBefore("]").removePrefix("[")
            portPart = rawAp.substringAfter("]:", "443").toIntOrNull() ?: 443
        } else if (rawAp.contains(":")) {
            hostPart = rawAp.substringBeforeLast(":")
            portPart = rawAp.substringAfterLast(":").toIntOrNull() ?: 443
        } else {
            hostPart = rawAp
            portPart = 443
        }

        val proto = e.protocol.lowercase()
        val outbound = JSONObject().put("tag", "proxy").put("protocol", proto)

        when (proto) {
            "vmess" -> {
                val u = JSONObject().put("id", e.uuid.trim()).put("alterId", e.flow.trim().toIntOrNull() ?: 0).put("security", e.security.ifEmpty { "auto" })
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", hostPart).put("port", portPart).put("users", JSONArray().put(u)))))
            }
            "vless" -> {
                val u = JSONObject().put("id", e.uuid.trim()).put("encryption", "none")
                if (e.flow.trim().isNotEmpty() && e.flow.trim() != "0") u.put("flow", e.flow.trim())
                outbound.put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject().put("address", hostPart).put("port", portPart).put("users", JSONArray().put(u)))))
            }
            "trojan" -> {
                val s = JSONObject().put("address", hostPart).put("port", portPart).put("password", e.uuid.trim())
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(s)))
            }
            "shadowsocks" -> {
                val s = JSONObject().put("address", hostPart).put("port", portPart).put("method", e.security.ifEmpty { "aes-128-gcm" }).put("password", e.uuid.trim())
                outbound.put("settings", JSONObject().put("servers", JSONArray().put(s)))
            }
        }

        val net = e.network.lowercase()
        val secStr = if (e.tls) "tls" else "none"
        val sniVal = e.sni.trim().ifEmpty { e.host.trim().ifEmpty { hostPart } }
        outbound.put("streamSettings", buildStreamSettings(net, "none", e.host.trim(), e.path.trim(), secStr, sniVal, "", "chrome", "", ""))

        return JSONObject().put("outbounds", JSONArray().put(outbound)).toString(2)
    }

    private fun wrapOutboundInFullConfig(proxyOutbound: JSONObject): JSONObject {
        val outbounds = JSONArray().apply {
            put(proxyOutbound)
            put(JSONObject().put("tag", "direct").put("protocol", "freedom"))
            put(JSONObject().put("tag", "block").put("protocol", "blackhole"))
        }
        return JSONObject().put("log", JSONObject().put("loglevel", "warning")).put("outbounds", outbounds)
    }

    private fun buildStreamSettings(
        net: String, headerType: String, host: String, path: String,
        security: String, sni: String, alpn: String, fp: String, pbk: String, sid: String
    ): JSONObject {
        val normNet = net.lowercase()
        val stream = JSONObject().put("network", normNet)
        when (normNet) {
            "ws" -> {
                val ws = JSONObject().put("path", path.ifEmpty { "/" })
                if (host.isNotEmpty()) ws.put("headers", JSONObject().put("Host", host))
                stream.put("wsSettings", ws)
            }
            "httpupgrade" -> {
                val hu = JSONObject().put("path", path.ifEmpty { "/" })
                if (host.isNotEmpty()) hu.put("host", host)
                stream.put("httpupgradeSettings", hu)
            }
            "xhttp" -> {
                val xh = JSONObject().put("path", path.ifEmpty { "/" })
                if (host.isNotEmpty()) xh.put("host", host)
                stream.put("xhttpSettings", xh)
            }
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", path.removePrefix("/")))
            "tcp" -> if (headerType.equals("http", ignoreCase = true)) {
                val req = JSONObject().put("version", "1.1").put("method", "GET").put("path", JSONArray().put(path.ifEmpty { "/" }))
                if (host.isNotEmpty()) req.put("headers", JSONObject().put("Host", JSONArray().put(host)))
                stream.put("tcpSettings", JSONObject().put("header", JSONObject().put("type", "http").put("request", req)))
            }
        }
        val normSec = security.lowercase()
        if (normSec == "tls") {
            stream.put("security", "tls")
            val tlsObj = JSONObject().put("allowInsecure", true)
            if (sni.isNotEmpty()) tlsObj.put("serverName", sni)
            if (fp.isNotEmpty()) tlsObj.put("fingerprint", fp)
            if (alpn.isNotEmpty()) {
                val arr = JSONArray()
                alpn.split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { arr.put(it) }
                if (arr.length() > 0) tlsObj.put("alpn", arr)
            }
            stream.put("tlsSettings", tlsObj)
        } else if (normSec == "reality") {
            stream.put("security", "reality")
            stream.put("realitySettings", JSONObject().put("serverName", sni).put("fingerprint", fp.ifEmpty { "chrome" }).put("publicKey", pbk).put("shortId", sid))
        } else {
            stream.put("security", "none")
        }
        return stream
    }

    private fun buildSummary(proto: String, net: String, sec: String): String {
        val parts = mutableListOf(proto, net.uppercase())
        if (sec.isNotEmpty() && !sec.equals("none", ignoreCase = true)) parts.add(sec.uppercase())
        return parts.joinToString(" • ")
    }

    private data class UriParts(val userInfo: String, val host: String, val port: Int, val params: Map<String, String>, val fragment: String)

    private fun parseUriParts(raw: String): UriParts {
        val hashIdx = raw.indexOf('#')
        val fragment = if (hashIdx != -1) urlDecode(raw.substring(hashIdx + 1)) else ""
        val withoutFrag = if (hashIdx != -1) raw.substring(0, hashIdx) else raw
        val qIdx = withoutFrag.indexOf('?')
        val queryStr = if (qIdx != -1) withoutFrag.substring(qIdx + 1) else ""
        val base = if (qIdx != -1) withoutFrag.substring(0, qIdx) else withoutFrag
        val authAndHost = base.substringAfter("://")
        val atIdx = authAndHost.lastIndexOf('@')
        val userInfo = if (atIdx != -1) urlDecode(authAndHost.substring(0, atIdx)) else ""
        val hostPort = if (atIdx != -1) authAndHost.substring(atIdx + 1) else authAndHost
        val parsedUri = URI("http://$hostPort")
        val host = (parsedUri.host ?: hostPort.substringBefore(':')).trim('[', ']')
        val params = mutableMapOf<String, String>()
        if (queryStr.isNotEmpty()) {
            for (pair in queryStr.split('&')) {
                val eq = pair.indexOf('=')
                if (eq > 0) params[urlDecode(pair.substring(0, eq))] = urlDecode(pair.substring(eq + 1))
            }
        }
        return UriParts(userInfo, host, parsedUri.port, params, fragment)
    }

    private fun parseShadowsocksUri(raw: String): Pair<String, JSONObject> {
        val hashIdx = raw.indexOf('#')
        val name = if (hashIdx != -1) urlDecode(raw.substring(hashIdx + 1)).ifEmpty { "Shadowsocks" } else "Shadowsocks"
        val body = (if (hashIdx != -1) raw.substring(5, hashIdx) else raw.substring(5)).substringBefore('?')
        val decodedFull = if (body.contains('@')) {
            val at = body.lastIndexOf('@')
            val userPart = body.substring(0, at)
            val creds = if (userPart.contains(':')) userPart else decodeBase64Flexible(userPart)
            "$creds@${body.substring(at + 1)}"
        } else decodeBase64Flexible(body)

        val at = decodedFull.lastIndexOf('@')
        require(at != -1) { "Format ss:// tidak valid" }
        val creds = decodedFull.substring(0, at)
        val hostPort = decodedFull.substring(at + 1)
        val colon = creds.indexOf(':')
        val u = URI("http://$hostPort")
        val server = JSONObject().put("address", (u.host ?: hostPort.substringBefore(':')).trim('[', ']')).put("port", if (u.port > 0) u.port else 8388).put("method", creds.substring(0, colon)).put("password", creds.substring(colon + 1))
        return Pair(name, JSONObject().put("tag", "proxy").put("protocol", "shadowsocks").put("settings", JSONObject().put("servers", JSONArray().put(server))))
    }

    private fun decodeBase64Flexible(input: String): String {
        val clean = input.replace('-', '+').replace('_', '/')
        val padded = clean + "=".repeat((4 - clean.length % 4) % 4)
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    private fun urlDecode(s: String): String = try { URLDecoder.decode(s, "UTF-8") } catch (_: Exception) { s }
}
