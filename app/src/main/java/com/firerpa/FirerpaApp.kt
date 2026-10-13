package com.firerpa

import android.app.Application
import com.firerpa.i18n.LocaleManager
import com.firerpa.runtime.RootAccess
import com.firerpa.runtime.ShizukuAccess
import com.firerpa.security.FirerpaCertificateManager

class FirerpaApp : Application() {
    override fun onCreate() {
        super.onCreate()
        LocaleManager.applySavedLocale(this)
        Thread {
            runCatching { FirerpaCertificateManager.ensureGenerated(this) }
            RootAccess.probeOnce()
            if (ShizukuAccess.canUseShell()) {
                ShizukuAccess.refreshShellProbe(force = true)
            }
        }.start()
    }
}
