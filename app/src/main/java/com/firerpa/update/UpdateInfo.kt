package com.firerpa.update

data class UpdateInfo(
    val version: String,
    val changelog: String?,
) {
    val hasChangelog: Boolean
        get() = !changelog.isNullOrBlank()
}

