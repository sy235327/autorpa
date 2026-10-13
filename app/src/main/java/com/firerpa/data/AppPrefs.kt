package com.firerpa.data

import android.content.Context
import android.content.SharedPreferences
import com.firerpa.runtime.DirectoryPrefixGenerator

class AppPrefs(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("com.firerpa.prefs", Context.MODE_PRIVATE)

    var autoStartEnabled: Boolean
        get() = prefs.getBoolean("auto_start_enabled", true)
        set(value) = prefs.edit().putBoolean("auto_start_enabled", value).apply()

    var installedVersion: String?
        get() = prefs.getString("installed_version", null)
        set(value) = prefs.edit().putString("installed_version", value).apply()

    var skippedVersion: String?
        get() = prefs.getString("skipped_version", null)
        set(value) = prefs.edit().putString("skipped_version", value).apply()

    var skippedAppVersion: String?
        get() = prefs.getString("skipped_app_version", null)
        set(value) = prefs.edit().putString("skipped_app_version", value).apply()

    var iniTemplate: String
        get() = prefs.getString("ini_template", defaultIniTemplate).orEmpty()
        set(value) = prefs.edit().putString("ini_template", value).apply()

    var languageTag: String
        get() = prefs.getString("language_tag", defaultLanguageTag).orEmpty()
        set(value) = prefs.edit().putString("language_tag", value).apply()

    // [fork] 业务网关 / 卡密（口令）配置
    var licenseServer: String
        get() = prefs.getString("license_server", "").orEmpty()
        set(value) = prefs.edit().putString("license_server", value).apply()

    var licensePort: String
        get() = prefs.getString("license_port", "8000").orEmpty()
        set(value) = prefs.edit().putString("license_port", value).apply()

    var licenseCard: String
        get() = prefs.getString("license_card", "").orEmpty()
        set(value) = prefs.edit().putString("license_card", value).apply()

    var licenseDeviceId: String
        get() = prefs.getString("license_device_id", "").orEmpty()
        set(value) = prefs.edit().putString("license_device_id", value).apply()

    var licenseToken: String
        get() = prefs.getString("license_token", "").orEmpty()
        set(value) = prefs.edit().putString("license_token", value).apply()

    var licenseActive: Boolean
        get() = prefs.getBoolean("license_active", false)
        set(value) = prefs.edit().putBoolean("license_active", value).apply()

    // [fork] 悬浮窗（脚本控制）开关
    var overlayEnabled: Boolean
        get() = prefs.getBoolean("overlay_enabled", false)
        set(value) = prefs.edit().putBoolean("overlay_enabled", value).apply()

    fun ensureDirectoryPrefix(): String {
        val existing = prefs.getString(KEY_DIRECTORY_PREFIX, null)
        if (!existing.isNullOrBlank() && PREFIX_PATTERN.matches(existing)) {
            return existing
        }

        val generated = DirectoryPrefixGenerator.generate()
        if (!PREFIX_PATTERN.matches(generated)) {
            prefs.edit().remove(KEY_DIRECTORY_PREFIX).commit()
            val retry = DirectoryPrefixGenerator.generate()
            check(PREFIX_PATTERN.matches(retry)) { "Failed to generate directory prefix" }
            prefs.edit().putString(KEY_DIRECTORY_PREFIX, retry).commit()
            return retry
        }
        prefs.edit().putString(KEY_DIRECTORY_PREFIX, generated).commit()
        return generated
    }

    companion object {
        const val KEY_DIRECTORY_PREFIX = "directory_prefix"
        val PREFIX_PATTERN = Regex("^[a-zA-Z0-9]{6}$")
        const val defaultLanguageTag = "en"

        private val defaultIniTemplate = """
            [DEFAULT]
            port=65000
        """.trimIndent()
    }
}
