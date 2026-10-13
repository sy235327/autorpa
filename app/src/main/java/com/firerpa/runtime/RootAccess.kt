package com.firerpa.runtime

import java.io.ByteArrayOutputStream

object RootAccess {
    private val candidates = listOf(
        "su",
        "/system/bin/su",
        "/system/xbin/su",
        "/sbin/su",
    )
    private const val probeTimeoutMillis = 60_000L
    private const val pollIntervalMillis = 50L

    @Volatile
    private var cachedSuBinary: String? = null

    fun probeOnce() {
        refreshProbe(force = false)
    }

    fun refreshProbe(force: Boolean = false) {
        synchronized(this) {
            if (cachedSuBinary != null && !force) {
                return
            }
            cachedSuBinary = probeSuBinary()
        }
    }

    fun isAvailable(): Boolean = cachedSuBinary != null

    fun runScript(script: String): CommandResult {
        val suBinary = cachedSuBinary
            ?: return CommandResult(false, -1, "su binary not found")

        return runShellScript(suBinary, script)
    }

    private fun probeSuBinary(): String? {
        for (candidate in candidates) {
            if (runCatching { probeCandidate(candidate) }.getOrDefault(false)) {
                return candidate
            }
        }
        return null
    }

    private fun probeCandidate(candidate: String): Boolean {
        val process = ProcessBuilder(candidate).redirectErrorStream(true).start()
        val stdout = ByteArrayOutputStream()
        val outThread = Thread {
            runCatching { process.inputStream.copyTo(stdout) }
        }
        outThread.start()

        runCatching {
            process.outputStream.bufferedWriter().use { writer ->
                writer.appendLine("id -u")
                writer.appendLine("exit")
                writer.flush()
            }
        }

        val completed = waitForExit(process, probeTimeoutMillis)
        outThread.join(250)

        if (!completed || process.exitValue() != 0) {
            return false
        }

        val uid = stdout.toString()
            .lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotEmpty() }
        return uid == "0"
    }

    internal fun runShellScript(binary: String, script: String): CommandResult {
        return try {
            val process = ProcessBuilder(binary).redirectErrorStream(true).start()
            process.outputStream.bufferedWriter().use { writer ->
                writer.appendLine(script)
                writer.appendLine("exit")
                writer.flush()
            }

            val output = ByteArrayOutputStream()
            process.inputStream.copyTo(output)
            val exitCode = process.waitFor()
            CommandResult(exitCode == 0, exitCode, output.toString().trim())
        } catch (t: Throwable) {
            CommandResult(false, -1, t.message ?: t.javaClass.simpleName)
        }
    }

    private fun waitForExit(process: Process, timeoutMillis: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (hasExited(process)) {
                return true
            }
            Thread.sleep(pollIntervalMillis)
        }

        if (!hasExited(process)) {
            process.destroy()
        }
        return hasExited(process)
    }

    private fun hasExited(process: Process): Boolean {
        return try {
            process.exitValue()
            true
        } catch (_: IllegalThreadStateException) {
            false
        }
    }
}
