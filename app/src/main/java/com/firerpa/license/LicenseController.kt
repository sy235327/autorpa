package com.firerpa.license

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.firerpa.BuildConfig
import com.firerpa.R
import com.firerpa.data.AppPrefs
import com.firerpa.databinding.PageLicenseBinding
import com.firerpa.overlay.OverlayService
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * [fork] 卡密（口令）激活页控制器。
 *  - verify：口令 + 设备ID 激活（服务端完成设备入组与任务下发）
 *  - me：查询激活 / 运行状态
 *  - pause / resume / stop：启动、暂停、结束脚本运行
 *  - 悬浮窗开关：启动 / 停止悬浮控制面板
 * TODO(M3): 自动读取 lamda unique_id（当前为手动填写，等运营方给出）。
 */
class LicenseController(
    private val activity: Activity,
    private val prefs: AppPrefs,
    private val ensureLamdaRunning: () -> Unit = {},
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var binding: PageLicenseBinding? = null

    @Volatile
    private var scriptState: String? = null

    @Volatile
    private var updateAvailable = false

    private var waitingOverlayGrant = false

    private val autoRefreshIntervalMs = 5000L
    private var autoRefreshActive = false
    private val autoRefreshRunnable = object : Runnable {
        override fun run() {
            if (!autoRefreshActive) {
                return
            }
            if (prefs.licenseToken.isNotBlank()) {
                refresh(silent = true)
                loadMyCards()
            }
            mainHandler.postDelayed(this, autoRefreshIntervalMs)
        }
    }

    init {
        (activity as? LifecycleOwner)?.lifecycle?.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                if (waitingOverlayGrant && hasOverlayPermission()) {
                    waitingOverlayGrant = false
                    startOverlay()
                }
                syncOverlaySwitch()
            }

            override fun onStart(owner: LifecycleOwner) {
                autoRefreshActive = true
                mainHandler.removeCallbacks(autoRefreshRunnable)
                mainHandler.postDelayed(autoRefreshRunnable, autoRefreshIntervalMs)
            }

            override fun onStop(owner: LifecycleOwner) {
                autoRefreshActive = false
                mainHandler.removeCallbacks(autoRefreshRunnable)
            }
        })
    }

    fun bind(b: PageLicenseBinding) {
        binding = b
        b.licenseAddCardButton.setOnClickListener { showConfigDialog() }
        b.licenseQueryBindingsButton.setOnClickListener { showQueryDialog() }
        b.licenseToggleScriptButton.setOnClickListener { toggleScript() }
        b.licenseStopScriptButton.setOnClickListener { stopScript() }
        b.licenseUpdateButton.setOnClickListener { acceptUpdate() }
        b.licenseOverlaySwitch.isChecked = prefs.overlayEnabled
        b.licenseOverlaySwitch.setOnCheckedChangeListener { _, checked -> onOverlaySwitch(checked) }
        updateScriptControls()
        loadMyCards()
        syncOverlaySwitch()
        if (prefs.licenseToken.isBlank()) {
            renderStatus("尚未激活。点击「新增卡密」填写服务器与口令完成激活。")
        } else {
            refresh()
        }
    }

    // ---------------- 表单 / 状态 ----------------

    private fun saveLicenseInputs(server: String, port: String, card: String, deviceId: String) {
        prefs.licenseServer = server.trim()
        prefs.licensePort = port.trim().ifBlank { "8000" }
        prefs.licenseCard = card.trim().uppercase()
        prefs.licenseDeviceId = deviceId.trim()
    }

    private fun baseUrl(): String = LicenseApi.baseUrl(prefs.licenseServer, prefs.licensePort)

    private fun renderStatus(text: String) {
        mainHandler.post {
            binding?.licenseStatusText?.text = text
        }
    }

    private fun updateScriptControls() {
        val b = binding ?: return
        val activated = prefs.licenseToken.isNotBlank()
        b.licenseControlRow.visibility = if (activated) View.VISIBLE else View.GONE
        b.licenseUpdateButton.visibility = if (activated && updateAvailable) View.VISIBLE else View.GONE
        if (!activated) {
            return
        }
        val state = scriptState
        b.licenseToggleScriptButton.text = activity.getString(
            if (state == "active") R.string.license_script_pause else R.string.license_script_start
        )
        b.licenseStopScriptButton.isEnabled = state == "active" || state == "paused"
    }

    private fun stateLabel(state: String?): String = when (state) {
        "active" -> "运行中"
        "idle" -> "已激活（未启动）"
        "paused" -> "已暂停"
        "stopped" -> "已结束"
        "kicked" -> "已停用"
        else -> "未知"
    }

    // ---------------- 激活 ----------------

    private fun verifyFromDialog(server: String, port: String, card: String, deviceId: String) {
        saveLicenseInputs(server, port, card, deviceId)
        val code = prefs.licenseCard
        val devId = prefs.licenseDeviceId
        if (code.isBlank() || devId.isBlank()) {
            renderStatus("请先填写 口令 与 设备ID。")
            return
        }
        renderStatus("正在校验…")
        executor.execute {
            val result = runCatching { LicenseApi.verify(baseUrl(), code, devId) }
            mainHandler.post {
                result.onSuccess { handleVerifyResponse(it) }
                    .onFailure { renderStatus("请求失败：" + friendly(it)) }
            }
        }
    }

    /** 新增卡密弹窗（服务器/端口/口令/设备ID + 校验并激活） */
    private fun showConfigDialog() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_license_config, null)
        val serverInput = view.findViewById<EditText>(R.id.dlgServerInput)
        val portInput = view.findViewById<EditText>(R.id.dlgPortInput)
        val cardInput = view.findViewById<EditText>(R.id.dlgCardInput)
        val deviceInput = view.findViewById<EditText>(R.id.dlgDeviceInput)
        serverInput.setText(prefs.licenseServer)
        portInput.setText(prefs.licensePort)
        cardInput.setText(prefs.licenseCard)
        deviceInput.setText(prefs.licenseDeviceId)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_add_card)
            .setView(view)
            .setNegativeButton(R.string.license_cancel, null)
            .setPositiveButton(R.string.license_verify) { _, _ ->
                verifyFromDialog(
                    serverInput.text?.toString().orEmpty(),
                    portInput.text?.toString().orEmpty(),
                    cardInput.text?.toString().orEmpty(),
                    deviceInput.text?.toString().orEmpty(),
                )
            }
            .show()
    }

    /** 查询绑定/解绑弹窗（输入卡密 → 展示绑定设备并可解绑） */
    private fun showQueryDialog() {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_bindings_query, null)
        val codeInput = view.findViewById<EditText>(R.id.dlgBindCodeInput)
        codeInput.setText(prefs.licenseCard)
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_query_bindings)
            .setView(view)
            .setNegativeButton(R.string.license_cancel, null)
            .setPositiveButton(R.string.license_query) { _, _ ->
                val code = codeInput.text?.toString()?.trim().orEmpty().uppercase()
                if (code.isBlank()) {
                    renderStatus("请填写要查询的卡密。")
                } else {
                    openBindingsByCode(code)
                }
            }
            .show()
    }

    private fun handleVerifyResponse(json: JSONObject) {
        if (json.optInt("status") != 0) {
            renderStatus("激活失败：" + licenseErrorText(json.optString("message")) + " (" + json.optString("error") + ")")
            return
        }
        val data = json.optJSONObject("data") ?: JSONObject()
        when (data.optString("status")) {
            "ok" -> {
                prefs.licenseToken = data.optString("session_token")
                prefs.licenseActive = true
                scriptState = data.optString("state").ifBlank { "idle" }
                updateAvailable = false
                updateScriptControls()
                val script = data.optJSONObject("script")
                val batch = data.optJSONObject("batch")
                val card = data.optJSONObject("card")
                val policy = if (batch?.optString("update_policy") == "force") "强制更新" else "确认更新"
                val expire = card?.optLong("expire_at") ?: 0L
                val expireText = if (expire <= 0L) {
                    "永久"
                } else {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(expire * 1000))
                }
                renderStatus(
                    "激活成功 ✓\n脚本：" + (script?.optString("name") ?: "-") +
                        "\n更新策略：" + policy +
                        "\n到期：" + expireText +
                        "\n运行状态：" + stateLabel(scriptState) +
                        (if (scriptState == "active") "" else "\n请点击「启动脚本」开始运行")
                )
                maybeStartOverlay()
                ensureServiceStarted()
                loadMyCards()
            }
            "pending_enroll" -> renderStatus("设备尚未接入服务器，请先让运营方在服务端完成设备接入。")
            else -> renderStatus("返回状态：" + data.optString("status"))
        }
    }

    // ---------------- 运行控制（启动 / 暂停 / 结束） ----------------

    private fun toggleScript() {
        if (prefs.licenseToken.isBlank()) {
            renderStatus("尚未激活。")
            return
        }
        val pausing = scriptState == "active"
        if (!pausing) {
            ensureServiceStarted()
        }
        runAction(if (pausing) "pause" else "resume", if (pausing) "已暂停脚本运行" else "脚本已启动")
    }

    private fun stopScript() {
        if (prefs.licenseToken.isBlank()) {
            renderStatus("尚未激活。")
            return
        }
        runAction("stop", "已结束脚本运行")
    }

    private fun runAction(name: String, okText: String) {
        renderStatus("正在执行…")
        executor.execute {
            val token = prefs.licenseToken
            val result = runCatching {
                when (name) {
                    "pause" -> LicenseApi.pause(baseUrl(), token)
                    "resume" -> LicenseApi.resume(baseUrl(), token)
                    else -> LicenseApi.stopRun(baseUrl(), token)
                }
            }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        renderStatus("操作失败：" + licenseErrorText(json.optString("message")))
                        return@onSuccess
                    }
                    val st = json.optJSONObject("data")?.optString("state").orEmpty()
                    if (st.isNotBlank()) {
                        scriptState = st
                    }
                    if (name == "resume") {
                        // 启动后把会话对齐到实际运行的卡（避免 token 停留在别的卡）
                        val newToken = json.optJSONObject("data")
                            ?.optString("session_token").orEmpty()
                        if (newToken.isNotBlank()) {
                            prefs.licenseToken = newToken
                        }
                    }
                    updateScriptControls()
                    loadMyCards()
                    renderStatus(okText + "（当前：" + stateLabel(scriptState) + "）")
                }.onFailure { renderStatus("请求失败：" + friendly(it)) }
            }
        }
    }

    // ---------------- 确认式更新 ----------------

    private fun acceptUpdate() {
        val token = prefs.licenseToken
        if (token.isBlank()) {
            renderStatus("尚未激活。")
            return
        }
        renderStatus("正在更新…")
        executor.execute {
            val result = runCatching { LicenseApi.acceptUpdate(baseUrl(), token) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        renderStatus("更新失败：" + json.optString("message"))
                        return@onSuccess
                    }
                    updateAvailable = false
                    updateScriptControls()
                    renderStatus("更新已提交，正在刷新状态…")
                    refresh()
                }.onFailure { renderStatus("请求失败：" + friendly(it)) }
            }
        }
    }

    // ---------------- 状态刷新 ----------------

    private fun refresh(silent: Boolean = false) {
        val token = prefs.licenseToken
        if (token.isBlank()) {
            if (!silent) {
                renderStatus("尚未激活。")
            }
            return
        }
        if (!silent) {
            renderStatus("正在刷新…")
        }
        executor.execute {
            val result = runCatching { LicenseApi.me(baseUrl(), token) }
            mainHandler.post {
                result.onSuccess { text ->
                    if (text.optInt("status") != 0) {
                        if (isInvalidSession(text.optString("message"))) {
                            resetLicenseState("本机已从该卡密解绑，请重新激活。")
                        } else {
                            renderStatus("刷新失败：" + text.optString("message"))
                        }
                        return@onSuccess
                    }
                    val d = text.optJSONObject("data") ?: JSONObject()
                    scriptState = d.optString("state").ifBlank { null }
                    prefs.licenseActive = d.optBoolean("active")
                    updateAvailable = d.optJSONObject("update")?.optBoolean("available") == true
                    updateScriptControls()
                    loadMyCards()
                    LamdaHealer.maybeHeal(prefs, d.optString("device_state"))
                    checkAppUpdate(d)
                    val update = d.optJSONObject("update")
                    val card = d.optJSONObject("card")
                    val updateText = if (update?.optBoolean("available") == true) {
                        "有可用更新 → " + update.optString("latest")
                    } else {
                        "已是最新"
                    }
                    renderStatus(
                        "激活：" + (if (d.optBoolean("active")) "是" else "否") +
                            " · 状态：" + d.optString("state") +
                            "\n运行状态：" + stateLabel(scriptState) +
                            "\n设备在线：" + d.optString("device_state") +
                            "\n脚本：" + (d.optJSONObject("script")?.optString("name") ?: "-") +
                            "\n口令：" + (card?.optString("code") ?: "-") +
                            "（" + (card?.optString("status") ?: "-") + "）" +
                            "\n更新：" + updateText +
                            (if (d.optBoolean("blacklisted")) "\n⚠ 该设备已被管理员拉黑，无法使用卡密或运行脚本" else "")
                    )
                }.onFailure { renderStatus("请求失败：" + friendly(it)) }
            }
        }
    }

    // ---------------- 悬浮窗 ----------------

    private fun onOverlaySwitch(checked: Boolean) {
        prefs.overlayEnabled = checked
        if (checked) {
            maybeStartOverlay()
        } else {
            OverlayService.stop(activity)
        }
    }

    private fun syncOverlaySwitch() {
        val b = binding ?: return
        if (b.licenseOverlaySwitch.isChecked != prefs.overlayEnabled) {
            b.licenseOverlaySwitch.setOnCheckedChangeListener(null)
            b.licenseOverlaySwitch.isChecked = prefs.overlayEnabled
            b.licenseOverlaySwitch.setOnCheckedChangeListener { _, checked -> onOverlaySwitch(checked) }
        }
        if (prefs.overlayEnabled && hasOverlayPermission() && !OverlayService.isRunning) {
            startOverlay()
        }
    }

    private fun maybeStartOverlay() {
        if (!prefs.overlayEnabled) {
            return
        }
        if (hasOverlayPermission()) {
            startOverlay()
        } else {
            requestOverlayPermission()
        }
    }

    private fun startOverlay() {
        requestNotificationPermissionIfNeeded()
        OverlayService.start(activity)
    }

    private fun hasOverlayPermission(): Boolean = Settings.canDrawOverlays(activity)

    private fun requestOverlayPermission() {
        waitingOverlayGrant = true
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + activity.packageName)
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { activity.startActivity(intent) }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) {
            return
        }
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        runCatching {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                61,
            )
        }
    }

    // ---------------- 我的卡密 / 绑定设备 / 解绑 ----------------

    private fun loadMyCards() {
        val b = binding ?: return
        val token = prefs.licenseToken
        if (token.isBlank()) {
            b.licenseMyCardsContainer.removeAllViews()
            return
        }
        executor.execute {
            val result = runCatching { LicenseApi.myCards(baseUrl(), token) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        if (isInvalidSession(json.optString("message"))) {
                            resetLicenseState("会话已失效，请重新激活。")
                        }
                        return@onSuccess
                    }
                    val list = json.optJSONObject("data")?.optJSONArray("data") ?: JSONArray()
                    renderMyCards(list)
                }
            }
        }
    }

    private fun renderMyCards(list: JSONArray) {
        val b = binding ?: return
        val container = b.licenseMyCardsContainer
        container.removeAllViews()
        val inflater = LayoutInflater.from(activity)
        for (i in 0 until list.length()) {
            val card = list.optJSONObject(i) ?: continue
            val row = inflater.inflate(R.layout.item_my_card, container, false)
            val masked = card.optString("code")
            val script = card.optJSONObject("script")?.optString("name") ?: "-"
            val state = card.optString("state")
            val cardStatus = card.optString("card_status")
            val active = card.optBoolean("active")
            row.findViewById<TextView>(R.id.myCardCode).text = masked
            val created = card.optLong("create_time")
            val createdText = if (created > 0) {
                SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
                    .format(Date(created * 1000))
            } else {
                ""
            }
            row.findViewById<TextView>(R.id.myCardMeta).text =
                script + " · " + stateLabel(state) +
                    " · " + (if (active) "当前启用" else "未启用") +
                    (if (cardStatus == "disabled") " · 卡密已停用" else "") +
                    (if (createdText.isNotEmpty()) " · 创建于 " + createdText else "")
            val switchBtn = row.findViewById<MaterialButton>(R.id.myCardSwitch)
            switchBtn.isEnabled = !active && cardStatus == "enable"
            switchBtn.text = activity.getString(
                if (active) R.string.license_current else R.string.license_switch
            )
            switchBtn.setOnClickListener {
                if (!active) {
                    switchToCard(card.optInt("card_id"), masked)
                }
            }
            row.findViewById<MaterialButton>(R.id.myCardDevices).setOnClickListener {
                openDevicesDialog(card.optInt("card_id"), masked)
            }
            container.addView(row)
        }
    }

    private fun switchToCard(cardId: Int, maskedCode: String) {
        val token = prefs.licenseToken
        if (token.isBlank() || cardId <= 0) {
            return
        }
        renderStatus("正在暂停旧脚本并切换到 " + maskedCode + " …")
        executor.execute {
            // 切换前先暂停当前脚本（撤销在途任务、退出旧组）；失败不阻塞切换
            runCatching { LicenseApi.pause(baseUrl(), token) }
            val result = runCatching { LicenseApi.switchCard(baseUrl(), token, cardId) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        val msg = json.optString("message")
                        if (isInvalidSession(msg)) {
                            resetLicenseState("会话已失效，请重新激活后再切换。")
                        } else {
                            renderStatus("切换失败：" + msg)
                        }
                        return@onSuccess
                    }
                    val data = json.optJSONObject("data")
                    val newToken = data?.optString("session_token").orEmpty()
                    if (newToken.isNotBlank()) {
                        // 关键：切换后更新会话 token，否则 /me、刷新状态仍指向旧卡密
                        prefs.licenseToken = newToken
                    }
                    prefs.licenseActive = true
                    scriptState = data?.optString("state").orEmpty().ifBlank { "idle" }
                    updateAvailable = false
                    updateScriptControls()
                    renderStatus("已暂停旧脚本并切换到 " + maskedCode +
                        "\n请点击「启动脚本」开始运行")
                    loadMyCards()
                    ensureServiceStarted()
                }.onFailure { renderStatus("切换失败：" + friendly(it)) }
            }
        }
    }

    private fun openDevicesDialog(cardId: Int, maskedCode: String) {
        val token = prefs.licenseToken
        if (token.isBlank() || cardId <= 0) {
            return
        }
        renderStatus("正在查询绑定设备…")
        executor.execute {
            val result = runCatching { LicenseApi.cardDevices(baseUrl(), token, cardId) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        renderStatus("查询失败：" + json.optString("message"))
                        return@onSuccess
                    }
                    renderStatus("")
                    showDevicesDialog(json.optJSONObject("data") ?: JSONObject()) { devId ->
                        doUnbind(cardId, devId)
                    }
                }.onFailure { renderStatus("查询失败：" + friendly(it)) }
            }
        }
    }

    private fun openBindingsByCode(code: String) {
        renderStatus("正在查询绑定设备…")
        executor.execute {
            val result = runCatching {
                LicenseApi.cardBindingsByCode(baseUrl(), code, prefs.licenseDeviceId)
            }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        renderStatus("查询失败：" + json.optString("message"))
                        return@onSuccess
                    }
                    renderStatus("")
                    showDevicesDialog(json.optJSONObject("data") ?: JSONObject()) { devId ->
                        doUnbindByCode(code, devId)
                    }
                }.onFailure { renderStatus("查询失败：" + friendly(it)) }
            }
        }
    }

    private fun doUnbindByCode(code: String, devId: String) {
        renderStatus("正在解绑…")
        executor.execute {
            val result = runCatching { LicenseApi.unbindByCode(baseUrl(), code, devId) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        val msg = json.optString("message")
                        if (isInvalidSession(msg)) {
                            resetLicenseState("会话已失效，请重新激活。")
                        } else {
                            renderStatus(unbindErrorText(msg))
                        }
                        return@onSuccess
                    }
                    renderStatus("解绑成功，可重新点击「校验并激活」")
                    loadMyCards()
                }.onFailure { renderStatus("解绑失败：" + friendly(it)) }
            }
        }
    }

    private fun showDevicesDialog(data: JSONObject, onUnbind: (String) -> Unit) {
        val view = LayoutInflater.from(activity).inflate(R.layout.dialog_card_devices, null)
        val header = view.findViewById<TextView>(R.id.cardDevicesHeader)
        val container = view.findViewById<LinearLayout>(R.id.cardDevicesContainer)
        val quota = data.optJSONObject("quota") ?: JSONObject()
        val devices = data.optJSONArray("devices") ?: JSONArray()
        val totalLimit = quota.optInt("total_limit")
        val dailyLimit = quota.optInt("daily_limit")
        val interval = quota.optInt("interval")
        header.text = data.optString("code") +
            "\n解绑配额：总 " + quota.optInt("total_used") + "/" +
            (if (totalLimit > 0) totalLimit.toString() else "∞") +
            " · 今日 " + quota.optInt("day_used") + "/" +
            (if (dailyLimit > 0) dailyLimit.toString() else "∞") +
            (if (interval > 0) " · 间隔 " + interval + "s" else "")
        val inflater = LayoutInflater.from(activity)
        for (i in 0 until devices.length()) {
            val d = devices.optJSONObject(i) ?: continue
            val row = inflater.inflate(R.layout.item_card_device, container, false)
            val devId = d.optString("dev_id")
            val domain = d.optString("domain")
            val self = d.optBoolean("is_self")
            row.findViewById<TextView>(R.id.cardDeviceName).text =
                (if (domain.isNotBlank()) domain else devId.take(8) + "…") +
                    (if (self) "（本机）" else "")
            row.findViewById<TextView>(R.id.cardDeviceMeta).text =
                (if (d.optString("device_state") == "online") "在线" else "离线") +
                    " · " + d.optString("state") +
                    " · " + devId.take(12)
            row.findViewById<MaterialButton>(R.id.cardDeviceUnbind).setOnClickListener {
                confirmUnbind(devId, domain.ifBlank { devId.take(8) }, onUnbind)
            }
            container.addView(row)
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_devices_title)
            .setView(view)
            .setPositiveButton(R.string.license_close, null)
            .show()
    }

    private fun confirmUnbind(devId: String, label: String, onUnbind: (String) -> Unit) {
        MaterialAlertDialogBuilder(activity)
            .setTitle(R.string.license_unbind_title)
            .setMessage("确认将 " + label + " 从该卡密解绑？\n（会占用一次解绑次数）")
            .setNegativeButton(R.string.license_cancel, null)
            .setPositiveButton(R.string.license_unbind_btn) { _, _ -> onUnbind(devId) }
            .show()
    }

    private fun doUnbind(cardId: Int, devId: String) {
        val token = prefs.licenseToken
        if (token.isBlank()) {
            return
        }
        renderStatus("正在解绑…")
        executor.execute {
            val result = runCatching { LicenseApi.unbind(baseUrl(), token, cardId, devId) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") != 0) {
                        val msg = json.optString("message")
                        if (isInvalidSession(msg)) {
                            resetLicenseState("会话已失效，请重新激活。")
                        } else {
                            renderStatus(unbindErrorText(msg))
                        }
                        return@onSuccess
                    }
                    renderStatus("解绑成功")
                    loadMyCards()
                    refresh()
                }.onFailure { renderStatus("解绑失败：" + friendly(it)) }
            }
        }
    }

    private fun unbindErrorText(raw: String): String = when {
        raw.contains("unbind_total_limit_reached") -> "解绑失败：已达总解绑次数上限"
        raw.contains("unbind_daily_limit_reached") -> "解绑失败：今日解绑次数已用完"
        raw.contains("unbind_interval_not_reached") -> "解绑失败：距上次解绑未满间隔，请稍后再试"
        raw.contains("binding_not_exist") -> "解绑失败：该设备未绑定此卡密"
        else -> "解绑失败：" + raw
    }

    private fun isInvalidSession(message: String?): Boolean =
        message?.contains("Invalid license session") == true

    private fun resetLicenseState(message: String) {
        prefs.licenseToken = ""
        prefs.licenseActive = false
        scriptState = null
        updateAvailable = false
        updateScriptControls()
        loadMyCards()
        renderStatus(message)
    }

    // ---------------- 本地 lamda 服务 ----------------

    /** 激活成功 / 启动脚本时，确保设备端 lamda 在运行（脚本执行与远控的前提） */
    private fun ensureServiceStarted() {
        executor.execute {
            runCatching { ensureLamdaRunning() }
            runCatching { keepScreenAwake() }
        }
    }

    /** 屏幕常亮：避免熄屏后 lamda 按键注入失败（injectInputEvent failed） */
    private fun keepScreenAwake() {
        runCatching {
            Runtime.getRuntime().exec(
                arrayOf(
                    "su", "-c",
                    "svc power stayon true; settings put system screen_off_timeout 2147483647"
                )
            ).waitFor()
        }
    }

    /** App 更新提示：后台版本高于当前版本时提示前往下载（同版本只提示一次） */
    private fun checkAppUpdate(d: JSONObject) {
        val app = d.optJSONObject("app") ?: return
        val version = app.optString("version").trim()
        val url = app.optString("url").trim()
        if (version.isBlank() || url.isBlank()) {
            return
        }
        // 语义化比较：仅当后台版本 > 当前版本才提示（git-describe 版本号如 1.3-10-gxxx 也能正确比较）
        if (versionCompare(version, BuildConfig.VERSION_NAME) <= 0) {
            return
        }
        if (prefs.skippedAppVersion == version) {
            return
        }
        prefs.skippedAppVersion = version
        mainHandler.post {
            val note = app.optString("note").trim()
            val cur = BuildConfig.VERSION_NAME.substringBefore("-")
            MaterialAlertDialogBuilder(activity)
                .setTitle(R.string.app_update_title)
                .setMessage(
                    "发现新版本 v" + version + "（当前 v" + cur + "）" +
                        (if (note.isNotBlank()) "\n" + note else "") +
                        "\n\n是否前往下载更新？"
                )
                .setNegativeButton(R.string.license_cancel, null)
                .setPositiveButton(R.string.app_update_go) { _, _ ->
                    runCatching {
                        activity.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url))
                        )
                    }
                }
                .show()
        }
    }

    /** 卡密错误中文化：拉黑 / 防爆破锁定等 */
    private fun licenseErrorText(raw: String): String {
        if (raw.contains("device_blacklisted")) {
            return "该设备已被管理员拉黑，无法使用卡密或运行脚本"
        }
        if (raw.contains("verify_locked_retry_in_")) {
            val secs = raw.substringAfter("verify_locked_retry_in_")
                .takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            val wait = if (secs >= 60) "约 " + ((secs + 59) / 60) + " 分钟" else secs.toString() + " 秒"
            return "口令错误次数过多，请 " + wait + "后再试"
        }
        return raw
    }

    /** 版本号数字段比较：a>b 返回 1，相等 0，a<b 返回 -1 */
    private fun versionCompare(a: String, b: String): Int {
        val x = Regex("\\d+").findAll(a).map { it.value.toInt() }.toList()
        val y = Regex("\\d+").findAll(b).map { it.value.toInt() }.toList()
        val n = maxOf(x.size, y.size)
        for (i in 0 until n) {
            val xi = x.getOrElse(i) { 0 }
            val yi = y.getOrElse(i) { 0 }
            if (xi != yi) {
                return if (xi > yi) 1 else -1
            }
        }
        return 0
    }

    private fun friendly(t: Throwable): String = t.message ?: t.javaClass.simpleName
}
