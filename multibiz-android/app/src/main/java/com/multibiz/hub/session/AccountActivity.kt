package com.multibiz.hub.session

import android.app.ActivityManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.addCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.multibiz.hub.App
import com.multibiz.hub.R
import com.multibiz.hub.core.Account
import com.multibiz.hub.core.AccountStore
import com.multibiz.hub.core.KeepAliveService
import com.multibiz.hub.core.LockGate
import com.multibiz.hub.core.Notifier
import com.multibiz.hub.core.StatusBus
import com.multibiz.hub.ui.MainActivity

/** Shows one account. Runs in that account's own process (:waN). */
abstract class AccountActivity : AppCompatActivity() {

    lateinit var webContainer: FrameLayout
        private set
    private lateinit var toolbar: MaterialToolbar
    private lateinit var progress: LinearProgressIndicator
    private lateinit var helpBar: TextView
    private lateinit var lockOverlay: View
    private var account: Account? = null

    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val cb = pendingFileCallback
        pendingFileCallback = null
        cb?.onReceiveValue(parseFiles(r.resultCode, r.data))
    }

    private var permissionCallback: ((Boolean) -> Unit)? = null
    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        permissionCallback?.invoke(ok)
        permissionCallback = null
    }

    val isVisibleToUser: Boolean get() = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val acc = AccountStore.account(this, App.slot)
        if (App.slot == 0 || acc == null) {
            Toast.makeText(this, "This account was removed", Toast.LENGTH_SHORT).show()
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        account = acc
        if (AccountStore.settings(this).secureScreen) {
            window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_account)

        val root = findViewById<View>(R.id.root)
        toolbar = findViewById(R.id.toolbar)
        progress = findViewById(R.id.progress)
        helpBar = findViewById(R.id.helpBar)
        webContainer = findViewById(R.id.webContainer)
        lockOverlay = findViewById(R.id.lockOverlay)
        findViewById<MaterialButton>(R.id.unlockButton).setOnClickListener { checkLock() }

        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(bars.left, bars.top, bars.right, maxOf(bars.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }
        WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars = false

        toolbar.inflateMenu(R.menu.menu_account)
        toolbar.setNavigationOnClickListener { openDashboard() }
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_reload -> SessionManager.reload()
                R.id.action_layout -> chooseLayout()
                R.id.action_notifications -> openChannelSettings()
                R.id.action_help -> showHelp()
                R.id.action_dashboard -> openDashboard()
            }
            true
        }
        applyAccount(acc)

        if (intent.getBooleanExtra(LockGate.EXTRA_UNLOCKED, false)) LockGate.markUnlocked()
        KeepAliveService.start(this)
        SessionManager.attach(this)
        handleIntent(intent)

        onBackPressedDispatcher.addCallback(this) { moveTaskToBack(true) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(LockGate.EXTRA_UNLOCKED, false)) LockGate.markUnlocked()
        handleIntent(intent)
    }

    private fun handleIntent(i: Intent?) {
        val js = i?.getStringExtra(Notifier.EXTRA_JS_ID) ?: return
        i.removeExtra(Notifier.EXTRA_JS_ID)
        webContainer.postDelayed({ SessionManager.clickNotification(js) }, 300)
    }

    fun onAvatarChanged() {
        account?.let { applyAccount(it) }
    }

    private fun applyAccount(a: Account) {
        toolbar.title = a.name
        val px = (36 * resources.displayMetrics.density).toInt()
        toolbar.logo = com.multibiz.hub.core.Avatars.load(this, a.slot)?.let {
            android.graphics.drawable.BitmapDrawable(resources, com.multibiz.hub.core.Avatars.circle(it, px))
        }
        toolbar.logoDescription = a.name
        toolbar.setBackgroundColor(a.color)
        findViewById<View>(R.id.root).setBackgroundColor(a.color)
        progress.setIndicatorColor(ContextCompat.getColor(this, android.R.color.white))
        @Suppress("DEPRECATION")
        setTaskDescription(
            ActivityManager.TaskDescription(a.name, com.multibiz.hub.core.Badge.icon(this, a, 128), a.color)
        )
    }

    override fun onStart() {
        super.onStart()
        LockGate.onActivityStart()
    }

    override fun onResume() {
        super.onResume()
        val acc = AccountStore.account(this, App.slot)
        if (acc == null) { finish(); return }
        if (acc != account) { account = acc; applyAccount(acc) }
        SessionManager.onHostResumed()
        checkLock()
    }

    override fun onStop() {
        super.onStop()
        LockGate.onActivityStop(this)
    }

    override fun onDestroy() {
        if (account != null) SessionManager.detach(this)
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        super.onDestroy()
    }

    private fun checkLock() {
        if (LockGate.isLocked(this)) {
            lockOverlay.visibility = View.VISIBLE
            LockGate.prompt(this, "Unlock ${account?.name ?: "account"}") { lockOverlay.visibility = View.GONE }
        } else {
            lockOverlay.visibility = View.GONE
        }
    }

    // ---------------------------------------------------------------- called by SessionManager

    fun onProgress(p: Int) {
        progress.visibility = if (p in 1..99) View.VISIBLE else View.GONE
        progress.setProgressCompat(p, true)
    }

    fun onSessionStatus(state: String, unread: Int, offline: Boolean) {
        toolbar.subtitle = when {
            offline || state == StatusBus.STATE_OFFLINE -> getString(R.string.status_offline)
            state == StatusBus.STATE_QR -> getString(R.string.status_qr)
            state == StatusBus.STATE_READY -> if (unread > 0) getString(R.string.status_unread, unread) else getString(R.string.status_ready)
            else -> getString(R.string.status_loading)
        }
        helpBar.visibility = if (state == StatusBus.STATE_QR) View.VISIBLE else View.GONE
    }

    fun openFileChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = cb
        val mimes = params.acceptTypes
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.contains('/') }
            .distinct()
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimes.size == 1) mimes[0] else "*/*"
            if (mimes.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, mimes.toTypedArray())
            if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            }
        }
        return try {
            fileLauncher.launch(Intent.createChooser(pick, getString(R.string.choose_file)))
            true
        } catch (_: Exception) {
            pendingFileCallback = null
            false
        }
    }

    private fun parseFiles(resultCode: Int, data: Intent?): Array<Uri>? {
        if (resultCode != RESULT_OK || data == null) return null
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) return Array(clip.itemCount) { clip.getItemAt(it).uri }
        return data.data?.let { arrayOf(it) }
    }

    fun requestRuntimePermission(permission: String, cb: (Boolean) -> Unit) {
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            cb(true); return
        }
        permissionCallback?.invoke(false)
        permissionCallback = cb
        permissionLauncher.launch(permission)
    }

    // ---------------------------------------------------------------- menu actions

    private fun openDashboard() {
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        )
    }

    private fun chooseLayout() {
        val labels = arrayOf("Fit to phone screen", "Compact desktop (900px)", "Full desktop (1200px)")
        val values = intArrayOf(0, 900, 1200)
        val current = values.indexOf(SessionManager.layoutWidth()).coerceAtLeast(0)
        MaterialAlertDialogBuilder(this)
            .setTitle("Layout")
            .setSingleChoiceItems(labels, current) { d, which ->
                SessionManager.setLayoutWidth(values[which])
                d.dismiss()
            }
            .show()
    }

    private fun openChannelSettings() {
        val a = account ?: return
        Notifier.ensureAccountChannel(this, a)
        val i = Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
            .putExtra(Settings.EXTRA_CHANNEL_ID, a.channelId)
        try { startActivity(i) } catch (_: Exception) {}
    }

    private fun showHelp() {
        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.help_title)
            .setMessage(R.string.help_link)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

class AccountActivity1 : AccountActivity()
class AccountActivity2 : AccountActivity()
class AccountActivity3 : AccountActivity()
class AccountActivity4 : AccountActivity()
class AccountActivity5 : AccountActivity()
