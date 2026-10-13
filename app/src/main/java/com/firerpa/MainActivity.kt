package com.firerpa

import android.Manifest
import android.net.Uri
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.text.LineBreaker
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.core.content.ContextCompat
import androidx.core.widget.doOnTextChanged
import com.firerpa.data.AppPrefs
import com.firerpa.databinding.ActivityMainBinding
import com.firerpa.databinding.DialogDownloadProgressBinding
import com.firerpa.databinding.PageConfigBinding
import com.firerpa.databinding.PageHomeBinding
import com.firerpa.i18n.LocaleManager
import com.firerpa.runtime.RootAccess
import com.firerpa.runtime.ServiceIniDocument
import com.firerpa.runtime.ShizukuAccess
import com.firerpa.security.FirerpaCertificateManager
import com.firerpa.service.ActionResult
import com.firerpa.service.DownloadProgress
import com.firerpa.service.NativeServiceManager
import com.firerpa.service.ServiceConsoleInfo
import com.firerpa.service.ShizukuAccessState
import com.firerpa.service.ServiceRuntimeState
import com.firerpa.service.ServiceRuntimeStatus
import com.firerpa.license.LicenseController
import com.firerpa.ui.MainPagerAdapter
import com.firerpa.ui.NoWrapEditorHelper
import com.firerpa.ui.ServiceLogController
import com.firerpa.update.AppUpdateManager
import com.firerpa.update.UpdateInfo
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import rikka.shizuku.Shizuku
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {
    companion object {
        private const val shizukuPermissionRequestCode = 41
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var pagerAdapter: MainPagerAdapter
    private lateinit var manager: NativeServiceManager
    private lateinit var appUpdateManager: AppUpdateManager
    private lateinit var logController: ServiceLogController
    private lateinit var prefs: AppPrefs
    private var executor: ExecutorService? = null
    private var isActivityDestroyed = false
    private var pendingPrivilegedAction: (() -> Unit)? = null
    private var pendingInstallAction: (() -> Unit)? = null
    private var serviceStateRequestId = 0L
    private var consoleAccessRequestId = 0L
    private var lastServiceState: ServiceRuntimeState? = null
    private var hasConsoleAccessData = false
    private var syncingConfigAuthUi = false
    private var configAuthLockedByExternalCert = false
    private var configLoadedIniRaw = ""
    private var pendingApkInstall: File? = null
    private val updatePromptQueue = ArrayDeque<() -> Unit>()
    private var updatePromptActive = false
    private var holdUpdatePromptQueue = false

    private val installPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val apkFile = pendingApkInstall
            pendingApkInstall = null
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                packageManager.canRequestPackageInstalls() &&
                apkFile != null
            ) {
                launchApkInstall(apkFile)
            } else if (apkFile != null) {
                postAction(getString(R.string.app_install_permission_required), isError = true)
            }
        }

    private val storagePermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val granted = result.values.all { it }
            val action = pendingInstallAction
            pendingInstallAction = null
            if (granted) {
                action?.invoke()
            } else {
                postAction(getString(R.string.storage_permission_denied), isError = true)
                resumeUpdatePromptQueue()
            }
        }

    private val manageStorageLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val action = pendingInstallAction
            val granted = hasDownloadStoragePermission()
            if (granted) {
                pendingInstallAction = null
                action?.invoke()
            } else {
                pendingInstallAction = null
                postAction(getString(R.string.all_files_access_denied), isError = true)
                resumeUpdatePromptQueue()
            }
        }

    private val shizukuPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        if (requestCode != shizukuPermissionRequestCode) {
            return@OnRequestPermissionResultListener
        }

        runOnUiThread {
            val action = pendingPrivilegedAction
            pendingPrivilegedAction = null
            if (grantResult == PackageManager.PERMISSION_GRANTED) {
                ShizukuAccess.invalidateShellProbe()
                action?.invoke()
            } else {
                postAction(getString(R.string.no_execution_identity), isError = true)
                renderEnvironment()
                resumeUpdatePromptQueue()
            }
        }
    }

    private val shizukuBinderReceivedListener = Shizuku.OnBinderReceivedListener {
        ShizukuAccess.invalidateShellProbe()
        runOnUiThread {
            if (::manager.isInitialized && ::binding.isInitialized && !isActivityDestroyed) {
                binding.root.post { renderEnvironment() }
            }
        }
    }

    private val shizukuBinderDeadListener = Shizuku.OnBinderDeadListener {
        ShizukuAccess.invalidateShellProbe()
        runOnUiThread {
            if (::manager.isInitialized && ::binding.isInitialized && !isActivityDestroyed) {
                binding.root.post { renderEnvironment() }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        LocaleManager.applySavedLocale(this)
        isActivityDestroyed = false
        executor = Executors.newSingleThreadExecutor()
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        manager = NativeServiceManager(applicationContext)
        appUpdateManager = AppUpdateManager(applicationContext)
        logController = ServiceLogController(
            logReader = manager.serviceLogReader,
            executorProvider = ::activeExecutor,
            unavailableText = getString(R.string.log_unavailable),
            emptyText = getString(R.string.log_empty),
            exportSuccessText = { path -> getString(R.string.log_export_success, path) },
            exportEmptyText = getString(R.string.log_export_empty),
            exportFailedText = getString(R.string.log_export_failed),
            onMessage = { message ->
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            },
        )
        prefs = AppPrefs(applicationContext)
        pagerAdapter = MainPagerAdapter(
            onConfigPageBound = ::bindConfigPage,
            onLogPageBound = logController::bind,
            onLicensePageBound = (LicenseController(this, prefs, ::ensureLamdaRunning))::bind,
            onHomePageBound = ::onHomePageReady,
        )

        setupPager()

        ShizukuAccess.addRequestPermissionResultListener(shizukuPermissionListener)
        ShizukuAccess.addBinderReceivedListenerSticky(shizukuBinderReceivedListener)
        ShizukuAccess.addBinderDeadListener(shizukuBinderDeadListener)

        checkForStartupUpdates()
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        applyNavigationLabels()
    }

    override fun onResume() {
        super.onResume()
        activeExecutor()?.execute {
            RootAccess.refreshProbe()
            ShizukuAccess.refreshShellProbe(force = true)
            runOnUiThread {
                if (isActivityDestroyed) {
                    return@runOnUiThread
                }
                renderAccessStatus()
                renderEnvironmentSummary()
                refreshServiceState()
            }
        } ?: run {
            renderAccessStatus()
        }
    }

    override fun onPause() {
        logController.stop()
        super.onPause()
    }

    override fun onDestroy() {
        isActivityDestroyed = true
        if (::binding.isInitialized) {
            binding.root.removeCallbacks(serviceStateRefreshRunnable)
        }
        logController.stop()
        ShizukuAccess.removeRequestPermissionResultListener(shizukuPermissionListener)
        ShizukuAccess.removeBinderReceivedListener(shizukuBinderReceivedListener)
        ShizukuAccess.removeBinderDeadListener(shizukuBinderDeadListener)
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    private fun setupPager() {
        binding.viewPager.adapter = pagerAdapter
        binding.viewPager.isUserInputEnabled = false
        binding.bottomNavigation.setOnItemSelectedListener { item ->
            val targetPage = when (item.itemId) {
                R.id.nav_home -> MainPagerAdapter.pageHome
                R.id.nav_config -> MainPagerAdapter.pageConfig
                R.id.nav_license -> MainPagerAdapter.pageLicense
                R.id.nav_log -> MainPagerAdapter.pageLog
                else -> return@setOnItemSelectedListener false
            }
            if (binding.viewPager.currentItem == targetPage) {
                return@setOnItemSelectedListener true
            }

            if (item.itemId != R.id.nav_log) {
                logController.stop()
            }
            binding.viewPager.setCurrentItem(targetPage, false)
            when (item.itemId) {
                R.id.nav_config -> refreshConfigEditor()
                R.id.nav_log -> logController.start()
            }
            true
        }
        applyNavigationLabels()
        binding.bottomNavigation.selectedItemId = R.id.nav_license

        binding.viewPager.post {
            homeBinding()?.autoStartSwitch?.isChecked = manager.isAutoStartEnabled()
            bindActions()
            renderEnvironment()
        }
    }

    private fun ensureLamdaRunning() {
        if (lastServiceState != ServiceRuntimeState.RUNNING) {
            manager.startService()
        }
    }

    private fun onHomePageReady(home: PageHomeBinding) {
        home.autoStartSwitch.isChecked = manager.isAutoStartEnabled()
        bindActions()
        renderEnvironment()
    }

    private fun applyNavigationLabels() {
        if (!::binding.isInitialized) {
            return
        }
        binding.bottomNavigation.menu.apply {
            findItem(R.id.nav_home)?.setTitle(LocaleManager.stringForAppLocale(this@MainActivity, R.string.tab_home))
            findItem(R.id.nav_config)?.setTitle(LocaleManager.stringForAppLocale(this@MainActivity, R.string.tab_config))
            findItem(R.id.nav_license)?.setTitle(LocaleManager.stringForAppLocale(this@MainActivity, R.string.tab_license))
            findItem(R.id.nav_log)?.setTitle(LocaleManager.stringForAppLocale(this@MainActivity, R.string.tab_log))
        }
    }

    private fun bindConfigPage(config: PageConfigBinding) {
        NoWrapEditorHelper.configureConfigEditor(
            config.configEditText,
            config.configScrollView,
            config.configHorizontalScrollView,
        )
        config.authEnabledSwitch.setOnCheckedChangeListener { _, enabled ->
            if (syncingConfigAuthUi || configAuthLockedByExternalCert) {
                return@setOnCheckedChangeListener
            }
            setConfigAuthInputsVisible(config, enabled)
            if (enabled) {
                config.accessPasswordInput.setText(manager.defaultAccessCredential())
            }
            refreshConfigEditorText(config)
        }
        config.accessPasswordInput.doOnTextChanged { _, _, _, _ ->
            if (syncingConfigAuthUi || configAuthLockedByExternalCert || !config.authEnabledSwitch.isChecked) {
                return@doOnTextChanged
            }
            val selection = config.accessPasswordInput.selectionStart
            refreshConfigEditorText(config)
            val length = config.accessPasswordInput.text?.length ?: 0
            config.accessPasswordInput.setSelection(selection.coerceIn(0, length))
        }
        config.configEditText.doOnTextChanged { _, _, _, _ ->
            if (syncingConfigAuthUi) {
                return@doOnTextChanged
            }
            updateConfigAuthLockFromEditor(config)
        }
        config.applyConfigButton.setOnClickListener {
            runApplyConfigTask(config)
        }
        refreshConfigEditor()
    }

    private fun setConfigAuthInputsVisible(config: PageConfigBinding, visible: Boolean) {
        config.accessPasswordRow.isVisible = visible
    }

    private fun syncConfigAuthFromIni(config: PageConfigBinding, ini: String) {
        syncingConfigAuthUi = true
        configAuthLockedByExternalCert = ServiceIniDocument.requiresForcedAccessAuthentication(ini)
        val auth = ServiceIniDocument.readAuthSettings(ini)
        val authEnabled = configAuthLockedByExternalCert || auth.enabled
        config.authEnabledSwitch.isChecked = authEnabled
        setConfigAuthInputsVisible(config, authEnabled)
        val password = when {
            configAuthLockedByExternalCert -> auth.password
            authEnabled -> manager.defaultAccessCredential()
            else -> ""
        }
        config.accessPasswordInput.setText(password)
        syncingConfigAuthUi = false
        applyConfigAuthLockState(config)
    }

    private fun updateConfigAuthLockFromEditor(config: PageConfigBinding) {
        val editorIni = config.configEditText.text?.toString().orEmpty()
        val locked = ServiceIniDocument.requiresForcedAccessAuthentication(editorIni)
        if (locked == configAuthLockedByExternalCert) {
            return
        }
        configAuthLockedByExternalCert = locked
        applyConfigAuthLockState(config)
        refreshConfigEditorText(config)
    }

    private fun applyConfigAuthLockState(config: PageConfigBinding) {
        if (configAuthLockedByExternalCert) {
            syncingConfigAuthUi = true
            config.authEnabledSwitch.isChecked = true
            config.authEnabledSwitch.isEnabled = false
            setConfigAuthInputsVisible(config, true)
            config.accessPasswordInput.isEnabled = false
            syncingConfigAuthUi = false
            return
        }
        config.authEnabledSwitch.isEnabled = true
        config.accessPasswordInput.isEnabled = config.authEnabledSwitch.isChecked
    }

    private fun enableConfigPageControls(config: PageConfigBinding) {
        config.configEditText.isEnabled = true
        config.applyConfigButton.isEnabled = true
        applyConfigAuthLockState(config)
    }

    private fun presentConfigEditor(
        config: PageConfigBinding,
        ini: String,
    ) {
        configLoadedIniRaw = ini
        syncConfigAuthFromIni(config, ini)
        refreshConfigEditorText(config, sourceIni = ini)
    }

    private fun refreshConfigEditorText(
        config: PageConfigBinding,
        sourceIni: String? = null,
    ) {
        val authEnabled = config.authEnabledSwitch.isChecked
        val password = config.accessPasswordInput.text?.toString().orEmpty()
        val editorSource = sourceIni ?: config.configEditText.text?.toString().orEmpty()
        syncingConfigAuthUi = true
        val editorContent = if (configAuthLockedByExternalCert) {
            ServiceIniDocument.toEditorContent(editorSource)
        } else {
            ServiceIniDocument.previewEditorContent(
                raw = editorSource,
                authEnabled = authEnabled,
                password = password,
                clientPemBase64 = manager.clientPemBase64OneLine(),
            )
        }
        config.configEditText.setText(editorContent)
        syncingConfigAuthUi = false
    }

    private fun refreshConfigEditor() {
        val config = pagerAdapter.configBinding ?: return
        config.configEditText.isEnabled = false
        config.applyConfigButton.isEnabled = false
        config.authEnabledSwitch.isEnabled = false
        config.accessPasswordInput.isEnabled = false

        val canReadShell = RootAccess.isAvailable() || ShizukuAccess.hasPermission()
        if (!canReadShell) {
            presentConfigEditor(config, manager.localIniTemplate())
            enableConfigPageControls(config)
            return
        }

        runBackground(
            onStart = {},
            task = { manager.loadIniFromDevice() },
            onSuccess = { content ->
                presentConfigEditor(config, content)
                enableConfigPageControls(config)
            },
            onFailure = {
                presentConfigEditor(config, manager.localIniTemplate())
                enableConfigPageControls(config)
            },
        )
    }

    private fun bindActions() {
        configureExecutionAccessErrorText()
        homeBinding()?.autoStartSwitch?.setOnCheckedChangeListener { _, isChecked ->
            manager.setAutoStart(isChecked)
            postAction(
                if (isChecked) getString(R.string.auto_start_enabled) else getString(R.string.auto_start_disabled),
            )
        }

        homeBinding()?.languageButton?.setOnClickListener {
            LocaleManager.toggleLanguage(this)
            applyNavigationLabels()
            recreate()
        }

        homeBinding()?.startButton?.setOnClickListener {
            runStartTask()
        }

        homeBinding()?.stopButton?.setOnClickListener {
            runStopTask()
        }

        homeBinding()?.uninstallButton?.setOnClickListener {
            promptUninstall()
        }

        bindCopyableInfoRows()
    }

    private fun configureExecutionAccessErrorText() {
        val textView = homeBinding()?.executionAccessErrorText ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            textView.justificationMode = LineBreaker.JUSTIFICATION_MODE_INTER_WORD
        }
    }

    private fun bindCopyableInfoRows() {
        val page = homeBinding() ?: return
        bindCopyableRow(page.installDirRow, page.installDirValue)
        bindMcpEndpointRow(page)
        bindCopyableRow(page.consoleUrlRow, page.consoleUrlValue)
        bindCopyableRow(page.consoleCredentialRow, page.consoleCredentialValue)
    }

    private fun bindMcpEndpointRow(page: PageHomeBinding) {
        val copyAction = View.OnClickListener {
            onMcpEndpointCopy(page.mcpEndpointValue.text?.toString().orEmpty())
        }
        page.mcpEndpointRow.setOnClickListener(copyAction)
        page.mcpEndpointValue.setOnClickListener(copyAction)
    }

    private fun onMcpEndpointCopy(text: String) {
        if (!copyTextToClipboard(text)) {
            return
        }
        if (text.contains("https://", ignoreCase = true)) {
            showMcpHttpsCertificateDialog()
        }
    }

    private fun showMcpHttpsCertificateDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.mcp_https_cert_dialog_title)
            .setMessage(R.string.mcp_https_cert_dialog_message)
            .setPositiveButton(R.string.mcp_export_root_cert) { _, _ ->
                exportRootCertificateToDownloads()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun exportRootCertificateToDownloads() {
        ensureDownloadStoragePermission {
            runCatching {
                FirerpaCertificateManager.exportRootCertificateToDownloads(applicationContext)
            }.fold(
                onSuccess = { file ->
                    postAction(getString(R.string.root_cert_export_success, file.absolutePath))
                },
                onFailure = {
                    postAction(getString(R.string.root_cert_export_failed), isError = true)
                },
            )
        }
    }

    private fun bindCopyableRow(row: View?, valueView: TextView?) {
        row ?: return
        valueView ?: return
        val copyAction = View.OnClickListener {
            copyTextToClipboard(valueView.text?.toString().orEmpty())
        }
        row.setOnClickListener(copyAction)
        valueView.setOnClickListener(copyAction)
    }

    private fun copyTextToClipboard(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || trimmed == getString(R.string.none_text)) {
            return false
        }

        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("firerpa", trimmed))
        Toast.makeText(this, R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
        return true
    }

    private fun runApplyConfigTask(config: PageConfigBinding) {
        val rawIni = config.configEditText.text?.toString().orEmpty()
        configAuthLockedByExternalCert =
            ServiceIniDocument.requiresForcedAccessAuthentication(rawIni)

        val mergedIni = try {
            if (configAuthLockedByExternalCert) {
                ServiceIniDocument.mergeEditorPreservingAuthKeys(
                    rawEditor = rawIni,
                    originalRaw = configLoadedIniRaw,
                )
            } else {
                val authEnabled = config.authEnabledSwitch.isChecked
                val password = config.accessPasswordInput.text?.toString().orEmpty()
                if (authEnabled) {
                    when {
                        password.isBlank() -> {
                            postAction(getString(R.string.auth_password_required), isError = true)
                            return
                        }
                        !ServiceIniDocument.isValidAuthPassword(password) -> {
                            postAction(getString(R.string.auth_password_length_invalid), isError = true)
                            return
                        }
                    }
                }
                ServiceIniDocument.applyAuthSettings(
                    raw = ServiceIniDocument.toEditorContent(rawIni),
                    enabled = authEnabled,
                    password = password,
                    clientPemBase64 = manager.clientPemBase64OneLine(),
                )
            }
        } catch (_: Exception) {
            postAction(getString(R.string.ini_invalid_format), isError = true)
            return
        }

        ensurePrivilegedExecution {
            runBackground(
                onStart = {
                    setBlockingState(
                        visible = true,
                        title = getString(R.string.applying_config),
                        message = "",
                    )
                    renderServiceStateChecking()
                },
                task = { manager.applyIniAndRestart(mergedIni) },
                onSuccess = { result ->
                    setBlockingState(visible = false)
                    consumeActionResult(result)
                    refreshConfigEditor()
                    renderEnvironment()
                },
                onFailure = { error ->
                    setBlockingState(visible = false)
                    postAction(error.message ?: getString(R.string.unexpected_failure), isError = true)
                    renderEnvironment()
                },
            )
        }
    }

    private fun renderEnvironment() {
        renderEnvironmentSummary()
        refreshServiceState()
    }

    private fun renderEnvironmentSummary() {
        val summary = manager.buildEnvironmentSummary()
        homeBinding()?.identityValue?.text = getString(summary.identity.labelResId)
        homeBinding()?.abiValue?.text = summary.abi
        homeBinding()?.installDirValue?.text = summary.installRoot
        homeBinding()?.heroBadgeText?.text = buildHeroBadgeText(summary.installedVersion)
        homeBinding()?.uninstallButton?.isVisible = !summary.installedVersion.isNullOrBlank()
        renderAccessStatus()
    }

    private fun buildHeroBadgeText(installedVersion: String?): String {
        val badge = getString(R.string.hero_badge)
        val version = installedVersion.orEmpty()
        return if (version.isBlank()) badge else "$badge $version"
    }

    private fun renderAccessStatus() {
        val binding = homeBinding() ?: return
        val access = manager.buildAccessSummary()

        binding.rootAccessValue.text = getString(
            if (access.rootAvailable) R.string.root_status_available else R.string.root_status_unavailable,
        )
        binding.rootAccessValue.setTextColor(
            ContextCompat.getColor(
                this,
                if (access.rootAvailable) R.color.statusRunning else R.color.statusUnknown,
            ),
        )

        val shizukuLabelRes = when (access.shizukuState) {
            ShizukuAccessState.READY -> R.string.shizuku_status_ready
            ShizukuAccessState.UNAUTHORIZED -> R.string.shizuku_status_unauthorized
            ShizukuAccessState.NOT_CONNECTED -> R.string.root_status_unavailable
        }
        val shizukuColorRes = when (access.shizukuState) {
            ShizukuAccessState.READY -> R.color.statusRunning
            ShizukuAccessState.NOT_CONNECTED -> R.color.statusUnknown
            ShizukuAccessState.UNAUTHORIZED -> R.color.statusStopped
        }
        binding.shizukuAccessValue.text = getString(shizukuLabelRes)
        binding.shizukuAccessValue.setTextColor(ContextCompat.getColor(this, shizukuColorRes))
        binding.executionAccessErrorCard.isVisible =
            !access.rootAvailable && access.shizukuState != ShizukuAccessState.READY
    }

    private fun checkForStartupUpdates() {
        runBackground(
            onStart = {},
            task = {
                Pair(
                    runCatching { appUpdateManager.fetchLatest() },
                    runCatching { manager.fetchLatestUpdate() },
                )
            },
            onSuccess = { (appResult, serviceResult) ->
                appResult.getOrNull()?.let { updateInfo ->
                    if (appUpdateManager.shouldPromptUpdate(updateInfo)) {
                        enqueueUpdatePrompt { promptAppUpdate(updateInfo, fromQueue = true) }
                    }
                }
                serviceResult.getOrNull()?.let { updateInfo ->
                    if (manager.shouldForceInstall()) {
                        enqueueUpdatePrompt { promptInstall(updateInfo, mandatory = true, fromQueue = true) }
                    }
                }
                serviceResult.getOrNull()?.let { updateInfo ->
                    if (!manager.shouldForceInstall() && manager.shouldPromptUpdate(updateInfo)) {
                        enqueueUpdatePrompt { promptInstall(updateInfo, mandatory = false, fromQueue = true) }
                    }
                }
            },
            onFailure = {},
        )
    }

    private fun enqueueUpdatePrompt(action: () -> Unit) {
        updatePromptQueue.addLast(action)
        drainUpdatePromptQueue()
    }

    private fun drainUpdatePromptQueue() {
        if (updatePromptActive || updatePromptQueue.isEmpty() || isActivityDestroyed) {
            return
        }
        updatePromptActive = true
        updatePromptQueue.removeFirst().invoke()
    }

    private fun onUpdatePromptDismissed() {
        updatePromptActive = false
        if (holdUpdatePromptQueue) {
            return
        }
        drainUpdatePromptQueue()
    }

    private fun resumeUpdatePromptQueue() {
        holdUpdatePromptQueue = false
        drainUpdatePromptQueue()
    }

    private fun clearPendingUpdatePrompts() {
        updatePromptQueue.clear()
        updatePromptActive = false
    }

    private fun checkForAppUpdate(autoTriggered: Boolean) {
        runBackground(
            onStart = {},
            task = { appUpdateManager.fetchLatest() },
            onSuccess = { updateInfo ->
                if (appUpdateManager.shouldPromptUpdate(updateInfo)) {
                    promptAppUpdate(updateInfo)
                } else if (!autoTriggered) {
                    postAction(getString(R.string.already_up_to_date))
                }
            },
            onFailure = { error ->
                if (!autoTriggered) {
                    postAction(
                        getString(R.string.update_check_failed, error.message ?: getString(R.string.unexpected_failure)),
                        isError = true,
                    )
                }
            },
        )
    }

    private fun promptAppUpdate(updateInfo: UpdateInfo, fromQueue: Boolean = false) {
        val message = buildString {
            append(getString(R.string.version_label, updateInfo.version))
            if (updateInfo.hasChangelog) {
                append("\n\n${updateInfo.changelog}")
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.app_update_available)
            .setMessage(message)
            .setPositiveButton(R.string.update) { _, _ ->
                clearPendingUpdatePrompts()
                performAppUpdate(updateInfo)
            }
            .setNegativeButton(getString(R.string.skip_version, updateInfo.version)) { _, _ ->
                appUpdateManager.markVersionSkipped(updateInfo.version)
            }
            .setNeutralButton(R.string.later, null)

        showUpdatePrompt(builder, fromQueue)
    }

    private fun performAppUpdate(updateInfo: UpdateInfo) {
        val progressBinding = DialogDownloadProgressBinding.inflate(LayoutInflater.from(this))
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(progressBinding.root)
            .setCancelable(false)
            .create()

        progressDialog.show()
        progressDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        progressDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        runBackground(
            onStart = {
                progressBinding.progressTitle.text = getString(R.string.downloading_app_version, updateInfo.version)
                progressBinding.progressStage.text = getString(R.string.progress_stage_fetching_app)
                progressBinding.progressMessage.text = ""
                progressBinding.progressPercent.text = getString(R.string.progress_percent, 0)
            },
            task = {
                appUpdateManager.downloadApk(updateInfo) { progress ->
                    runOnUiThread {
                        renderProgress(progressBinding, progress)
                    }
                }
            },
            onSuccess = { apkFile ->
                progressDialog.dismiss()
                launchApkInstall(apkFile)
            },
            onFailure = { error ->
                progressDialog.dismiss()
                postAction(
                    getString(R.string.install_failed, error.message ?: getString(R.string.unexpected_failure)),
                    isError = true,
                )
            },
        )
    }

    private fun launchApkInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !packageManager.canRequestPackageInstalls()
        ) {
            pendingApkInstall = apkFile
            installPermissionLauncher.launch(
                Intent(
                    Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:$packageName"),
                ),
            )
            return
        }

        val uri = FileProvider.getUriForFile(
            this,
            "${packageName}.fileprovider",
            apkFile,
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(intent)
    }

    private fun checkForUpdates(autoTriggered: Boolean) {
        runBackground(
            onStart = {
                if (!autoTriggered) {
                }
            },
            task = { manager.fetchLatestUpdate() },
            onSuccess = { updateInfo ->
                when {
                    manager.shouldForceInstall() -> promptInstall(updateInfo, mandatory = true)
                    manager.shouldPromptUpdate(updateInfo) -> promptInstall(updateInfo, mandatory = false)
                    !autoTriggered -> postAction(getString(R.string.already_up_to_date))
                }
            },
            onFailure = { error ->
                if (!autoTriggered) {
                    postAction(
                        getString(R.string.update_check_failed, error.message ?: getString(R.string.unexpected_failure)),
                        isError = true,
                    )
                }
            },
        )
    }

    private fun promptInstall(updateInfo: UpdateInfo, mandatory: Boolean, fromQueue: Boolean = false) {
        val title = if (mandatory) getString(R.string.install_required) else getString(R.string.update_available)
        val message = buildString {
            append(getString(R.string.version_label, updateInfo.version))
            if (updateInfo.hasChangelog) {
                append("\n\n${updateInfo.changelog}")
            }
        }

        val builder = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton(if (mandatory) getString(R.string.download_now) else getString(R.string.update)) { _, _ ->
                if (mandatory) {
                    holdUpdatePromptQueue = true
                    ensureDownloadStoragePermission {
                        ensurePrivilegedExecution {
                            performInstall(updateInfo, onComplete = ::resumeUpdatePromptQueue)
                        }
                    }
                } else {
                    clearPendingUpdatePrompts()
                    ensureDownloadStoragePermission {
                        ensurePrivilegedExecution { performInstall(updateInfo) }
                    }
                }
            }

        if (mandatory) {
            builder.setCancelable(false)
        } else {
            builder.setNegativeButton(getString(R.string.skip_version, updateInfo.version)) { _, _ ->
                manager.markVersionSkipped(updateInfo.version)
            }
            builder.setNeutralButton(R.string.later, null)
        }

        showUpdatePrompt(builder, fromQueue)
    }

    private fun showUpdatePrompt(builder: MaterialAlertDialogBuilder, fromQueue: Boolean) {
        if (!fromQueue) {
            builder.show()
            return
        }
        val dialog = builder.create()
        dialog.setOnDismissListener { onUpdatePromptDismissed() }
        dialog.show()
    }

    private fun performInstall(
        updateInfo: UpdateInfo,
        onInstalled: (() -> Unit)? = null,
        onComplete: (() -> Unit)? = null,
    ) {
        val progressBinding = DialogDownloadProgressBinding.inflate(LayoutInflater.from(this))
        val progressDialog = MaterialAlertDialogBuilder(this)
            .setView(progressBinding.root)
            .setCancelable(false)
            .create()

        progressDialog.show()
        progressDialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        progressDialog.window?.setLayout(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
        )

        runBackground(
            onStart = {
                progressBinding.progressTitle.text = getString(R.string.downloading_version, updateInfo.version)
                progressBinding.progressStage.text = getString(
                    R.string.progress_stage_fetching,
                    manager.buildEnvironmentSummary().abi,
                )
                progressBinding.progressMessage.text = ""
                progressBinding.progressPercent.text = getString(R.string.progress_percent, 0)
            },
            task = {
                manager.installOrUpdate(updateInfo) { progress ->
                    runOnUiThread {
                        renderProgress(progressBinding, progress)
                    }
                }
            },
            onSuccess = { result ->
                progressDialog.dismiss()
                consumeActionResult(result)
                renderEnvironment()
                if (result.success) {
                    onInstalled?.invoke()
                }
                onComplete?.invoke()
            },
            onFailure = { error ->
                progressDialog.dismiss()
                postAction(
                    getString(R.string.install_failed, error.message ?: getString(R.string.unexpected_failure)),
                    isError = true,
                )
                onComplete?.invoke()
            },
        )
    }

    private fun renderProgress(
        dialogBinding: DialogDownloadProgressBinding,
        progress: DownloadProgress,
    ) {
        val indeterminate = progress.totalBytes <= 0
        dialogBinding.progressBar.isIndeterminate = indeterminate
        if (!indeterminate) {
            dialogBinding.progressBar.max = 100
            dialogBinding.progressBar.progress = max(progress.percent, 1)
        }

        dialogBinding.progressStage.text =
            if (indeterminate) getString(R.string.progress_stage_streaming) else getString(R.string.progress_stage_transfer)
        dialogBinding.progressPercent.text =
            if (indeterminate) getString(R.string.progress_streaming) else getString(R.string.progress_percent, progress.percent)
    }

    private fun runStartTask() {
        ensurePrivilegedExecution {
            runBackground(
                onStart = {
                    renderServiceStateChecking()
                },
                task = { manager.needsServiceInstall() },
                onSuccess = { needsInstall ->
                    if (needsInstall) {
                        ensureDownloadStoragePermission {
                            installLatestThenStart()
                        }
                    } else {
                        executeStartTask()
                    }
                },
                onFailure = { error ->
                    postAction(error.message ?: getString(R.string.unexpected_failure), isError = true)
                    renderEnvironment()
                },
            )
        }
    }

    private fun installLatestThenStart() {
        runBackground(
            onStart = { },
            task = { manager.fetchLatestUpdate() },
            onSuccess = { updateInfo ->
                performInstall(updateInfo) {
                    executeStartTask()
                }
            },
            onFailure = { error ->
                postAction(
                    getString(R.string.update_check_failed, error.message ?: getString(R.string.unexpected_failure)),
                    isError = true,
                )
                renderEnvironment()
            },
        )
    }

    private fun executeStartTask() {
        ensurePrivilegedExecution {
            runBackground(
                onStart = {
                    setBlockingState(
                        visible = true,
                        title = getString(R.string.service_starting_title),
                        message = "",
                    )
                    renderServiceStateChecking()
                },
                task = { manager.startServiceAndWait() },
                onSuccess = { result ->
                    setBlockingState(visible = false)
                    consumeActionResult(result)
                    renderEnvironment()
                },
                onFailure = { error ->
                    setBlockingState(visible = false)
                    postAction(error.message ?: getString(R.string.unexpected_failure), isError = true)
                    renderEnvironment()
                },
            )
        }
    }

    private fun runStopTask() {
        ensurePrivilegedExecution {
            runBackground(
                onStart = {
                    setBlockingState(
                        visible = true,
                        title = getString(R.string.service_stopping_title),
                        message = "",
                    )
                    renderServiceStateChecking()
                },
                task = { manager.stopService() },
                onSuccess = { result ->
                    setBlockingState(visible = false)
                    consumeActionResult(result)
                    renderEnvironment()
                },
                onFailure = { error ->
                    setBlockingState(visible = false)
                    postAction(error.message ?: getString(R.string.unexpected_failure), isError = true)
                    renderEnvironment()
                },
            )
        }
    }

    private fun promptUninstall() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.uninstall_confirm_title)
            .setMessage(R.string.uninstall_confirm_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                runUninstall()
            }
            .show()
    }

    private fun runUninstall() {
        ensurePrivilegedExecution {
            runBackground(
                onStart = {
                    setBlockingState(
                        visible = true,
                        title = getString(R.string.removing_service),
                        message = "",
                    )
                    renderServiceStateChecking()
                },
                task = {
                    val stopResult = manager.stopService()
                    if (!stopResult.success && stopResult.message != getString(R.string.action_service_already_stopped)) {
                        stopResult
                    } else {
                        manager.uninstallService()
                    }
                },
                onSuccess = { result ->
                    setBlockingState(visible = false)
                    consumeActionResult(result)
                    renderEnvironment()
                    if (result.success) {
                        showRebootRecommendedDialog()
                    }
                },
                onFailure = { error ->
                    setBlockingState(visible = false)
                    postAction(error.message ?: getString(R.string.unexpected_failure), isError = true)
                    renderEnvironment()
                },
            )
        }
    }

    private fun showRebootRecommendedDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.reboot_recommended_title)
            .setMessage(R.string.reboot_recommended_message)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun ensureDownloadStoragePermission(onGranted: () -> Unit) {
        if (hasDownloadStoragePermission()) {
            onGranted()
            return
        }

        pendingInstallAction = onGranted
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            )
            manageStorageLauncher.launch(intent)
        } else {
            val permissions = buildList {
                add(Manifest.permission.READ_EXTERNAL_STORAGE)
                if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                    add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                }
            }.toTypedArray()
            storagePermissionLauncher.launch(permissions)
        }
    }

    private fun hasDownloadStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val readGranted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE,
            ) == PackageManager.PERMISSION_GRANTED
            val writeGranted = if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            readGranted && writeGranted
        }
    }

    private fun ensurePrivilegedExecution(onReady: () -> Unit) {
        val backgroundExecutor = activeExecutor()
        if (backgroundExecutor == null) {
            postAction(getString(R.string.unexpected_failure), isError = true)
            return
        }

        backgroundExecutor.execute {
            RootAccess.refreshProbe(force = !RootAccess.isAvailable())
            runOnUiThread {
                if (isActivityDestroyed) {
                    return@runOnUiThread
                }
                proceedPrivilegedExecution(onReady)
            }
        }
    }

    private fun proceedPrivilegedExecution(onReady: () -> Unit) {
        if (RootAccess.isAvailable()) {
            onReady()
            return
        }

        when {
            !ShizukuAccess.isBinderReady() -> {
                postAction(getString(R.string.no_execution_identity), isError = true)
                resumeUpdatePromptQueue()
            }
            ShizukuAccess.hasPermission() -> {
                onReady()
            }
            else -> {
                pendingPrivilegedAction = onReady
                ShizukuAccess.requestPermission(shizukuPermissionRequestCode)
            }
        }
        renderEnvironment()
    }

    private fun consumeActionResult(result: ActionResult) {
        postAction(result.message, isError = !result.success)
    }

    private fun postAction(message: String, isError: Boolean = false) {
        val toastLength = if (isError) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        Toast.makeText(this, message, toastLength).show()
    }

    private fun refreshServiceState() {
        if (activeExecutor() == null) {
            return
        }
        binding.root.removeCallbacks(serviceStateRefreshRunnable)
        binding.root.postDelayed(serviceStateRefreshRunnable, 200L)
    }

    private val serviceStateRefreshRunnable = Runnable {
        val backgroundExecutor = activeExecutor() ?: return@Runnable
        val requestId = ++serviceStateRequestId
        backgroundExecutor.execute {
            val status = manager.queryServiceRuntime()
            runOnUiThread {
                if (isActivityDestroyed || requestId != serviceStateRequestId) {
                    return@runOnUiThread
                }
                applyServiceState(status)
            }
        }
    }

    private fun renderServiceStateChecking() {
        val chip = homeBinding()?.serviceStateChip ?: return
        hasConsoleAccessData = false
        chip.text = getString(R.string.service_state_checking)
        chip.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, R.color.statusUnknownBg),
        )
        chip.setTextColor(ContextCompat.getColor(this, R.color.statusUnknown))
        setActionButtonEnabled(homeBinding()?.startButton, false)
        setActionButtonEnabled(homeBinding()?.stopButton, false)
    }

    private fun applyServiceState(status: ServiceRuntimeStatus) {
        val chip = homeBinding()?.serviceStateChip ?: return
        val previousState = lastServiceState
        lastServiceState = status.state
        val stateChanged = previousState != status.state
        val labelRes = when (status.state) {
            ServiceRuntimeState.RUNNING -> R.string.service_state_running
            ServiceRuntimeState.STOPPED -> R.string.service_state_stopped
            ServiceRuntimeState.UNAVAILABLE -> R.string.service_state_unavailable
            ServiceRuntimeState.UNKNOWN -> R.string.service_state_unknown
        }
        val backgroundRes = when (status.state) {
            ServiceRuntimeState.RUNNING -> R.color.statusRunningBg
            ServiceRuntimeState.STOPPED -> R.color.statusStoppedBg
            ServiceRuntimeState.UNAVAILABLE, ServiceRuntimeState.UNKNOWN -> R.color.statusUnknownBg
        }
        val textColorRes = when (status.state) {
            ServiceRuntimeState.RUNNING -> R.color.statusRunning
            ServiceRuntimeState.STOPPED -> R.color.statusStopped
            ServiceRuntimeState.UNAVAILABLE, ServiceRuntimeState.UNKNOWN -> R.color.statusUnknown
        }

        chip.text = getString(labelRes)
        chip.backgroundTintList = ColorStateList.valueOf(
            ContextCompat.getColor(this, backgroundRes),
        )
        chip.setTextColor(ContextCompat.getColor(this, textColorRes))

        when (status.state) {
            ServiceRuntimeState.RUNNING -> {
                setActionButtonEnabled(homeBinding()?.startButton, false)
                setActionButtonEnabled(homeBinding()?.stopButton, true)
                if (stateChanged || !hasConsoleAccessData) {
                    refreshConsoleAccess()
                }
            }

            ServiceRuntimeState.STOPPED -> {
                setActionButtonEnabled(homeBinding()?.startButton, true)
                setActionButtonEnabled(homeBinding()?.stopButton, false)
                hasConsoleAccessData = false
                hideConsoleAccess()
            }

            ServiceRuntimeState.UNAVAILABLE, ServiceRuntimeState.UNKNOWN -> {
                setActionButtonEnabled(homeBinding()?.startButton, false)
                setActionButtonEnabled(homeBinding()?.stopButton, false)
                hasConsoleAccessData = false
                hideConsoleAccess()
            }
        }
    }

    private fun hideConsoleAccess() {
        homeBinding()?.consoleAccessPanel?.isVisible = false
    }

    private fun refreshConsoleAccess() {
        val backgroundExecutor = activeExecutor() ?: run {
            hasConsoleAccessData = false
            hideConsoleAccess()
            return
        }

        val requestId = ++consoleAccessRequestId
        backgroundExecutor.execute {
            val info = runCatching { manager.loadServiceConsoleInfo() }.getOrNull()
            runOnUiThread {
                if (isActivityDestroyed || requestId != consoleAccessRequestId) {
                    return@runOnUiThread
                }
                renderConsoleAccess(info)
            }
        }
    }

    private fun renderConsoleAccess(info: ServiceConsoleInfo?) {
        val page = homeBinding() ?: return
        if (info == null) {
            hasConsoleAccessData = false
            hideConsoleAccess()
            return
        }

        hasConsoleAccessData = true
        val urls = info.backendUrls.joinToString("  ")
        if (!page.consoleAccessPanel.isVisible) {
            page.consoleAccessPanel.isVisible = true
        }

        val credential = info.credential.orEmpty()
        val mcpEndpoints = info.formatMcpEndpoints()
        if (!page.mcpEndpointRow.isVisible) {
            page.mcpEndpointRow.isVisible = true
        }
        if (page.mcpEndpointValue.text?.toString() != mcpEndpoints) {
            page.mcpEndpointValue.text = mcpEndpoints
        }

        if (page.consoleUrlValue.text?.toString() != urls) {
            page.consoleUrlValue.text = urls
        }

        if (credential.isBlank()) {
            if (page.consoleCredentialRow.isVisible) {
                page.consoleCredentialRow.isVisible = false
            }
        } else {
            if (!page.consoleCredentialRow.isVisible) {
                page.consoleCredentialRow.isVisible = true
            }
            if (page.consoleCredentialValue.text?.toString() != credential) {
                page.consoleCredentialValue.text = credential
            }
        }
    }

    private fun setActionButtonEnabled(button: MaterialButton?, enabled: Boolean) {
        button ?: return
        button.isEnabled = enabled
        button.alpha = if (enabled) 1f else 0.42f
    }

    private fun setBlockingState(
        visible: Boolean,
        title: String = "",
        message: String = "",
    ) {
        binding.blockingOverlay.isVisible = visible
        if (!visible) {
            return
        }

        binding.blockingTitle.text = title
        binding.blockingMessage.text = message
        binding.blockingMessage.isVisible = message.isNotBlank()
    }

    private fun <T> runBackground(
        onStart: () -> Unit,
        task: () -> T,
        onSuccess: (T) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (isActivityDestroyed) {
            return
        }
        onStart()
        val backgroundExecutor = activeExecutor() ?: return
        backgroundExecutor.execute {
            runCatching(task).onSuccess { value ->
                runOnUiThread {
                    if (isActivityDestroyed) {
                        return@runOnUiThread
                    }
                    onSuccess(value)
                }
            }.onFailure { throwable ->
                runOnUiThread {
                    if (isActivityDestroyed) {
                        return@runOnUiThread
                    }
                    onFailure(throwable)
                }
            }
        }
    }

    private fun activeExecutor(): ExecutorService? {
        val current = executor
        return if (current == null || current.isShutdown) null else current
    }

    private fun homeBinding(): PageHomeBinding? = pagerAdapter.homeBinding

    private fun configBinding(): PageConfigBinding? = pagerAdapter.configBinding
}
