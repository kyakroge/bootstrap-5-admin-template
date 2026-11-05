package com.multibiz.hub.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Account processes -> main process status channel (unread count + login state).
 * Uses package-restricted, non-exported broadcasts: other apps can neither send nor read them.
 */
object StatusBus {
    private const val ACTION = "com.multibiz.hub.action.STATUS"
    private const val EXTRA_SLOT = "slot"
    private const val EXTRA_UNREAD = "unread"
    private const val EXTRA_STATE = "state"
    private const val EXTRA_AVATAR = "avatar"

    const val STATE_UNKNOWN = "unknown"
    const val STATE_LOADING = "loading"
    const val STATE_QR = "qr"
    const val STATE_READY = "ready"
    const val STATE_OFFLINE = "offline"

    data class Status(val unread: Int = 0, val state: String = STATE_UNKNOWN, val avatarVersion: Int = 0)

    private val map = ConcurrentHashMap<Int, Status>()
    private val listeners = CopyOnWriteArraySet<() -> Unit>()
    private val main = Handler(Looper.getMainLooper())

    fun get(slot: Int): Status = map[slot] ?: Status()
    fun totalUnread(): Int = map.values.sumOf { it.unread }
    fun clear(slot: Int) { map.remove(slot); dispatch() }

    fun addListener(l: () -> Unit) { listeners.add(l) }
    fun removeListener(l: () -> Unit) { listeners.remove(l) }

    private fun dispatch() = main.post { listeners.forEach { it() } }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val slot = intent.getIntExtra(EXTRA_SLOT, 0)
            if (slot !in 1..Slots.MAX) return
            val old = get(slot)
            val unread = intent.getIntExtra(EXTRA_UNREAD, -1).let { if (it < 0) old.unread else it }
            val state = intent.getStringExtra(EXTRA_STATE) ?: old.state
            val avatarChanged = intent.getBooleanExtra(EXTRA_AVATAR, false)
            map[slot] = Status(unread, state, old.avatarVersion + if (avatarChanged) 1 else 0)
            if (avatarChanged) Housekeeping.syncShortcuts(context)
            dispatch()
        }
    }

    /** Main process only. */
    fun register(ctx: Context) {
        ContextCompat.registerReceiver(
            ctx.applicationContext, receiver, IntentFilter(ACTION), ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }

    /** Called from an account process. */
    fun send(ctx: Context, slot: Int, unread: Int?, state: String?, avatarChanged: Boolean = false) {
        val i = Intent(ACTION).setPackage(ctx.packageName).putExtra(EXTRA_SLOT, slot)
        if (avatarChanged) i.putExtra(EXTRA_AVATAR, true)
        if (unread != null) i.putExtra(EXTRA_UNREAD, unread)
        if (state != null) i.putExtra(EXTRA_STATE, state)
        ctx.sendBroadcast(i)
    }
}
