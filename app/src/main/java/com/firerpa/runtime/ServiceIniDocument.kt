package com.firerpa.runtime

import android.content.Context
import com.firerpa.R
import org.ini4j.Ini
import org.ini4j.InvalidFileFormatException
import org.ini4j.Profile
import java.io.StringReader

data class IniAuthSettings(
    val enabled: Boolean,
    val password: String,
)

data class ValidationResult(
    val isValid: Boolean,
    val message: String,
)

object ServiceIniDocument {
    const val SECTION_DEFAULT = "DEFAULT"
    const val KEY_PORT = "port"
    const val KEY_CERT = "cert"
    const val KEY_SSL_WEB_CREDENTIAL = "ssl-web-credential"
    const val AUTH_PASSWORD_MIN_LENGTH = 5
    const val AUTH_PASSWORD_MAX_LENGTH = 32

    private val defaultAuthKeys = setOf(KEY_CERT, KEY_SSL_WEB_CREDENTIAL)

    private fun isAuthKey(key: String): Boolean {
        return key == KEY_CERT || key == KEY_SSL_WEB_CREDENTIAL
    }

    fun isValidAuthPassword(password: String): Boolean {
        return password.length in AUTH_PASSWORD_MIN_LENGTH..AUTH_PASSWORD_MAX_LENGTH
    }

    fun validate(context: Context, raw: String): ValidationResult {
        if (raw.isBlank()) {
            return ValidationResult(false, context.getString(R.string.ini_empty))
        }

        return try {
            val ini = load(raw)
            val section = ini[SECTION_DEFAULT]
                ?: return ValidationResult(false, context.getString(R.string.ini_missing_default_section))

            if (!section.containsKey(KEY_PORT) || section[KEY_PORT].isNullOrBlank()) {
                return ValidationResult(false, context.getString(R.string.ini_missing_port))
            }

            ValidationResult(true, context.getString(R.string.ini_valid))
        } catch (_: InvalidFileFormatException) {
            ValidationResult(false, context.getString(R.string.ini_invalid_format))
        } catch (_: Exception) {
            ValidationResult(false, context.getString(R.string.ini_invalid_format))
        }
    }

    fun readAuthSettings(raw: String): IniAuthSettings {
        return try {
            val section = load(raw)[SECTION_DEFAULT]
            val password = section?.get(KEY_SSL_WEB_CREDENTIAL)?.trim().orEmpty()
            IniAuthSettings(enabled = password.isNotEmpty(), password = password)
        } catch (_: Exception) {
            IniAuthSettings(enabled = false, password = "")
        }
    }

    fun requiresForcedAccessAuthentication(raw: String): Boolean {
        return try {
            val ini = load(raw)
            ini.keys.any { sectionName ->
                sectionName != SECTION_DEFAULT &&
                    ini[sectionName]?.containsKey(KEY_CERT) == true
            }
        } catch (_: Exception) {
            false
        }
    }

    fun toEditorContent(raw: String): String {
        val ini = load(raw)
        stripDefaultAuthKeys(ini)
        return store(ini)
    }

    fun previewEditorContent(
        raw: String,
        authEnabled: Boolean,
        password: String,
        clientPemBase64: String,
    ): String {
        val base = toEditorContent(raw)
        return if (authEnabled) {
            applyAuthSettings(
                raw = base,
                enabled = true,
                password = password,
                clientPemBase64 = clientPemBase64,
            )
        } else {
            base
        }
    }

    fun hasEditorChanges(originalRaw: String, rawEditor: String): Boolean {
        return toEditorContent(originalRaw).trim() != toEditorContent(rawEditor).trim()
    }

    fun mergeEditorPreservingAuthKeys(rawEditor: String, originalRaw: String): String {
        if (!hasEditorChanges(originalRaw, rawEditor)) {
            return originalRaw
        }
        val original = load(originalRaw)
        val editor = load(toEditorContent(rawEditor))
        for (sectionName in editor.keys) {
            val editorSection = editor[sectionName] ?: continue
            val targetSection = original[sectionName] ?: original.add(sectionName)
            targetSection.keys.toList().forEach { key ->
                if (!isAuthKey(key) && !editorSection.containsKey(key)) {
                    targetSection.remove(key)
                }
            }
            editorSection.keys.forEach { key ->
                if (!isAuthKey(key)) {
                    targetSection[key] = editorSection[key].orEmpty()
                }
            }
        }
        return store(original)
    }

    fun applyAuthSettings(
        raw: String,
        enabled: Boolean,
        password: String,
        clientPemBase64: String,
    ): String {
        val ini = load(raw)
        val authEntries = if (enabled) {
            listOf(
                KEY_CERT to clientPemBase64,
                KEY_SSL_WEB_CREDENTIAL to password.trim(),
            )
        } else {
            emptyList()
        }
        rewriteDefaultSection(ini, authEntries)
        return store(ini)
    }

    private fun stripDefaultAuthKeys(ini: Ini) {
        val section = ini[SECTION_DEFAULT] ?: return
        defaultAuthKeys.forEach { key ->
            section.remove(key)
        }
    }

    private fun rewriteDefaultSection(ini: Ini, authEntries: List<Pair<String, String>>) {
        val section = ini[SECTION_DEFAULT] ?: ini.add(SECTION_DEFAULT)
        val remaining = linkedMapOf<String, String>()
        section.keys.forEach { key ->
            if (key !in defaultAuthKeys) {
                remaining[key] = section[key].orEmpty()
            }
        }
        defaultAuthKeys.forEach { key ->
            section.remove(key)
        }
        remaining.forEach { (key, value) ->
            section[key] = value
        }
        authEntries.forEach { (key, value) ->
            section[key] = value
        }
    }

    private fun store(ini: Ini): String {
        return buildString {
            ini.keys.forEachIndexed { index, sectionName ->
                if (index > 0) {
                    append('\n')
                }
                append('[').append(sectionName).append(']').append('\n')
                val section = ini[sectionName] ?: return@forEachIndexed
                orderedSectionKeys(sectionName, section).forEach { key ->
                    append(key).append('=').append(section[key]).append('\n')
                }
            }
        }.trim()
    }

    private fun orderedSectionKeys(sectionName: String, section: Profile.Section): List<String> {
        val keys = section.keys.toList()
        if (sectionName != SECTION_DEFAULT) {
            return keys
        }
        val suffix = listOf(KEY_CERT, KEY_SSL_WEB_CREDENTIAL).filter { section.containsKey(it) }
        val rest = keys.filter { it !in defaultAuthKeys }
        return rest + suffix
    }

    private fun newIni(): Ini {
        return Ini().apply {
            config.isStrictOperator = true
            config.isEscape = false
        }
    }

    private fun load(raw: String): Ini {
        return newIni().apply { load(StringReader(raw.trim())) }
    }
}
