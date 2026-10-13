package com.firerpa.security

import android.content.Context
import android.os.Build

object DeviceCnSanitizer {
    private const val MAX_CN_LENGTH = 64
    private const val FALLBACK_CN = "FIRERPA"

    fun fromDevice(context: Context): String {
        return fromDeviceParts(
            brand = Build.BRAND,
            model = Build.MODEL,
            serialNumber = DeviceSerialNumber.read(context),
        )
    }

    fun fromDeviceParts(brand: String, model: String, serialNumber: String? = null): String {
        val parts = buildList {
            sanitizeComponent(brand).takeIf { it.isNotBlank() }?.let(::add)
            sanitizeComponent(model).takeIf { it.isNotBlank() }?.let(::add)
            serialNumber
                ?.takeIf(DeviceSerialNumber::isUsableSerial)
                ?.let(::sanitizeComponent)
                ?.takeIf { it.isNotBlank() }
                ?.let(::add)
        }
        return limitCn(parts.joinToString("_").ifBlank { FALLBACK_CN })
    }

    internal fun sanitizeComponent(value: String): String {
        return buildString {
            for (character in value.trim()) {
                append(
                    when {
                        character.isLetterOrDigit() -> character
                        character == ' ' -> '_'
                        character == '_' || character == '-' || character == '.' -> character
                        else -> '_'
                    },
                )
            }
        }
            .replace(Regex("_+"), "_")
            .trim('_')
    }

    private fun limitCn(cn: String): String {
        return if (cn.length <= MAX_CN_LENGTH) {
            cn
        } else {
            cn.take(MAX_CN_LENGTH).trim('_').ifBlank { FALLBACK_CN }
        }
    }
}
