package com.firerpa.runtime

import android.content.Context
import com.firerpa.data.AppPrefs

object NativeServicePaths {
    const val serviceFolderName = "server"
    const val configFileName = "properties"
    private const val launchScriptName = "bin/launch.sh"

    private const val rootBase = "/data"
    private const val shellBase = "/data/local/tmp"

    val currentAbi: String
        get() = android.os.Build.SUPPORTED_ABIS.firstOrNull().orEmpty().ifBlank { "arm64-v8a" }

    fun installRoot(identity: ExecutionIdentity, prefix: String): String {
        return "${baseRoot(identity)}/${requireValidPrefix(prefix)}"
    }

    fun serverRoot(identity: ExecutionIdentity, prefix: String): String {
        return "${installRoot(identity, prefix)}/$serviceFolderName"
    }

    fun launchPath(identity: ExecutionIdentity, prefix: String): String {
        return "${serverRoot(identity, prefix)}/$launchScriptName"
    }

    fun usrRoot(identity: ExecutionIdentity, prefix: String): String {
        return "${installRoot(identity, prefix)}/usr"
    }

    fun configDir(identity: ExecutionIdentity, prefix: String): String {
        return "${usrRoot(identity, prefix)}/.local"
    }

    fun configPath(identity: ExecutionIdentity, prefix: String): String {
        return "${configDir(identity, prefix)}/$configFileName"
    }

    fun environmentPath(identity: ExecutionIdentity, prefix: String): String {
        return "${configDir(identity, prefix)}/run/environment"
    }

    fun pidPath(identity: ExecutionIdentity, prefix: String): String {
        return "${usrRoot(identity, prefix)}/lamda.pid"
    }

    fun logPath(identity: ExecutionIdentity, prefix: String): String {
        return "${installRoot(identity, prefix)}/server.log"
    }

    fun ensurePrefix(context: Context): String {
        return AppPrefs(context.applicationContext).ensureDirectoryPrefix()
    }

    private fun baseRoot(identity: ExecutionIdentity): String = when (identity) {
        ExecutionIdentity.ROOT, ExecutionIdentity.SHIZUKU_ROOT -> rootBase
        ExecutionIdentity.SHIZUKU_SHELL, ExecutionIdentity.NONE -> shellBase
    }

    private fun requireValidPrefix(prefix: String): String {
        require(AppPrefs.PREFIX_PATTERN.matches(prefix)) {
            "Invalid directory prefix: '$prefix'"
        }
        return prefix
    }
}
