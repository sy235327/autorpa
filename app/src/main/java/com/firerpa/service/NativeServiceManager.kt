package com.firerpa.service

import android.content.Context
import android.util.Base64
import com.firerpa.R
import com.firerpa.data.AppPrefs
import com.firerpa.runtime.CommandResult
import com.firerpa.runtime.EnvironmentFileParser
import com.firerpa.runtime.ExecutionIdentity
import com.firerpa.runtime.DeviceNetworkAddresses
import com.firerpa.runtime.ServiceIniDocument
import com.firerpa.runtime.NativeServicePaths
import com.firerpa.runtime.RootAccess
import com.firerpa.runtime.ShizukuAccess
import com.firerpa.security.FirerpaCertificateManager
import com.firerpa.update.ReleaseHttp
import com.firerpa.update.UpdateInfo
import com.firerpa.update.UpdateRepository
import java.io.File

class NativeServiceManager(context: Context) {
    companion object {
        private const val startupTimeoutSeconds = 60
        private const val shutdownTimeoutSeconds = 60
    }

    private val appContext = context.applicationContext
    private val prefs = AppPrefs(appContext)
    val serviceLogReader = ServiceLogReader(appContext, ::directoryPrefix, ::resolveIdentity)

    private fun directoryPrefix(): String = prefs.ensureDirectoryPrefix()

    fun resolveIdentity(): ExecutionIdentity {
        return when {
            RootAccess.isAvailable() -> ExecutionIdentity.ROOT
            ShizukuAccess.canUseShell() -> {
                ShizukuAccess.refreshShellProbe()
                if (ShizukuAccess.isRootShell()) {
                    ExecutionIdentity.SHIZUKU_ROOT
                } else {
                    ExecutionIdentity.SHIZUKU_SHELL
                }
            }
            else -> ExecutionIdentity.NONE
        }
    }

    fun buildEnvironmentSummary(): EnvironmentSummary {
        val identity = resolveIdentity()
        val prefix = directoryPrefix()
        return EnvironmentSummary(
            identity = identity,
            installRoot = NativeServicePaths.installRoot(identity, prefix),
            launchPath = NativeServicePaths.launchPath(identity, prefix),
            configPath = NativeServicePaths.configPath(identity, prefix),
            abi = NativeServicePaths.currentAbi,
            installedVersion = prefs.installedVersion,
        )
    }

    fun buildAccessSummary(): AccessSummary {
        return AccessSummary(
            rootAvailable = RootAccess.isAvailable(),
            shizukuState = resolveShizukuAccessState(),
        )
    }

    private fun resolveShizukuAccessState(): ShizukuAccessState {
        if (!ShizukuAccess.isBinderReady()) {
            return ShizukuAccessState.NOT_CONNECTED
        }
        if (!ShizukuAccess.hasPermission()) {
            return ShizukuAccessState.UNAUTHORIZED
        }
        return ShizukuAccessState.READY
    }

    fun queryServiceRuntime(): ServiceRuntimeStatus {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ServiceRuntimeStatus(ServiceRuntimeState.UNAVAILABLE)
        }

        val prefix = directoryPrefix()
        val pidPath = NativeServicePaths.pidPath(identity, prefix)
        val script = buildString {
            appendLine("pid_file='$pidPath'")
            appendLine("if [ ! -f \"\$pid_file\" ]; then")
            appendLine("  echo 'stopped'")
            appendLine("  exit 3")
            appendLine("fi")
            appendLine("pid=$(tr -d '[:space:]' < \"\$pid_file\" 2>/dev/null)")
            appendLine("if [ -z \"\$pid\" ]; then")
            appendLine("  echo 'stopped'")
            appendLine("  exit 3")
            appendLine("fi")
            appendLine("cmdline=$(tr '\\000' ' ' < \"/proc/\$pid/cmdline\" 2>/dev/null)")
            appendLine("comm=$(cat \"/proc/\$pid/comm\" 2>/dev/null)")
            appendLine("if printf '%s\\n%s\\n' \"\$cmdline\" \"\$comm\" | grep -qi 'lamda'; then")
            appendLine("  echo \"running:\$pid\"")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("echo \"stopped:\$pid\"")
            appendLine("exit 4")
        }

