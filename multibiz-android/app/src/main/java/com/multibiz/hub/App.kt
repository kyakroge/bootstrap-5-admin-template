package com.multibiz.hub

import android.app.Application
import android.webkit.WebView
import com.multibiz.hub.core.Notifier
import com.multibiz.hub.core.Slots
import com.multibiz.hub.core.StatusBus

class App : Application() {

    companion object {
        /** 0 = main (dashboard) process, 1..5 = account process. */
        @Volatile
        var slot: Int = 0
            private set
    }

    override fun onCreate() {
        super.onCreate()
        slot = Slots.slotOfProcess(getProcessName())

        // MUST happen before any WebView is touched in this process.
        // Each account gets its own data directory => fully isolated login.
        WebView.setDataDirectorySuffix(if (slot > 0) Slots.suffix(slot) else "main_process")

        Notifier.ensureBaseChannels(this)
        if (slot == 0) {
            StatusBus.register(this)
        }
    }
}
