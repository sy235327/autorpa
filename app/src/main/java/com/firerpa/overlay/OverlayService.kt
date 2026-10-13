package com.firerpa.overlay

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.firerpa.MainActivity
import com.firerpa.R
import com.firerpa.data.AppPrefs
import com.firerpa.license.LamdaHealer
import com.firerpa.license.LicenseApi
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * [fork] 悬浮窗脚本控制（图标模式）：
 *  - 收起时：只显示一个小圆形图标（可拖动）
 *  - 点击圆点：展开 [启动/暂停] [结束脚本] [回到主页] 三个图标按钮
 *  - 长按圆点：关闭悬浮窗（同步关闭 app 内开关）
 * 通过前台服务保活。
 */
class OverlayService : Service() {

    companion object {
        private const val REFRESH_INTERVAL_MS = 10_000L

        @Volatile
        var isRunning = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, OverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, OverlayService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private lateinit var prefs: AppPrefs
    private var panelView: View? = null
    private var actionsView: View? = null
    private var toggleView: ImageView? = null
    private var stopView: ImageView? = null
    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var currentState: String? = null

    private var expanded = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(applicationContext)
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForeground()
        if (panelView == null) {
            showPanel()
        }
        if (panelView != null) {
            isRunning = true
            refreshState()
            mainHandler.removeCallbacks(refreshRunnable)
            mainHandler.postDelayed(refreshRunnable, REFRESH_INTERVAL_MS)
        }
        return START_STICKY
    }

