package com.firerpa.license

import com.firerpa.data.AppPrefs

/**
 * [fork] 设备离线自愈：
 * server 判定设备 offline（典型原因：lamda 的 cron/heartbeat 线程卡死
 * "maximum concurrent instance reached"）时，自动重启设备端 lamda
 * （kill 全部 lamda 进程 + launch.sh 拉起）。
 * 保守策略：3 分钟内最多尝试一次，避免反复重启。
 */
object LamdaHealer {
    private const val MIN_INTERVAL_MS = 3 * 60 * 1000L

    @Volatile
    private var lastAttemptMs = 0L

    fun maybeHeal(prefs: AppPrefs, deviceState: String?) {
        if (deviceState != "offline") {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastAttemptMs < MIN_INTERVAL_MS) {
            return
        }
        lastAttemptMs = now
        runCatching {
            val root = "/data/" + prefs.ensureDirectoryPrefix()
            val cmd = "for p in ${'$'}(ps -A -o PID,NAME | grep -w lamda | grep -v grep | awk '{print ${'$'}1}'); do kill -9 ${'$'}p; done; " +
                "sleep 2; mkdir -p $root/usr; export USRDIR=$root/usr; " +
                "nohup /system/bin/sh $root/server/bin/launch.sh --logfile=$root/server.log >/dev/null 2>&1 &"
            Runtime.getRuntime().exec(arrayOf("su", "-c", cmd))
        }
    }
}