        val result = execute(identity, script)
        val output = result.output.trim()
        return when {
            output.contains("running:") -> {
                ServiceRuntimeStatus(
                    state = ServiceRuntimeState.RUNNING,
                    pid = output.substringAfter("running:").lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { null },
                    detail = output,
                )
            }

            output.contains("stopped") -> {
                ServiceRuntimeStatus(
                    state = ServiceRuntimeState.STOPPED,
                    pid = output.substringAfter("stopped:", "").lineSequence().firstOrNull()?.trim().orEmpty().ifBlank { null },
                    detail = output,
                )
            }

            else -> {
                ServiceRuntimeStatus(
                    state = ServiceRuntimeState.UNKNOWN,
                    detail = output.ifBlank { appContext.getString(R.string.runtime_probe_failed) },
                )
            }
        }
    }

    fun loadServiceConsoleInfo(): ServiceConsoleInfo? {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return null
        }

        val path = NativeServicePaths.environmentPath(identity, directoryPrefix())
        val missingMarker = "__FIRERPA_ENVIRONMENT_MISSING__"
        val script = buildString {
            appendLine("if [ -f '$path' ]; then")
            appendLine("  cat '$path'")
            appendLine("else")
            appendLine("  echo '$missingMarker'")
            appendLine("fi")
        }

        val result = execute(identity, script)
        val output = result.output.trim()
        if (!result.success || output.isBlank() || output.contains(missingMarker)) {
            return null
        }

        val variables = EnvironmentFileParser.parse(output)
        val port = variables["PORT"]?.trim().orEmpty()
        if (port.isEmpty()) {
            return null
        }

        val credential = variables["CREDENTIAL"]?.trim().orEmpty()
        val scheme = if (credential.isNotEmpty()) "https" else "http"
        val (internalHosts, externalHosts) = resolveLocalIPv4Addresses(identity)
        val hosts = (internalHosts + externalHosts).distinct()
        if (hosts.isEmpty()) {
            return null
        }

        return ServiceConsoleInfo(
            backendUrls = hosts.map { host -> "$scheme://$host:$port" },
            credential = credential.takeIf { it.isNotEmpty() },
        )
    }

    private fun resolveLocalIPv4Addresses(identity: ExecutionIdentity): Pair<List<String>, List<String>> {
        val fromFramework = DeviceNetworkAddresses.localIPv4Addresses(appContext)
        if (fromFramework.first.isNotEmpty() || fromFramework.second.isNotEmpty()) {
            return fromFramework
        }
        return resolveLocalIPv4AddressesViaShell(identity)
    }

    private fun resolveLocalIPv4AddressesViaShell(identity: ExecutionIdentity): Pair<List<String>, List<String>> {
        val script = buildString {
            appendLine("if command -v ip >/dev/null 2>&1; then")
            appendLine("  ip -4 -o addr show 2>/dev/null | awk '{print $4}' | cut -d/ -f1")
            appendLine("elif command -v ifconfig >/dev/null 2>&1; then")
            appendLine("  ifconfig 2>/dev/null | awk '/inet / {print $2}' | sed 's/addr://'")
            appendLine("else")
            appendLine("  echo ''")
            appendLine("fi")
        }
        val result = execute(identity, script)
        if (!result.success || result.output.isBlank()) {
            return emptyList<String>() to emptyList()
        }
        return DeviceNetworkAddresses.parseAddressLines(result.output.lineSequence())
    }

    fun startService(): ActionResult {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ActionResult.failure(appContext.getString(R.string.no_execution_identity))
        }

        val script = buildStartLaunchScript(identity, waitForRunning = false)
        return ActionResult.from(
            execute(identity, script),
            appContext.getString(R.string.action_service_started),
            appContext.getString(R.string.action_service_start_failed),
        )
    }

    fun startServiceAndWait(): ActionResult {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ActionResult.failure(appContext.getString(R.string.no_execution_identity))
        }

        val script = buildStartLaunchScript(identity, waitForRunning = true)
        val result = execute(identity, script)
        return if (result.success) {
            ActionResult(true, appContext.getString(R.string.action_service_started), result.output)
        } else {
            ActionResult(false, appContext.getString(R.string.action_service_start_timed_out), result.output)
        }
    }

    fun needsServiceInstall(): Boolean {
        if (prefs.installedVersion.isNullOrBlank()) {
            return true
        }

        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return true
        }

        val prefix = directoryPrefix()
        val launchPath = NativeServicePaths.launchPath(identity, prefix)
        val script = "if [ -f '$launchPath' ]; then echo 'installed'; else echo 'missing'; fi"
        val result = execute(identity, script)
        return !result.success || !result.output.contains("installed")
    }

    fun stopService(): ActionResult {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ActionResult.failure(appContext.getString(R.string.no_execution_identity))
        }

        val prefix = directoryPrefix()
        val pidPath = NativeServicePaths.pidPath(identity, prefix)
        val script = buildString {
            appendLine("pid_file='$pidPath'")
            appendLine("if [ ! -f \"${'$'}pid_file\" ]; then")
            appendLine("  echo 'already_stopped'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("pid=${'$'}(tr -d '[:space:]' < \"${'$'}pid_file\" 2>/dev/null)")
            appendLine("if [ -z \"${'$'}pid\" ]; then")
            appendLine("  echo 'already_stopped'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("cmdline=${'$'}(tr '\\000' ' ' < \"/proc/${'$'}pid/cmdline\" 2>/dev/null)")
            appendLine("comm=${'$'}(cat \"/proc/${'$'}pid/comm\" 2>/dev/null)")
            appendLine("if ! printf '%s\\n%s\\n' \"${'$'}cmdline\" \"${'$'}comm\" | grep -qi 'lamda'; then")
            appendLine("  echo 'already_stopped'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("kill -SIGUSR2 \"${'$'}pid\" 2>/dev/null || { echo 'signal_failed'; exit 6; }")
            appendLine("count=0")
            appendLine("while [ \"${'$'}count\" -lt $shutdownTimeoutSeconds ]; do")
            appendLine("  cmdline=${'$'}(tr '\\000' ' ' < \"/proc/${'$'}pid/cmdline\" 2>/dev/null)")
            appendLine("  comm=${'$'}(cat \"/proc/${'$'}pid/comm\" 2>/dev/null)")
            appendLine("  if [ -z \"${'$'}cmdline\" ] && [ -z \"${'$'}comm\" ]; then")
            appendLine("    echo 'stopped'")
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("  if ! printf '%s\\n%s\\n' \"${'$'}cmdline\" \"${'$'}comm\" | grep -qi 'lamda'; then")
            appendLine("    echo 'stopped'")
            appendLine("    exit 0")
            appendLine("  fi")
            appendLine("  sleep 1")
            appendLine("  count=${'$'}((count + 1))")
            appendLine("done")
            appendLine("echo 'timeout'")
            appendLine("exit 7")
        }

        val result = execute(identity, script)
        return when {
            result.success && result.output.contains("already_stopped") -> {
                ActionResult(true, appContext.getString(R.string.action_service_already_stopped), result.output)
            }

            result.success -> {
                ActionResult(true, appContext.getString(R.string.action_service_stopped), result.output)
            }

            result.output.contains("signal_failed") -> {
                ActionResult(false, appContext.getString(R.string.action_service_stop_signal_failed), result.output)
            }

            else -> {
                ActionResult(false, appContext.getString(R.string.action_service_stop_timed_out), result.output)
            }
        }
    }

    fun applyIni(content: String): ActionResult {
        val validation = ServiceIniDocument.validate(appContext, content)
        if (!validation.isValid) {
            return ActionResult.failure(validation.message)
        }

        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ActionResult.failure(appContext.getString(R.string.no_execution_identity))
        }

        val prefix = directoryPrefix()
        val targetDir = NativeServicePaths.configDir(identity, prefix)
        val targetPath = NativeServicePaths.configPath(identity, prefix)
        val delimiter = "DEVICEFARM_EOF"
        val script = buildString {
            appendLine("mkdir -p '$targetDir'")
            appendLine("cat > '$targetPath' <<'$delimiter'")
            appendLine(content.trim())
            appendLine(delimiter)
        }

        val result = execute(identity, script)
        if (result.success) {
            prefs.iniTemplate = content
        }
        return ActionResult.from(
            result,
            appContext.getString(R.string.action_ini_written),
            appContext.getString(R.string.action_ini_write_failed),
        )
    }

    fun applyIniAndRestart(content: String): ActionResult {
        val applyResult = applyIni(content)
        if (!applyResult.success) {
            return applyResult
        }

        val stopResult = stopService()
        if (!stopResult.success &&
            stopResult.message != appContext.getString(R.string.action_service_already_stopped)
        ) {
            return stopResult
        }

        return startServiceAndWait()
    }

    fun fetchLatestUpdate(): UpdateInfo {
        return UpdateRepository().fetchLatest()
    }

    fun shouldForceInstall(): Boolean {
        return prefs.installedVersion.isNullOrBlank()
    }

    fun shouldPromptUpdate(updateInfo: UpdateInfo): Boolean {
        val installedVersion = prefs.installedVersion
        return installedVersion != null &&
            installedVersion != updateInfo.version &&
            prefs.skippedVersion != updateInfo.version
    }

    fun markVersionSkipped(version: String) {
        prefs.skippedVersion = version
    }

    fun installOrUpdate(
        updateInfo: UpdateInfo,
        onProgress: (DownloadProgress) -> Unit,
    ): ActionResult {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ActionResult.failure(appContext.getString(R.string.no_execution_identity))
        }

        val prefix = directoryPrefix()
        val targetRoot = NativeServicePaths.installRoot(identity, prefix)
        val serverDir = NativeServicePaths.serverRoot(identity, prefix)
        val archiveFile = sharedArchiveFile(updateInfo)

        archiveFile.parentFile?.mkdirs()
        downloadArchive(updateInfo, archiveFile, onProgress)

        val script = buildString {
            append("rm -rf '$serverDir'")
            append(" && mkdir -p '$targetRoot'")
            append(" && tar -xzf '${archiveFile.absolutePath}' -C '$targetRoot'")
            append(" && rm -f '${archiveFile.absolutePath}'")
        }
        val result = execute(identity, script)
        return if (result.success) {
            prefs.installedVersion = updateInfo.version
            prefs.skippedVersion = null
            ActionResult(true, appContext.getString(R.string.action_installed_version, updateInfo.version), result.output)
        } else {
            ActionResult(false, appContext.getString(R.string.action_install_failed), result.output)
        }
    }

    fun autoStartIfEnabled() {
        if (!prefs.autoStartEnabled) {
            return
        }

        startService()
    }

    fun uninstallService(): ActionResult {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return ActionResult.failure(appContext.getString(R.string.no_execution_identity))
        }

        val prefix = directoryPrefix()
        val installRoot = NativeServicePaths.installRoot(identity, prefix)
        val script = "rm -rf '$installRoot'"

        val result = execute(identity, script)
        return if (result.success) {
            prefs.installedVersion = null
            prefs.skippedVersion = null
            ActionResult(true, appContext.getString(R.string.action_service_removed), result.output)
        } else {
            ActionResult(false, appContext.getString(R.string.action_uninstall_failed), result.output)
        }
    }

    fun loadIniFromDevice(): String {
        val identity = resolveIdentity()
        if (identity == ExecutionIdentity.NONE) {
            return prefs.iniTemplate
        }

        val targetPath = NativeServicePaths.configPath(identity, directoryPrefix())
        val missingMarker = "__DEVICEFARM_CONFIG_MISSING__"
        val script = buildString {
            appendLine("if [ -f '$targetPath' ]; then")
            appendLine("  cat '$targetPath'")
            appendLine("else")
            appendLine("  echo '$missingMarker'")
            appendLine("fi")
        }

        val result = execute(identity, script)
        val output = result.output.trim()
        if (output.isBlank() || output == missingMarker) {
            return prefs.iniTemplate
        }

        prefs.iniTemplate = output
        return output
    }

    fun defaultAccessCredential(): String {
        return FirerpaCertificateManager.defaultSslWebCredential(appContext)
    }

    fun clientPemBase64OneLine(): String {
        val pemBytes = FirerpaCertificateManager.readClientPemText(appContext).toByteArray(Charsets.US_ASCII)
        return Base64.encodeToString(pemBytes, Base64.NO_WRAP)
    }

    fun localIniTemplate(): String = prefs.iniTemplate

    fun setAutoStart(enabled: Boolean) {
        prefs.autoStartEnabled = enabled
    }

    fun isAutoStartEnabled(): Boolean = prefs.autoStartEnabled

    private fun buildStartLaunchScript(identity: ExecutionIdentity, waitForRunning: Boolean): String {
        val prefix = directoryPrefix()
        val installRoot = NativeServicePaths.installRoot(identity, prefix)
        val usrDir = NativeServicePaths.usrRoot(identity, prefix)
        val configDir = NativeServicePaths.configDir(identity, prefix)
        val launchPath = NativeServicePaths.launchPath(identity, prefix)
        val pidPath = NativeServicePaths.pidPath(identity, prefix)
        val logPath = NativeServicePaths.logPath(identity, prefix)
        return buildString {
            append("mkdir -p '$installRoot' '$usrDir' '$configDir' && ")
            append("export USRDIR='$usrDir' && sh '$launchPath' --logfile='$logPath' >/dev/null 2>&1 &")
            if (!waitForRunning) {
                return@buildString
            }
            appendLine()
            appendLine("count=0")
            appendLine("while [ \"${'$'}count\" -lt $startupTimeoutSeconds ]; do")
            appendLine("  if [ -f '$pidPath' ]; then")
            appendLine("    pid=${'$'}(tr -d '[:space:]' < '$pidPath' 2>/dev/null)")
            appendLine("    if [ -n \"${'$'}pid\" ]; then")
            appendLine("      cmdline=${'$'}(tr '\\000' ' ' < \"/proc/${'$'}pid/cmdline\" 2>/dev/null)")
            appendLine("      comm=${'$'}(cat \"/proc/${'$'}pid/comm\" 2>/dev/null)")
            appendLine("      if printf '%s\\n%s\\n' \"${'$'}cmdline\" \"${'$'}comm\" | grep -qi 'lamda'; then")
            appendLine("        echo \"running:${'$'}pid\"")
            appendLine("        exit 0")
            appendLine("      fi")
            appendLine("    fi")
            appendLine("  fi")
            appendLine("  sleep 1")
            appendLine("  count=${'$'}((count + 1))")
            appendLine("done")
            appendLine("echo 'timeout'")
            appendLine("exit 5")
        }
    }

    private fun execute(identity: ExecutionIdentity, script: String): CommandResult {
        return when (identity) {
            ExecutionIdentity.ROOT -> RootAccess.runScript(script)
            ExecutionIdentity.SHIZUKU_ROOT, ExecutionIdentity.SHIZUKU_SHELL -> ShizukuAccess.runScript(script)
            ExecutionIdentity.NONE -> CommandResult(false, -1, "No execution identity")
        }
    }

    private fun downloadArchive(
        updateInfo: UpdateInfo,
        outputFile: File,
        onProgress: (DownloadProgress) -> Unit,
    ) {
        val urls = UpdateRepository.buildDownloadUrls(updateInfo.version, NativeServicePaths.currentAbi)
        ReleaseHttp.downloadToFile(urls, outputFile, onProgress)
    }

    private fun sharedArchiveFile(updateInfo: UpdateInfo): File {
        return File("/sdcard/Download/server-${updateInfo.version}-${NativeServicePaths.currentAbi}.tar.gz")
    }
}

