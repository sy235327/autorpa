package com.firerpa.ui

import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import com.firerpa.databinding.PageLogBinding
import com.firerpa.service.LogExportFailure
import com.firerpa.service.LogExportResult
import com.firerpa.service.LogReadSlice
import com.firerpa.service.ServiceLogReader
import java.util.concurrent.ExecutorService
import kotlin.math.max

class ServiceLogController(
    private val logReader: ServiceLogReader,
    private val executorProvider: () -> ExecutorService?,
    private val unavailableText: String,
    private val emptyText: String,
    private val exportSuccessText: (String) -> String,
    private val exportEmptyText: String,
    private val exportFailedText: String,
    private val onMessage: (String) -> Unit,
) {
    companion object {
        private const val initialTailBytes = 32 * 1024
        private const val prependChunkBytes = 16 * 1024
        private const val appendPollBytes = 64 * 1024
        private const val maxDisplayedChars = 256 * 1024
        private const val pollIntervalMs = 1500L
        private const val topLoadThresholdPx = 96
    }

    private var binding: PageLogBinding? = null
    private var followTail = true
    private var startOffset = 0L
    private var endOffset = 0L
    private var loadingOlder = false
    private var exporting = false
    private var active = false
    private val handler = Handler(Looper.getMainLooper())
    private val pollRunnable = object : Runnable {
        override fun run() {
            if (!active) {
                return
            }
            pollAppend()
            handler.postDelayed(this, pollIntervalMs)
        }
    }

    fun bind(page: PageLogBinding) {
        binding = page
        NoWrapEditorHelper.configureLogText(
            page.logContentText,
            page.logScrollView,
            page.logHorizontalScrollView,
        )
        page.logScrollView.setOnScrollChangeListener { view, _, scrollY, _, _ ->
            if (view !is ScrollView) {
                return@setOnScrollChangeListener
            }
            val content = page.logContentText
            val atBottom = scrollY + view.height >= content.height - topLoadThresholdPx
            followTail = atBottom
            if (scrollY <= topLoadThresholdPx && !loadingOlder && startOffset > 0L) {
                loadOlder()
            }
        }
        page.logExportButton.setOnClickListener { exportLog() }
    }

    fun start() {
        if (active) {
            return
        }
        active = true
        followTail = true
        startOffset = 0L
        endOffset = 0L
        renderPlaceholder(emptyText)
        reloadInitial()
        handler.postDelayed(pollRunnable, pollIntervalMs)
    }

    fun stop() {
        active = false
        handler.removeCallbacks(pollRunnable)
    }

    private fun reloadInitial() {
        val executor = executorProvider() ?: return
        executor.execute {
            val slice = runCatching { logReader.readTail(initialTailBytes) }.getOrNull()
            handler.post {
                if (!active) {
                    return@post
                }
                if (slice == null) {
                    renderPlaceholder(unavailableText)
                    return@post
                }
                if (slice.text.isEmpty() && slice.fileSize <= 0L) {
                    renderPlaceholder(emptyText)
                    startOffset = 0L
                    endOffset = 0L
                    return@post
                }
                applyInitialSlice(slice)
            }
        }
    }

    private fun applyInitialSlice(slice: LogReadSlice) {
        val page = binding ?: return
        startOffset = slice.startOffset
        endOffset = slice.endOffset
        page.logContentText.text = slice.text
        scrollToBottom(page)
    }

    private fun pollAppend() {
        val executor = executorProvider() ?: return
        executor.execute {
            val fileSize = logReader.queryFileSize()
            if (fileSize < 0L) {
                return@execute
            }
            if (fileSize < endOffset) {
                handler.post {
                    if (active) {
                        reloadInitial()
                    }
                }
                return@execute
            }
            if (fileSize == endOffset) {
                return@execute
            }

            val slice = logReader.readFromOffset(endOffset + 1L, appendPollBytes) ?: return@execute
            if (slice.text.isEmpty()) {
                return@execute
            }
            handler.post {
                if (!active) {
                    return@post
                }
                appendSlice(slice)
            }
        }
    }

    private fun appendSlice(slice: LogReadSlice) {
        val page = binding ?: return
        val current = page.logContentText.text?.toString().orEmpty()
        val merged = current + slice.text
        page.logContentText.text = trimDisplayText(merged)
        endOffset = slice.endOffset
        if (followTail) {
            scrollToBottom(page)
        }
    }

    private fun loadOlder() {
        if (loadingOlder || startOffset <= 0L) {
            return
        }
        loadingOlder = true
        val beforeOffset = startOffset
        val executor = executorProvider() ?: run {
            loadingOlder = false
            return
        }
        executor.execute {
            val slice = logReader.readBefore(beforeOffset, prependChunkBytes)
            handler.post {
                loadingOlder = false
                if (!active || slice == null || slice.text.isEmpty()) {
                    return@post
                }
                prependSlice(slice)
            }
        }
    }

    private fun prependSlice(slice: LogReadSlice) {
        val page = binding ?: return
        val scrollView = page.logScrollView
        val content = page.logContentText
        val oldHeight = content.height
        val merged = slice.text + content.text?.toString().orEmpty()
        content.text = trimDisplayText(merged)
        startOffset = slice.startOffset
        scrollView.post {
            scrollView.scrollTo(0, max(0, scrollView.scrollY + (content.height - oldHeight)))
        }
    }

    private fun trimDisplayText(text: String): String {
        if (text.length <= maxDisplayedChars) {
            return text
        }
        val excess = text.length - maxDisplayedChars
        val cutAt = text.indexOf('\n', excess).let { if (it >= 0) it + 1 else excess }
        val trimmed = text.substring(cutAt)
        startOffset += text.substring(0, cutAt).toByteArray(Charsets.UTF_8).size.toLong()
        return trimmed
    }

    private fun renderPlaceholder(message: String) {
        val page = binding ?: return
        page.logContentText.text = message
    }

    private fun exportLog() {
        if (exporting) {
            return
        }
        val executor = executorProvider() ?: run {
            onMessage(unavailableText)
            return
        }
        exporting = true
        binding?.logExportButton?.isEnabled = false
        executor.execute {
            val result = runCatching { logReader.exportLog() }.getOrElse {
                LogExportResult(success = false, reason = LogExportFailure.EXPORT_FAILED)
            }
            handler.post {
                exporting = false
                binding?.logExportButton?.isEnabled = true
                if (!active) {
                    return@post
                }
                val message = when {
                    result.success && !result.destinationPath.isNullOrBlank() ->
                        exportSuccessText(result.destinationPath)
                    result.reason == LogExportFailure.EMPTY -> exportEmptyText
                    result.reason == LogExportFailure.NO_ACCESS -> unavailableText
                    else -> exportFailedText
                }
                onMessage(message)
            }
        }
    }

    private fun scrollToBottom(page: PageLogBinding) {
        page.logScrollView.post {
            page.logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
        }
    }
}
