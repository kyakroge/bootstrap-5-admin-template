package com.multibiz.hub.core

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.multibiz.hub.session.SessionService
import java.io.File

/** Account lifecycle operations. Main process only. */
object Housekeeping {

    fun onAccountAdded(ctx: Context, a: Account) {
        purgeSlotData(ctx, a.slot)
        Notifier.ensureAccountChannel(ctx, a)
        syncShortcuts(ctx)
        KeepAliveService.start(ctx)
    }

    fun onAccountChanged(ctx: Context, a: Account) {
        Notifier.ensureAccountChannel(ctx, a) // renames the channel too
        syncShortcuts(ctx)
        KeepAliveService.start(ctx)
    }

    fun removeAccount(ctx: Context, a: Account) {
        AccountStore.remove(ctx, a.slot)
        Notifier.cancelAll(ctx, a.slot, all = true)
        Notifier.deleteAccountChannel(ctx, a)
        StatusBus.clear(a.slot)
        Avatars.delete(ctx, a.slot)
        syncShortcuts(ctx)
        KeepAliveService.start(ctx) // unbinds the removed slot (or stops if none left)
        // Ask the account process to log out locally, wipe its storage and exit.
        try {
            ctx.startService(Intent(ctx, Slots.service(a.slot)).setAction(SessionService.ACTION_WIPE))
        } catch (_: Exception) {}
    }

    private fun isSlotProcessRunning(ctx: Context, slot: Int): Boolean {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val name = ctx.packageName + Slots.processSuffix(slot)
        return am.runningAppProcesses?.any { it.processName == name } == true
    }

    /** Deletes every trace of a previous account that used this slot. */
    fun purgeSlotData(ctx: Context, slot: Int) {
        if (isSlotProcessRunning(ctx, slot)) return
        val key = Slots.suffix(slot)
        listOf(ctx.dataDir, ctx.cacheDir).forEach { dir ->
            dir.listFiles()?.filter { it.name.contains(key) }?.forEach { it.deleteRecursively() }
        }
        File(ctx.dataDir, "shared_prefs/${Slots.prefsName(slot)}.xml").delete()
        Avatars.delete(ctx, slot)
    }

    private fun shortcutIcon(ctx: Context, a: Account): IconCompat {
        val dp = Avatars.load(ctx, a.slot)
        return if (dp != null) IconCompat.createWithBitmap(Avatars.circle(dp, 192))
        else IconCompat.createWithAdaptiveBitmap(Badge.bitmap(a, 216, circle = false))
    }

    fun syncShortcuts(ctx: Context) {
        try {
            val list = AccountStore.accounts(ctx).map { a ->
                ShortcutInfoCompat.Builder(ctx, "acc_${a.slot}")
                    .setShortLabel(a.name)
                    .setLongLabel(
                        if (a.description.isNotBlank()) "${a.name} – ${a.description}".take(60) else "Open ${a.name}"
                    )
                    .setIcon(shortcutIcon(ctx, a))
                    .setIntent(
                        Intent(ctx, Slots.activity(a.slot)).setAction(Intent.ACTION_VIEW)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                    .build()
            }
            ShortcutManagerCompat.setDynamicShortcuts(ctx, list)
        } catch (_: Exception) {}
    }
}
