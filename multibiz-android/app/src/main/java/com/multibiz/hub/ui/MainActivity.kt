package com.multibiz.hub.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton
import com.multibiz.hub.R
import com.multibiz.hub.core.Account
import com.multibiz.hub.core.AccountStore
import com.multibiz.hub.core.Housekeeping
import com.multibiz.hub.core.KeepAliveService
import com.multibiz.hub.core.LockGate
import com.multibiz.hub.core.Notifier
import com.multibiz.hub.core.Slots
import com.multibiz.hub.core.StatusBus

class MainActivity : AppCompatActivity() {

    private lateinit var toolbar: MaterialToolbar
    private lateinit var list: RecyclerView
    private lateinit var empty: View
    private lateinit var fab: ExtendedFloatingActionButton
    private lateinit var batteryCard: MaterialCardView
    private lateinit var notifCard: MaterialCardView
    private lateinit var lockOverlay: View
    private lateinit var adapter: AccountAdapter

    private var askedNotificationsOnce = false
    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refreshBanners() }
    private val statusListener: () -> Unit = { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (AccountStore.settings(this).secureScreen) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)

        val root = findViewById<View>(R.id.root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        toolbar = findViewById(R.id.toolbar)
        list = findViewById(R.id.list)
        empty = findViewById(R.id.empty)
        fab = findViewById(R.id.fab)
        batteryCard = findViewById(R.id.batteryCard)
        notifCard = findViewById(R.id.notifCard)
        lockOverlay = findViewById(R.id.lockOverlay)

        toolbar.inflateMenu(R.menu.menu_main)
        toolbar.setOnMenuItemClickListener { onMenu(it.itemId); true }

        adapter = AccountAdapter(onOpen = { open(it) }, onMore = { a, v -> more(a, v) })
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter

        fab.setOnClickListener { addAccount() }
        findViewById<MaterialButton>(R.id.batteryButton).setOnClickListener { requestBatteryExemption() }
        findViewById<MaterialButton>(R.id.notifButton).setOnClickListener { requestNotifications() }
        findViewById<MaterialButton>(R.id.unlockButton).setOnClickListener { checkLock() }
        findViewById<TextView>(R.id.emptyHelp).setOnClickListener { showHelp() }

        if (Build.VERSION.SDK_INT >= 33 && !hasNotificationPermission()) requestNotifications()
        KeepAliveService.start(this)
        Housekeeping.syncShortcuts(this)
    }

    override fun onStart() {
        super.onStart()
        LockGate.onActivityStart()
        StatusBus.addListener(statusListener)
    }

    override fun onResume() {
        super.onResume()
        refresh()
        checkLock()
    }

    override fun onStop() {
        super.onStop()
        LockGate.onActivityStop(this)
        StatusBus.removeListener(statusListener)
    }

    private fun checkLock() {
        if (LockGate.isLocked(this)) {
            lockOverlay.visibility = View.VISIBLE
            LockGate.prompt(this, getString(R.string.unlock_title)) { lockOverlay.visibility = View.GONE }
        } else lockOverlay.visibility = View.GONE
    }

    private fun refresh() {
        val accounts = AccountStore.accounts(this)
        adapter.submit(accounts.map { it to StatusBus.get(it.slot) })
        empty.visibility = if (accounts.isEmpty()) View.VISIBLE else View.GONE
        list.visibility = if (accounts.isEmpty()) View.GONE else View.VISIBLE
        val full = accounts.size >= Slots.MAX
        fab.text = if (full) getString(R.string.limit_reached, Slots.MAX)
        else getString(R.string.add_account_count, accounts.size, Slots.MAX)
        fab.alpha = if (full) 0.6f else 1f
        val total = StatusBus.totalUnread()
        toolbar.subtitle = if (total > 0) getString(R.string.total_unread, total) else null
        val s = AccountStore.settings(this)
        toolbar.menu.findItem(R.id.action_lock)?.isChecked = s.appLock
        toolbar.menu.findItem(R.id.action_secure)?.isChecked = s.secureScreen
        refreshBanners()
    }

    private fun refreshBanners() {
        val hasAccounts = AccountStore.accounts(this).isNotEmpty()
        val pm = getSystemService(PowerManager::class.java)
        batteryCard.visibility =
            if (hasAccounts && !pm.isIgnoringBatteryOptimizations(packageName)) View.VISIBLE else View.GONE
        notifCard.visibility = if (!hasNotificationPermission()) View.VISIBLE else View.GONE
    }

    private fun hasNotificationPermission(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled()

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !askedNotificationsOnce
        ) {
            askedNotificationsOnce = true
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Already denied once (or notifications blocked) -> send user to system settings.
        try {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        } catch (_: Exception) {}
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryExemption() {
        try {
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) {}
        }
    }

    private fun open(a: Account) {
        startActivity(
            Intent(this, Slots.activity(a.slot))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(LockGate.EXTRA_UNLOCKED, !LockGate.isLocked(this))
        )
    }

    private fun nextColor(): Int {
        val used = AccountStore.accounts(this).map { it.color }.toSet()
        return AccountDialog.COLORS.firstOrNull { it !in used } ?: AccountDialog.COLORS[0]
    }

    private fun addAccount() {
        if (AccountStore.accounts(this).size >= Slots.MAX) {
            MaterialAlertDialogBuilder(this)
                .setTitle(getString(R.string.limit_reached, Slots.MAX))
                .setMessage(R.string.limit_message)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        AccountDialog.show(this, null, nextColor()) { name, color, desc ->
            AccountStore.validateName(this, name)?.let { return@show it }
            try {
                val a = AccountStore.add(this, name, color, desc)
                Housekeeping.onAccountAdded(this, a)
                refresh()
                open(a)
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "Could not add account"
            }
        }
    }

    private fun more(a: Account, anchor: View) {
        val pm = PopupMenu(this, anchor)
        pm.menu.add(0, 1, 0, R.string.open)
        pm.menu.add(0, 2, 1, R.string.edit_account)
        pm.menu.add(0, 3, 2, R.string.notification_sound)
        pm.menu.add(0, 5, 3, R.string.menu_grabber)
        pm.menu.add(0, 4, 4, R.string.remove_account)
        pm.setOnMenuItemClickListener {
            when (it.itemId) {
                1 -> open(a)
                2 -> edit(a)
                3 -> openChannelSettings(a)
                4 -> confirmRemove(a)
                5 -> startActivity(
                    Intent(this, Slots.activity(a.slot))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .putExtra(LockGate.EXTRA_UNLOCKED, !LockGate.isLocked(this))
                        .putExtra(com.multibiz.hub.session.GroupGrabber.EXTRA_OPEN, true)
                )
            }
            true
        }
        pm.show()
    }

    private fun edit(a: Account) {
        AccountDialog.show(this, a, a.color) { name, color, desc ->
            AccountStore.validateName(this, name, a.slot)?.let { return@show it }
            try {
                val updated = a.copy(name = name, color = color, description = desc)
                AccountStore.update(this, updated)
                Housekeeping.onAccountChanged(this, updated)
                refresh()
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "Could not save"
            }
        }
    }

    private fun openChannelSettings(a: Account) {
        Notifier.ensureAccountChannel(this, a)
        try {
            startActivity(
                Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                    .putExtra(Settings.EXTRA_CHANNEL_ID, a.channelId)
            )
        } catch (_: Exception) {}
    }

    private fun confirmRemove(a: Account) {
        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.remove_title, a.name))
            .setMessage(R.string.remove_message)
            .setPositiveButton(R.string.remove) { _, _ ->
                Housekeeping.removeAccount(this, a)
                Toast.makeText(this, getString(R.string.removed, a.name), Toast.LENGTH_SHORT).show()
                refresh()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun onMenu(id: Int) {
        when (id) {
            R.id.action_lock -> toggleLock()
            R.id.action_secure -> {
                val s = AccountStore.settings(this)
                AccountStore.saveSettings(this, s.copy(secureScreen = !s.secureScreen))
                Toast.makeText(this, R.string.secure_restart, Toast.LENGTH_LONG).show()
                recreate()
            }
            R.id.action_battery -> requestBatteryExemption()
            R.id.action_help -> showHelp()
        }
    }

    private fun toggleLock() {
        val s = AccountStore.settings(this)
        if (s.appLock) {
            LockGate.prompt(this, getString(R.string.unlock_title)) {
                AccountStore.saveSettings(this, s.copy(appLock = false))
                refresh()
            }
        } else {
            if (!LockGate.canUseLock(this)) {
                Toast.makeText(this, R.string.lock_unavailable, Toast.LENGTH_LONG).show()
                return
            }
            LockGate.prompt(this, getString(R.string.enable_lock)) {
                AccountStore.saveSettings(this, s.copy(appLock = true))
                refresh()
            }
        }
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_full)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}
