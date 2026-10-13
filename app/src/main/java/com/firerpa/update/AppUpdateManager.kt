package com.firerpa.update

import android.content.Context
import com.firerpa.BuildConfig
import com.firerpa.data.AppPrefs
import com.firerpa.service.DownloadProgress
import java.io.File

class AppUpdateManager(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = AppPrefs(appContext)

    fun currentVersion(): String = BuildConfig.VERSION_NAME

    fun fetchLatest(): UpdateInfo = AppUpdateRepository().fetchLatest()

    fun shouldPromptUpdate(updateInfo: UpdateInfo): Boolean {
        return currentVersion() != updateInfo.version &&
            prefs.skippedAppVersion != updateInfo.version
    }

    fun markVersionSkipped(version: String) {
        prefs.skippedAppVersion = version
    }

    fun downloadApk(
        updateInfo: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): File {
        val updatesDir = File(appContext.getExternalFilesDir(null), "updates").apply { mkdirs() }
        val zipFile = File(updatesDir, "firerpa-${updateInfo.version}.zip")
        val apkFile = File(updatesDir, "firerpa-${updateInfo.version}.apk")

        if (apkFile.exists()) {
            apkFile.delete()
        }
        if (zipFile.exists()) {
            zipFile.delete()
        }

        val urls = AppUpdateRepository.buildDownloadUrls(updateInfo.version)
        downloadFile(urls, zipFile, onProgress)

        if (!zipFile.renameTo(apkFile)) {
            zipFile.copyTo(apkFile, overwrite = true)
            zipFile.delete()
        }

        if (!apkFile.exists() || apkFile.length() <= 0L) {
            throw IllegalStateException("Downloaded package is empty")
        }

        return apkFile
    }

    private fun downloadFile(
        urls: List<String>,
        outputFile: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        ReleaseHttp.downloadToFile(urls, outputFile, onProgress)
    }
}
