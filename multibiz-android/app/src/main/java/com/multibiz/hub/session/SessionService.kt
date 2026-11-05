package com.multibiz.hub.session

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.webkit.CookieManager
import android.webkit.WebStorage
import com.multibiz.hub.App
import com.multibiz.hub.core.Notifier

/**
 * Runs inside an account process. The main process binds to it (BIND_IMPORTANT),
 * which keeps this process - and its live WebView session - alive in the background.
 */
abstract class SessionService : Service() {

    companion object {
        const val ACTION_WIPE = "com.multibiz.hub.action.WIPE"
    }

    private val binder = Binder()

    override fun onBind(intent: Intent?): IBinder {
        SessionManager.ensure(this)
        SessionManager.republishStatus()
        return binder
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_WIPE) wipe() else SessionManager.ensure(this)
        return START_NOT_STICKY
    }

    /** Account removed: destroy session, clear all cookies/storage, then kill this process. */
    private fun wipe() {
        SessionManager.destroy()
        Notifier.cancelAll(this, App.slot, all = true)
        com.multibiz.hub.core.Avatars.delete(this, App.slot)
        val main = Handler(Looper.getMainLooper())
        val finish = Runnable {
            stopSelf()
            Process.killProcess(Process.myPid())
        }
        try {
            WebStorage.getInstance().deleteAllData()
            CookieManager.getInstance().removeAllCookies {
                CookieManager.getInstance().flush()
                main.postDelayed(finish, 1_500)
            }
            main.postDelayed(finish, 6_000) // safety net
        } catch (_: Exception) {
            main.post(finish)
        }
    }
}

class SessionService1 : SessionService()
class SessionService2 : SessionService()
class SessionService3 : SessionService()
class SessionService4 : SessionService()
class SessionService5 : SessionService()
