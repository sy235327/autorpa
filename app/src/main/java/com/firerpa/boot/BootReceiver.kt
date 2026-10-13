package com.firerpa.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.firerpa.service.NativeServiceManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) {
            return
        }

        Thread {
            NativeServiceManager(context.applicationContext).autoStartIfEnabled()
        }.start()
    }
}