    /** 定时刷新状态（避免服务端状态变化后悬浮窗显示过时） */
    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshState()
            mainHandler.postDelayed(this, REFRESH_INTERVAL_MS)
        }
    }

    override fun onDestroy() {
        isRunning = false
        mainHandler.removeCallbacks(refreshRunnable)
        panelView?.let { view -> runCatching { windowManager.removeView(view) } }
        panelView = null
        runCatching { stopForeground(Service.STOP_FOREGROUND_REMOVE) }
        super.onDestroy()
    }

    private fun ensureForeground() {
        val channelId = "firerpa.overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(channelId) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(
                        channelId,
                        getString(R.string.overlay_channel_name),
                        NotificationManager.IMPORTANCE_LOW,
                    )
                )
            }
        }
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_nav_license)
            .setContentTitle(getString(R.string.overlay_notification_title))
            .setContentText(getString(R.string.overlay_notification_text))
            .setOngoing(true)
            .build()
        startForeground(1001, notification)
    }

    private fun showPanel() {
        val view = LayoutInflater.from(this).inflate(R.layout.overlay_panel, null)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 220
        }

        actionsView = view.findViewById(R.id.overlayActions)
        toggleView = view.findViewById(R.id.overlayToggle)
        stopView = view.findViewById(R.id.overlayStop)

        view.findViewById<View>(R.id.overlayToggle).setOnClickListener {
            setExpanded(false)
            toggleScript()
        }
        view.findViewById<View>(R.id.overlayStop).setOnClickListener {
            setExpanded(false)
            stopScript()
        }
        view.findViewById<View>(R.id.overlayHome).setOnClickListener {
            setExpanded(false)
            openMainPage()
        }

        view.setOnTouchListener(
            TapDragTouchListener(
                params,
                onMove = { runCatching { windowManager.updateViewLayout(view, params) } },
                onTap = { setExpanded(!expanded) },
            )
        )

        val added = runCatching { windowManager.addView(view, params) }.isSuccess
        if (!added) {
            toast("悬浮窗权限未授予")
            stopSelf()
            return
        }
        panelView = view
        setExpanded(false)
        renderPanel(null, authorized = prefs.licenseToken.isNotBlank())
    }

    private fun setExpanded(value: Boolean) {
        expanded = value
        actionsView?.visibility = if (value) View.VISIBLE else View.GONE
        panelView?.setBackgroundResource(if (value) R.drawable.overlay_bg else 0)
    }

    private inner class TapDragTouchListener(
        private val params: WindowManager.LayoutParams,
        private val onMove: () -> Unit,
        private val onTap: () -> Unit,
    ) : View.OnTouchListener {
        private var startX = 0
        private var startY = 0
        private var touchX = 0f
        private var touchY = 0f
        private var moved = false

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = params.x
                    startY = params.y
                    touchX = event.rawX
                    touchY = event.rawY
                    moved = false
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - touchX
                    val dy = event.rawY - touchY
                    if (!moved && (abs(dx) > 12f || abs(dy) > 12f)) {
                        moved = true
                    }
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        onMove()
                    }
                    return true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (event.actionMasked == MotionEvent.ACTION_UP && !moved) {
                        onTap()
                    }
                    return true
                }
            }
            return false
        }
    }

    private fun baseUrl(): String = LicenseApi.baseUrl(prefs.licenseServer, prefs.licensePort)

    private fun refreshState() {
        val token = prefs.licenseToken
        if (token.isBlank()) {
            renderPanel(null, authorized = false)
            return
        }
        executor.execute {
            val result = runCatching { LicenseApi.me(baseUrl(), token) }
            mainHandler.post {
                result.onSuccess { json ->
                    val state = json.optJSONObject("data")?.optString("state").orEmpty()
                    currentState = state.ifBlank { null }
                    renderPanel(currentState, authorized = true)
                    // 设备离线自愈（lamda 卡死时自动重启）
                    LamdaHealer.maybeHeal(prefs, json.optJSONObject("data")?.optString("device_state"))
                }.onFailure {
                    renderPanel(currentState, authorized = true)
                }
            }
        }
    }

    private fun renderPanel(state: String?, authorized: Boolean) {
        val toggle = toggleView ?: return
        val stopBtn = stopView ?: return
        if (!authorized) {
            toggle.isEnabled = false
            stopBtn.isEnabled = false
            toggle.alpha = 0.35f
            stopBtn.alpha = 0.35f
            toggle.setImageResource(R.drawable.ic_overlay_play)
            toggle.contentDescription = getString(R.string.overlay_state_inactive)
            return
        }
        val running = state == "active"
        toggle.isEnabled = state != "kicked"
        stopBtn.isEnabled = running || state == "paused"
        toggle.alpha = if (toggle.isEnabled) 1f else 0.35f
        stopBtn.alpha = if (stopBtn.isEnabled) 1f else 0.35f
        toggle.setImageResource(
            if (running) R.drawable.ic_overlay_pause else R.drawable.ic_overlay_play
        )
        toggle.contentDescription = getString(
            if (running) R.string.license_script_pause else R.string.license_script_start
        )
    }

    private fun toggleScript() {
        val token = prefs.licenseToken
        if (token.isBlank()) {
            toast(getString(R.string.overlay_state_inactive))
            return
        }
        val pausing = currentState == "active"
        executor.execute {
            val result = runCatching {
                if (pausing) {
                    LicenseApi.pause(baseUrl(), token)
                } else {
                    LicenseApi.resume(baseUrl(), token)
                }
            }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") == 0) {
                        val st = json.optJSONObject("data")?.optString("state").orEmpty()
                        if (st.isNotBlank()) {
                            currentState = st
                        }
                        renderPanel(currentState, authorized = true)
                        toast(if (pausing) "脚本已暂停" else "脚本已启动")
                    } else {
                        toast("操作失败：" + json.optString("message"))
                    }
                }.onFailure { toast("请求失败：" + (it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

    private fun stopScript() {
        val token = prefs.licenseToken
        if (token.isBlank()) {
            toast(getString(R.string.overlay_state_inactive))
            return
        }
        executor.execute {
            val result = runCatching { LicenseApi.stopRun(baseUrl(), token) }
            mainHandler.post {
                result.onSuccess { json ->
                    if (json.optInt("status") == 0) {
                        currentState = json.optJSONObject("data")?.optString("state") ?: "stopped"
                        renderPanel(currentState, authorized = true)
                        toast("脚本已结束")
                    } else {
                        toast("操作失败：" + json.optString("message"))
                    }
                }.onFailure { toast("请求失败：" + (it.message ?: it.javaClass.simpleName)) }
            }
        }
    }

    private fun openMainPage() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
            )
        }
        runCatching { startActivity(intent) }
    }

    private fun toast(message: String) {
        runCatching { Toast.makeText(this, message, Toast.LENGTH_SHORT).show() }
    }
}
