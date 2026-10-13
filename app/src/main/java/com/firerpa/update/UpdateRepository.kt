package com.firerpa.update

import org.json.JSONObject

class UpdateRepository {
    fun fetchLatest(): UpdateInfo {
        return runCatching {
            val body = ReleaseHttp.getText(listOf(LATEST_VERSION_URL), "application/json")
            parseCdnVersion(body)
        }.getOrElse {
            fetchLatestFromGithub()
        }
    }

    companion object {
        const val LATEST_VERSION_URL =
            "https://assets.firerpa.com/release/latest/version.json"
        const val GITHUB_OWNER = "firerpa"
        const val GITHUB_REPO = "lamda"
        private const val GITHUB_API_BASE_URL = "https://api.github.com/repos"
        private const val GITHUB_RELEASE_BASE_URL = "https://github.com"
        private const val GITHUB_TAG_PREFIX = "v"

        private val githubRepositoryPath: String
            get() = "$GITHUB_OWNER/$GITHUB_REPO"

        private val githubLatestReleaseApiUrl: String
            get() = "$GITHUB_API_BASE_URL/$githubRepositoryPath/releases/latest"

        fun buildDownloadUrls(version: String, abi: String): List<String> {
            val assetName = "lamda-server-$abi.tar.gz"
            val tag = "$GITHUB_TAG_PREFIX$version"
            return listOf(
                "https://assets.firerpa.com/release/$version/$assetName",
                "$GITHUB_RELEASE_BASE_URL/$githubRepositoryPath/releases/download/$tag/$assetName",
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
