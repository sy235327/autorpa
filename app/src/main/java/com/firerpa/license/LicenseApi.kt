package com.firerpa.license

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * [fork] 卡密业务网关客户端（卡密页与悬浮窗服务共用）。
 * 统一封装 verify / me / pause / resume / stop 五个接口。
 */
object LicenseApi {
    private const val TIMEOUT_CONNECT = 8000
    private const val TIMEOUT_READ = 15000

    fun baseUrl(host: String, port: String): String {
        val clean = host.trim()
            .removePrefix("http://").removePrefix("https://")
            .trimEnd('/')
        val cleanPort = port.trim().ifBlank { "8000" }
        return "http://$clean:$cleanPort"
    }

    fun verify(baseUrl: String, code: String, devId: String): JSONObject =
        request("POST", "$baseUrl/api/v1/license/verify", null,
            mapOf("code" to code, "dev_id" to devId))

    fun me(baseUrl: String, token: String): JSONObject =
        request("GET", "$baseUrl/api/v1/license/me", token, null)

    fun pause(baseUrl: String, token: String): JSONObject = action(baseUrl, token, "pause")
    fun resume(baseUrl: String, token: String): JSONObject = action(baseUrl, token, "resume")
    fun stopRun(baseUrl: String, token: String): JSONObject = action(baseUrl, token, "stop")

    fun acceptUpdate(baseUrl: String, token: String): JSONObject =
        request("POST", "$baseUrl/api/v1/license/update/accept", token, emptyMap())

    // [M3] 我的卡密 / 快捷切换 / 绑定设备 / 解绑
    fun myCards(baseUrl: String, token: String): JSONObject =
        request("GET", "$baseUrl/api/v1/license/my-cards", token, null)

    fun switchCard(baseUrl: String, token: String, cardId: Int): JSONObject =
        request("POST", "$baseUrl/api/v1/license/switch", token,
            mapOf("card_id" to cardId.toString()))

    fun cardDevices(baseUrl: String, token: String, cardId: Int): JSONObject =
        request("GET", "$baseUrl/api/v1/license/card-devices?card_id=$cardId", token, null)

    fun unbind(baseUrl: String, token: String, cardId: Int, devId: String): JSONObject =
        request("POST", "$baseUrl/api/v1/license/unbind", token,
            mapOf("card_id" to cardId.toString(), "dev_id" to devId))

    // [M3] 换机场景：未激活时凭卡密查询绑定 / 解绑（卡密即凭据）
    fun cardBindingsByCode(baseUrl: String, code: String, devId: String): JSONObject =
        request("GET",
            "$baseUrl/api/v1/license/card-devices?code=${enc(code)}&dev_id=${enc(devId)}",
            null, null)

    fun unbindByCode(baseUrl: String, code: String, devId: String): JSONObject =
        request("POST", "$baseUrl/api/v1/license/unbind", null,
            mapOf("code" to code, "dev_id" to devId))

    private fun action(baseUrl: String, token: String, name: String): JSONObject =
        request("POST", "$baseUrl/api/v1/license/$name", token, emptyMap())

    private fun request(
        method: String,
        url: String,
        token: String?,
        form: Map<String, String>?,
    ): JSONObject {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = TIMEOUT_CONNECT
            readTimeout = TIMEOUT_READ
            if (!token.isNullOrBlank()) {
                setRequestProperty("X-License-Token", token)
            }
            if (form != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            }
        }
        if (form != null) {
            val body = form.entries.joinToString("&") { (k, v) -> k + "=" + enc(v) }
            conn.outputStream.use { it.write(body.toByteArray(StandardCharsets.UTF_8)) }
        }
        val text = readBody(conn)
        return runCatching { JSONObject(text) }.getOrElse {
            throw IllegalStateException("非 JSON 响应：${text.take(160)}")
        }
    }

    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        }
        return stream.bufferedReader().use { it.readText() }
    }

    private fun enc(value: String): String =
        URLEncoder.encode(value, StandardCharsets.UTF_8.name())
}
