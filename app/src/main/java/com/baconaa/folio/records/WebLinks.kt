package com.baconaa.folio.records

import java.net.IDN
import java.net.URI
import java.net.URISyntaxException
import java.util.Locale

class InvalidLinkAddress(val itemId: ItemId, val address: String) : IllegalArgumentException("请补全 HTTP 或 HTTPS 网址，或移除这一项。")

/** 只解析地址。国际域名转为浏览器可用的形式，草稿中保留用户输入的原文。 */
internal fun webAddress(input: String): String? {
    val uri = try { URI(input.trim()) } catch (_: URISyntaxException) { return null }
    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    if (scheme != "http" && scheme != "https") return null
    val authority = uri.rawAuthority ?: return null
    val hostPort = authority.substringAfterLast('@')
    val host = if (hostPort.startsWith('[')) hostPort.substringBefore(']') + "]" else hostPort.substringBefore(':')
    if (host.isEmpty()) return null
    val asciiHost = try {
        if (host.startsWith('[')) host else IDN.toASCII(host, IDN.USE_STD3_ASCII_RULES)
    } catch (_: IllegalArgumentException) { return null }
    val asciiAuthority = authority.dropLast(hostPort.length) + asciiHost + hostPort.removePrefix(host)
    val normalized = "$scheme://$asciiAuthority" + uri.rawPath.orEmpty() +
        (uri.rawQuery?.let { "?$it" } ?: "") + (uri.rawFragment?.let { "#$it" } ?: "")
    val parsed = try { URI(normalized).parseServerAuthority() } catch (_: URISyntaxException) { return null }
    return parsed.toASCIIString().takeIf { parsed.host != null && parsed.port <= 65535 }
}
