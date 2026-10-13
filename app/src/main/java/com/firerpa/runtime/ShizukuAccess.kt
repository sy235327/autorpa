package com.firerpa.runtime

import android.content.pm.PackageManager
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream
import java.lang.reflect.Method

object ShizukuAccess {
    private const val shellBinary = "/system/bin/sh"
    private const val rootUid = 0

    @Volatile
    private var cachedEffectiveUid: Int? = null

    private val newProcessMethod: Method by lazy {
        Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java,
            Array<String>::class.java,
            String::class.java,
        ).apply {
            isAccessible = true
        }
    }

    fun isBinderReady(): Boolean {
        return runCatching {
            !Shizuku.isPreV11() && Shizuku.pingBinder()
        }.getOrDefault(false)
    }

    fun hasPermission(): Boolean {
        if (!isBinderReady()) {
            return false
        }

        return runCatching {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        }.getOrDefault(false)
    }

    fun canUseShell(): Boolean = hasPermission()

    fun refreshShellProbe(force: Boolean = false) {
        synchronized(this) {
            if (cachedEffectiveUid != null && !force) {
                return
            }
            cachedEffectiveUid = if (hasPermission()) probeEffectiveUid() else null
        }
    }

    fun isRootShell(): Boolean = synchronized(this) { cachedEffectiveUid == rootUid }

    fun invalidateShellProbe() {
        synchronized(this) {
            cachedEffectiveUid = null
        }
    }

    fun requestPermission(requestCode: Int) {
        Shizuku.requestPermission(requestCode)
    }

    fun addRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.addRequestPermissionResultListener(listener)
    }

    fun removeRequestPermissionResultListener(listener: Shizuku.OnRequestPermissionResultListener) {
        Shizuku.removeRequestPermissionResultListener(listener)
    }

    fun addBinderReceivedListenerSticky(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.addBinderReceivedListenerSticky(listener)
    }

    fun removeBinderReceivedListener(listener: Shizuku.OnBinderReceivedListener) {
        Shizuku.removeBinderReceivedListener(listener)
    }

    fun addBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.addBinderDeadListener(listener)
    }

    fun removeBinderDeadListener(listener: Shizuku.OnBinderDeadListener) {
        Shizuku.removeBinderDeadListener(listener)
    }

    fun runScript(script: String): CommandResult {
        if (!isBinderReady()) {
            return CommandResult(false, -1, "Shizuku is not running.")
        }
        if (!hasPermission()) {
            return CommandResult(false, -1, "Shizuku permission is required.")
        }

        return try {
            runWithRemoteProcess(script)
        } catch (t: Throwable) {
            CommandResult(false, -1, t.message ?: "Shizuku execution failed.")
        }
    }

    fun unbind() {
    }

    private fun probeEffectiveUid(): Int? {
        val result = runCatching { runWithRemoteProcess("id -u") }.getOrNull() ?: return null
        if (!result.success) {
            return null
        }
        return result.output.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
            ?.toIntOrNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun runWithRemoteProcess(script: String): CommandResult {
        val process = newProcessMethod.invoke(
            null,
            arrayOf(shellBinary),
            null,
            null,
        ) as Process

        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()

        val outThread = Thread {
            runCatching { process.inputStream.copyTo(stdout) }
        }
        val errThread = Thread {
            runCatching { process.errorStream.copyTo(stderr) }
        }

        outThread.start()
        errThread.start()

        runCatching {
            process.outputStream.bufferedWriter().use { writer ->
                writer.appendLine(script)
                writer.appendLine("exit")
                writer.flush()
            }
        }

        val exitCode = process.waitFor()
        outThread.join()
        errThread.join()

        val output = buildString {
            val outText = stdout.toString().trim()
            val errText = stderr.toString().trim()
            if (outText.isNotEmpty()) {
                append(outText)
            }
            if (errText.isNotEmpty()) {
                if (isNotEmpty()) append('\n')
                append(errText)
            }
        }

        return CommandResult(exitCode == 0, exitCode, output)
    }
}
