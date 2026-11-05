package com.multibiz.hub.core

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.multibiz.hub.R

/**
 * Every account has its OWN notification channel (named after the account),
 * so the user can even set a different ringtone per business in Android settings.
 * Every notification shows the account name in the title, header (sub-text),
 * account color and an initials badge - impossible to confuse accounts.
 */
object Notifier {
    const val CH_SERVICE = "service"
    const val CH_ALERTS = "alerts"
    private const val GROUP_ACCOUNTS = "accounts"
    const val ID_SERVICE = 1
    const val EXTRA_JS_ID = "com.multibiz.hub.extra.JS_NOTIFICATION_ID"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    fun ensureBaseChannels(ctx: Context) {
        val nm = nm(ctx)
        nm.createNotificationChannelGroup(NotificationChannelGroup(GROUP_ACCOUNTS, "Business accounts"))
        nm.createNotificationChannel(
            NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Keeps accounts connected so messages arrive instantly"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERTS, "Account alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Logged-out / re-link required warnings"
            }
        )
    }

    fun ensureAccountChannel(ctx: Context, a: Account) {
        nm(ctx).createNotificationChannel(
            NotificationChannel(a.channelId, a.name, NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Messages for ${a.name}"
                group = GROUP_ACCOUNTS
                enableLights(true)
                lightColor = a.color
                enableVibration(true)
                setShowBadge(true)
            }
        )
    }

    fun deleteAccountChannel(ctx: Context, a: Account) {
        try { nm(ctx).deleteNotificationChannel(a.channelId) } catch (_: Exception) {}
    }

    private fun base(slot: Int) = slot * 10_000
    private fun summaryId(slot: Int) = base(slot)
    private fun genericId(slot: Int) = base(slot) + 1
    private fun loggedOutId(slot: Int) = base(slot) + 2
    fun messageId(slot: Int, tag: String) = base(slot) + 100 + ((tag.hashCode() and 0x7fffffff) % 9_000)

    private fun openIntent(ctx: Context, a: Account, reqCode: Int, jsId: String?): PendingIntent {
        val i = Intent(ctx, Slots.activity(a.slot))
            .setAction("com.multibiz.hub.OPEN.$reqCode")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (jsId != null) i.putExtra(EXTRA_JS_ID, jsId)
        return PendingIntent.getActivity(
            ctx, reqCode, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    @SuppressLint("MissingPermission")
    private fun post(ctx: Context, id: Int, n: android.app.Notification) {
        try { NotificationManagerCompat.from(ctx).notify(id, n) } catch (_: SecurityException) {}
    }

    private fun builder(ctx: Context, a: Account, channel: String = a.channelId) =
        NotificationCompat.Builder(ctx, channel)
            .setSmallIcon(R.drawable.ic_stat_chat)
            .setLargeIcon(Badge.icon(ctx, a))
            .setColor(a.color)
            .setSubText(a.name)
            .setShowWhen(true)
            .setWhen(System.currentTimeMillis())

    fun message(ctx: Context, a: Account, sender: String, body: String, tag: String, jsId: String, silent: Boolean) {
        ensureAccountChannel(ctx, a)
        val key = tag.ifBlank { sender }
        val id = messageId(a.slot, key)
        val title = "${a.name} • $sender"
        val n = builder(ctx, a)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body).setSummaryText(a.name))
            .setTicker("${a.name}: $sender")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(group(a))
            .setAutoCancel(true)
            .setSilent(silent)
            .setContentIntent(openIntent(ctx, a, id, jsId))
            .build()
        post(ctx, id, n)
        postSummary(ctx, a)
    }

    private fun group(a: Account) = "com.multibiz.hub.acc.${a.slot}"

    private fun postSummary(ctx: Context, a: Account) {
        val n = builder(ctx, a)
            .setContentTitle(a.name)
            .setContentText("New messages in ${a.name}")
            .setStyle(NotificationCompat.InboxStyle().setSummaryText(a.name))
            .setGroup(group(a))
            .setGroupSummary(true)
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, a, summaryId(a.slot), null))
            .build()
        post(ctx, summaryId(a.slot), n)
    }

    /** Fallback when the page's unread counter goes up but no explicit notification arrived. */
    fun generic(ctx: Context, a: Account, unread: Int) {
        ensureAccountChannel(ctx, a)
        val n = builder(ctx, a)
            .setContentTitle("${a.name} • New message")
            .setContentText(if (unread == 1) "1 unread chat" else "$unread unread chats")
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup(group(a))
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(openIntent(ctx, a, genericId(a.slot), null))
            .build()
        post(ctx, genericId(a.slot), n)
        postSummary(ctx, a)
    }

    fun loggedOut(ctx: Context, a: Account) {
        val n = builder(ctx, a, CH_ALERTS)
            .setContentTitle("${a.name} • Logged out")
            .setContentText("This account needs to be linked again. Tap to scan the QR code.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("This account needs to be linked again. Tap to open and re-link it from the WhatsApp Business phone (Linked devices)."))
            .setAutoCancel(true)
            .setContentIntent(openIntent(ctx, a, loggedOutId(a.slot), null))
            .build()
        post(ctx, loggedOutId(a.slot), n)
    }

    fun cancelTag(ctx: Context, slot: Int, tag: String) {
        if (tag.isBlank()) return
        nm(ctx).cancel(messageId(slot, tag))
        cleanupSummary(ctx, slot)
    }

    private fun cleanupSummary(ctx: Context, slot: Int) {
        val nm = nm(ctx)
        val range = base(slot) + 1 until base(slot) + 10_000
        val anyLeft = nm.activeNotifications.any { it.id in range && it.id != loggedOutId(slot) }
        if (!anyLeft) nm.cancel(summaryId(slot))
    }

    /** Clears message notifications of one account (keeps the logged-out warning unless [all]). */
    fun cancelAll(ctx: Context, slot: Int, all: Boolean = false) {
        val nm = nm(ctx)
        val range = base(slot) until base(slot) + 10_000
        nm.activeNotifications
            .filter { it.id in range && (all || it.id != loggedOutId(slot)) }
            .forEach { nm.cancel(it.id) }
    }

    fun cancelLoggedOut(ctx: Context, slot: Int) = nm(ctx).cancel(loggedOutId(slot))
}
