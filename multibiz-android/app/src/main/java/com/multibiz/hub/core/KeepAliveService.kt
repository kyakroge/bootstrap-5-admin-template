package com.multibiz.hub.core

import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.multibiz.hub.R
import com.multibiz.hub.ui.MainActivity

/**
 * Single foreground service (one silent ongoing notification) in the main process.
 * It binds to every account's SessionService with BIND_IMPORTANT, which raises the
 * priority of all 5 account processes so Android keeps them (and their live
 * WhatsApp Web sockets) running in the background. If an account process dies,
 * the binding automatically recreates it.
 */
class KeepAliveService : Service() {

    companion object {
        fun start(ctx: Context) {
            if (AccountStore.accounts(ctx).isEmpty()) {
                ctx.stopService(Intent(ctx, KeepAliveService::class.java))
                return
            }
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, KeepAliveService::class.java))
            } catch (_: Exception) {
                // Background start not allowed right now; will start next time the UI opens.
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private val conns = HashMap<Int, Conn>()
    private val statusListener: () -> Unit = { updateNotification() }

    private inner class Conn(val slot: Int) : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {}
        override fun onServiceDisconnected(name: ComponentName?) {
            // Process died; Android re-creates it automatically because of BIND_AUTO_CREATE.
        }
        override fun onBindingDied(name: ComponentName?) {
            unbind(slot)
            main.postDelayed({ sync() }, 1_000)
        }
        override fun onNullBinding(name: ComponentName?) {}
    }

    override fun onCreate() {
        super.onCreate()
        StatusBus.addListener(statusListener)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        sync()
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ServiceCompat.startForeground(
            this, Notifier.ID_SERVICE, buildNotification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        )
        true
    } catch (_: Exception) {
        false
    }

    private fun sync() {
        val slots = AccountStore.accounts(this).map { it.slot }.toSet()
        (conns.keys - slots).forEach { unbind(it) }
        (slots - conns.keys).forEach { bind(it) }
        if (slots.isEmpty()) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        } else {
            updateNotification()
        }
    }

    private fun bind(slot: Int) {
        val c = Conn(slot)
        val ok = try {
            bindService(Intent(this, Slots.service(slot)), c, Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
        } catch (_: Exception) { false }
        if (ok) conns[slot] = c else try { unbindService(c) } catch (_: Exception) {}
    }

    private fun unbind(slot: Int) {
        conns.remove(slot)?.let { try { unbindService(it) } catch (_: Exception) {} }
    }

    private fun buildNotification(): android.app.Notification {
        val accounts = AccountStore.accounts(this)
        val unread = StatusBus.totalUnread()
        val names = accounts.joinToString(", ") { a ->
            val s = StatusBus.get(a.slot)
            val u = if (s.unread > 0) " (${s.unread})" else ""
            val warn = if (s.state == StatusBus.STATE_QR) " ⚠" else ""
            "${a.name}$u$warn"
        }
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, Notifier.CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setContentTitle(
                if (unread > 0) "${accounts.size} accounts active • $unread unread"
                else "${accounts.size} accounts active"
            )
            .setContentText(names.ifEmpty { "No accounts" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(names))
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(pi)
            .build()
    }

    private fun updateNotification() {
        if (conns.isEmpty()) return
        try {
            getSystemService(android.app.NotificationManager::class.java)
                .notify(Notifier.ID_SERVICE, buildNotification())
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        StatusBus.removeListener(statusListener)
        conns.keys.toList().forEach { unbind(it) }
        super.onDestroy()
    }
}
