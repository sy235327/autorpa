package com.firerpa.runtime

object EnvironmentFileParser {
    fun parse(content: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        content.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .forEach { line ->
                val separatorIndex = line.indexOf('=')
                if (separatorIndex <= 0) {
                    return@forEach
                }
                val key = line.substring(0, separatorIndex).trim()
                val value = line.substring(separatorIndex + 1).trim()
                if (key.isNotEmpty()) {
                    values[key] = value
                }
            }
        return values
    }
}
