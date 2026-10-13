package com.firerpa.service

import android.content.Context
import com.firerpa.runtime.CommandResult
import com.firerpa.runtime.ExecutionIdentity
import com.firerpa.runtime.NativeServicePaths
import com.firerpa.runtime.RootAccess
import com.firerpa.runtime.ShizukuAccess
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ServiceLogReader(
    private val appContext: Context,
    private val directoryPrefix: () -> String,
    private val resolveIdentity: () -> ExecutionIdentity,
) {
    fun logPath(): String? {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return null
        }
        return NativeServicePaths.logPath(identity, directoryPrefix())
    }

    fun queryFileSize(): Long {
        val path = logPath() ?: return -1L
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return -1L
        }

        val script = buildString {
            appendLine("if [ -f '$path' ]; then")
            appendLine("  wc -c < '$path' | tr -d '[:space:]'")
            appendLine("else")
            appendLine("  echo -1")
            appendLine("fi")
        }
        val output = execute(identity, script).output.trim()
        return output.toLongOrNull() ?: -1L
    }

    fun readTail(maxBytes: Int): LogReadSlice? {
        val path = logPath() ?: return null
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return null
        }

        val size = queryFileSize()
        if (size <= 0L) {
            return LogReadSlice(text = "", startOffset = 0L, endOffset = 0L, fileSize = size.coerceAtLeast(0L))
        }

        val readBytes = minOf(size, maxBytes.toLong()).toInt()
        val script = "tail -c $readBytes '$path'"
        val text = execute(identity, script).output
        val byteLength = text.toByteArray(Charsets.UTF_8).size.toLong()
        val startOffset = (size - byteLength).coerceAtLeast(0L)
        return LogReadSlice(
            text = text,
            startOffset = startOffset,
            endOffset = startOffset + byteLength,
            fileSize = size,
        )
    }

    fun readFromOffset(fromOffset: Long, maxBytes: Int): LogReadSlice? {
        val path = logPath() ?: return null
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return null
        }

        val size = queryFileSize()
        if (size <= fromOffset) {
            return LogReadSlice(text = "", startOffset = fromOffset, endOffset = fromOffset, fileSize = size)
        }

        val script = "tail -c +${fromOffset.coerceAtLeast(1L)} '$path' | head -c $maxBytes"
        val text = execute(identity, script).output
        val endOffset = fromOffset + text.toByteArray(Charsets.UTF_8).size.toLong()
        return LogReadSlice(
            text = text,
            startOffset = fromOffset,
            endOffset = endOffset.coerceAtMost(size),
            fileSize = size,
        )
    }

    fun readBefore(beforeOffset: Long, maxBytes: Int): LogReadSlice? {
        if (beforeOffset <= 0L) {
            return LogReadSlice(text = "", startOffset = 0L, endOffset = 0L, fileSize = queryFileSize())
        }

        val path = logPath() ?: return null
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return null
        }

        val size = queryFileSize()
        val readBytes = minOf(beforeOffset, maxBytes.toLong()).toInt()
        val script = "head -c $beforeOffset '$path' | tail -c $readBytes"
        val text = execute(identity, script).output
        val startOffset = (beforeOffset - text.toByteArray(Charsets.UTF_8).size.toLong()).coerceAtLeast(0L)
        return LogReadSlice(
            text = text,
            startOffset = startOffset,
            endOffset = beforeOffset,
            fileSize = size,
        )
    }

    fun exportLog(): LogExportResult {
        val sourcePath = logPath()
            ?: return LogExportResult(success = false, reason = LogExportFailure.NO_ACCESS)
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return LogExportResult(success = false, reason = LogExportFailure.NO_ACCESS)
        }

        val fileSize = queryFileSize()
        if (fileSize <= 0L) {
            return LogExportResult(success = false, reason = LogExportFailure.EMPTY)
        }

        val destinationPath = buildExportPath()
        val script = buildString {
            appendLine("src='$sourcePath'")
            appendLine("dest='$destinationPath'")
            appendLine("if [ ! -f \"\$src\" ]; then")
            appendLine("  echo __ERR__:source_missing")
            appendLine("  exit 1")
            appendLine("fi")
            appendLine("mkdir -p \"\$(dirname \"\$dest\")\"")
            appendLine("if cp \"\$src\" \"\$dest\" 2>/dev/null; then")
            appendLine("  echo \"\$dest\"")
            appendLine("elif cat \"\$src\" > \"\$dest\" 2>/dev/null; then")
            appendLine("  echo \"\$dest\"")
            appendLine("else")
            appendLine("  echo __ERR__:export_failed")
            appendLine("  exit 1")
            appendLine("fi")
        }
        val result = execute(identity, script)
        val output = result.output.trim()
        if (!result.success || output.startsWith("__ERR__:")) {
            return LogExportResult(success = false, reason = LogExportFailure.EXPORT_FAILED)
        }
        return LogExportResult(success = true, destinationPath = output.lines().lastOrNull()?.trim().orEmpty())
    }

    private fun buildExportPath(): String {
        val timestamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        return "/sdcard/Download/firerpa-server-$timestamp.log"
    }

    private fun execute(identity: ExecutionIdentity, script: String): CommandResult {
        return when (identity) {
            ExecutionIdentity.ROOT -> RootAccess.runScript(script)
            ExecutionIdentity.SHIZUKU_ROOT, ExecutionIdentity.SHIZUKU_SHELL -> ShizukuAccess.runScript(script)
            ExecutionIdentity.NONE -> CommandResult(false, -1, "")
        }
    }
}

data class LogReadSlice(
    val text: String,
    val startOffset: Long,
    val endOffset: Long,
    val fileSize: Long,
)

data class LogExportResult(
    val success: Boolean,
    val destinationPath: String? = null,
    val reason: LogExportFailure? = null,
)

enum class LogExportFailure {
    NO_ACCESS,
    EMPTY,
    EXPORT_FAILED,
}
