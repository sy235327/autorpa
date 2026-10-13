package com.firerpa.security

import android.content.Context
import android.os.Build
import com.firerpa.runtime.RootAccess
import com.firerpa.runtime.ShizukuAccess

object DeviceSerialNumber {
    private val invalidSerialValues = setOf(
        Build.UNKNOWN,
        "unknown",
        "null",
    )

    fun read(context: Context): String? {
        readSystemProperty("ro.serialno")?.let { return it }
        readSystemProperty("ro.boot.serialno")?.let { return it }
        readLegacyBuildSerial()?.let { return it }
        readViaShell()?.let { return it }
        return null
    }

    private fun readLegacyBuildSerial(): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            return null
        }
        return runCatching {
            @Suppress("DEPRECATION")
            Build.SERIAL.takeIf(::isUsableSerial)
        }.getOrNull()
    }

    @Suppress("PrivateApi")
    private fun readSystemProperty(name: String): String? {
        return runCatching {
            val systemProperties = Class.forName("android.os.SystemProperties")
            val get = systemProperties.getMethod("get", String::class.java, String::class.java)
            val value = get.invoke(null, name, "") as String
            value.takeIf(::isUsableSerial)
        }.getOrNull()
    }

    private fun readViaShell(): String? {
        val script = """
            for key in ro.serialno ro.boot.serialno; do
              value=$(getprop "${'$'}key")
              if [ -n "${'$'}value" ] && [ "${'$'}value" != "unknown" ]; then
                echo "${'$'}value"
                exit 0
              fi
            done
        """.trimIndent()

        if (RootAccess.isAvailable()) {
            RootAccess.runScript(script).output.lineSequence()
                .map { it.trim() }
                .firstOrNull(::isUsableSerial)
                ?.let { return it }
        }
        if (ShizukuAccess.canUseShell()) {
            ShizukuAccess.runScript(script).output.lineSequence()
                .map { it.trim() }
                .firstOrNull(::isUsableSerial)
                ?.let { return it }
        }
        return null
    }

    internal fun isUsableSerial(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.isNotEmpty() &&
            invalidSerialValues.none { it.equals(trimmed, ignoreCase = true) }
    }
}