data class EnvironmentSummary(
    val identity: ExecutionIdentity,
    val installRoot: String,
    val launchPath: String,
    val configPath: String,
    val abi: String,
    val installedVersion: String?,
)

data class AccessSummary(
    val rootAvailable: Boolean,
    val shizukuState: ShizukuAccessState,
)

enum class ShizukuAccessState {
    NOT_CONNECTED,
    UNAUTHORIZED,
    READY,
}

data class DownloadProgress(
    val downloadedBytes: Long,
    val totalBytes: Long,
) {
    val percent: Int
        get() = if (totalBytes <= 0) 0 else ((downloadedBytes * 100) / totalBytes).toInt()
}

data class ServiceRuntimeStatus(
    val state: ServiceRuntimeState,
    val pid: String? = null,
    val detail: String = "",
)

enum class ServiceRuntimeState {
    RUNNING,
    STOPPED,
    UNKNOWN,
    UNAVAILABLE,
}

data class ActionResult(
    val success: Boolean,
    val message: String,
    val detail: String,
) {
    companion object {
        fun from(result: CommandResult, successMessage: String, failureMessage: String): ActionResult {
            return if (result.success) {
                ActionResult(true, successMessage, result.output)
            } else {
                ActionResult(false, failureMessage, result.output)
            }
        }

        fun failure(message: String): ActionResult = ActionResult(false, message, "")
    }
}
