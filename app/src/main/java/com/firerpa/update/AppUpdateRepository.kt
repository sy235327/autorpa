package com.firerpa.update

import org.json.JSONObject

class AppUpdateRepository {
    fun fetchLatest(): UpdateInfo {
        return runCatching {
            val body = ReleaseHttp.getText(listOf(APP_VERSION_URL), "application/json")
            parseCdnVersion(body)
        }.getOrElse {
            fetchLatestFromGithub()
        }
    }

    companion object {
        // [fork] 禁用官方 app 自更新（避免二开包被官方版本覆盖）；lamda 服务端更新保持官方源不变
        const val APP_VERSION_URL = "https://updates.invalid/app/version.json"
        const val GITHUB_OWNER = "invalid-fork"
        const val GITHUB_REPO = "app"
        private const val GITHUB_API_BASE_URL = "https://api.github.com/repos"
        private const val GITHUB_RELEASE_BASE_URL = "https://github.com"
        private const val GITHUB_TAG_PREFIX = "v"

        private val githubRepositoryPath: String
            get() = "$GITHUB_OWNER/$GITHUB_REPO"

        private val githubLatestReleaseApiUrl: String
            get() = "$GITHUB_API_BASE_URL/$githubRepositoryPath/releases/latest"

        fun buildDownloadUrls(version: String): List<String> {
            val assetName = "$version.zip"
            val tag = "$GITHUB_TAG_PREFIX$version"
            return listOf(
                "https://assets.firerpa.com/app/$assetName",
                "$GITHUB_RELEASE_BASE_URL/$githubRepositoryPath/releases/download/$tag/firerpa.apk",
            )
        }
    }

    private fun fetchLatestFromGithub(): UpdateInfo {
        val body = ReleaseHttp.getText(listOf(githubLatestReleaseApiUrl), "application/vnd.github+json")
        val json = JSONObject(body)
        val tagName = json.getString("tag_name")
        return UpdateInfo(
            version = tagName.removePrefix(GITHUB_TAG_PREFIX),
            changelog = json.optString("body").takeIf { it.isNotBlank() },
        )
    }

    private fun parseCdnVersion(body: String): UpdateInfo {
        val json = JSONObject(body)
        return UpdateInfo(
            version = json.getString("version"),
            changelog = json.optString("changelog").takeIf { it.isNotBlank() },
        )
    }
}
