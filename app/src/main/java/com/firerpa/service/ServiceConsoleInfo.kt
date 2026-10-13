package com.firerpa.service

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

data class ServiceConsoleInfo(
    val backendUrls: List<String>,
    val credential: String?,
) {
    fun formatMcpEndpoints(): String {
        val password = credential?.trim().orEmpty()
        return backendUrls.joinToString("  ") { url ->
            val base = "${url.trimEnd('/')}/mcp/"
            if (password.isEmpty()) {
                base
            } else {
                val encodedToken = URLEncoder.encode(password, StandardCharsets.UTF_8.name())
                "${base}?token=$encodedToken"
            }
        }
    }
}
