package com.multibiz.hub.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Reconnects all accounts automatically after reboot / app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> KeepAliveService.start(context)
        }
    }
}
