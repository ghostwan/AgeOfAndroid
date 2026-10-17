package com.ghostwan.ageofandroid.net

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * What a guest needs to join a game, shared as a link in the host's QR code:
 * `ageofandroid://join?h=192.168.1.20,10.0.0.4&p=47820&n=Erwan`.
 * Scanning it with the phone's camera opens the game directly (see the manifest).
 * [hosts] lists every local address of the host: the guest tries them in order.
 */
data class JoinLink(val hosts: List<String>, val port: Int = DEFAULT_PORT, val name: String = "") {
    override fun toString(): String =
        "$SCHEME://join?h=${hosts.joinToString(",")}&p=$port" + if (name.isNotEmpty()) "&n=${URLEncoder.encode(name, "UTF-8")}" else ""

    companion object {
        const val SCHEME = "ageofandroid"

        /** Accepts a join link, or a bare "ip" / "ip:port". */
        fun parse(text: String): JoinLink? {
            val t = text.trim()
            if (t.startsWith("$SCHEME://", ignoreCase = true)) {
                return try {
                    val query = URI(t).rawQuery ?: return null
                    val params = query.split('&').mapNotNull {
                        val i = it.indexOf('=')
                        if (i <= 0) null else it.substring(0, i) to URLDecoder.decode(it.substring(i + 1), "UTF-8")
                    }.toMap()
                    val hosts = params["h"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty()
                    if (hosts.isEmpty()) null else JoinLink(hosts, params["p"]?.toIntOrNull() ?: DEFAULT_PORT, params["n"].orEmpty())
                } catch (e: Exception) {
                    null
                }
            }
            if (t.isEmpty() || t.any { it.isWhitespace() || it == '/' }) return null
            val i = t.lastIndexOf(':')
            if (i > 0) t.substring(i + 1).toIntOrNull()?.let { return JoinLink(listOf(t.substring(0, i)), it) }
            return JoinLink(listOf(t))
        }
    }
}
