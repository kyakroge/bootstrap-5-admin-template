package com.multibiz.hub.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.multibiz.hub.App
import com.multibiz.hub.R
import com.multibiz.hub.core.AccountStore
import com.multibiz.hub.core.LockGate
import com.multibiz.hub.core.StatusBus
import com.multibiz.hub.core.Xlsx
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Group Grabber: export group members (numbers) and group chats of THIS account to Excel.
 * Files: Downloads/MultiBiz/<Account>/Groups/<Group name>.xlsx  and  "<Group name> - Chat.xlsx"
 */
class GroupGrabber(private val activity: AccountActivity) {

    private data class Group(val id: String, val name: String, val size: Int)
    private data class Saved(val label: String, val uri: Uri?, val count: Int, val hidden: Int, val error: String?)
    private enum class Mode { MEMBERS, CHAT }
    private enum class Phase { IDLE, PREPARING, EXPORTING }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val results: MutableList<Saved> = Collections.synchronizedList(mutableListOf())
    private var req = 0
    private var mode = Mode.MEMBERS
    private var names: Map<String, String> = emptyMap()
    private var waitAttempts = 0
    private var phase = Phase.IDLE
    private var progress: AlertDialog? = null
    private var progressText: TextView? = null
    private var progressBar: LinearProgressIndicator? = null

    private val accountName get() = AccountStore.account(activity, App.slot)?.name ?: "Account"
    private val folder get() = "$accountName/Groups"

    // ------------------------------------------------------------------ entry

    fun open() {
        if (activity.isFinishing) return
        if (phase == Phase.EXPORTING) return
        phase = Phase.PREPARING
        val state = SessionManager.currentState
        if (state == StatusBus.STATE_QR) {
            phase = Phase.IDLE
            dismissProgress()
            info("Account not linked", "Link this account first (scan the QR), then use Group Grabber.")
            return
        }
        if (state != StatusBus.STATE_READY || LockGate.isLocked(activity)) {
            if (waitAttempts == 0) showProgress("Waiting for WhatsApp to finish loading…", cancellable = true)
            if (waitAttempts++ < 60) { main.postDelayed({ open() }, 1_000); return }
            waitAttempts = 0
            phase = Phase.IDLE
            dismissProgress()
            info("Not ready", "WhatsApp is still loading. Wait until the chat list appears, then try again.")
            return
        }
        waitAttempts = 0
        showProgress("Loading groups…", cancellable = true)
        val r = ++req
        SessionManager.grab("window.__mbGrabber.list($r);")
    }

    fun onMessage(o: JSONObject) {
        if (o.optInt("req") != req) return
        when (o.optString("ev")) {
            "groups" -> {
                phase = Phase.IDLE
                dismissProgress()
                showPicker(parseGroups(o.optJSONArray("items")))
            }
            "progress" -> onProgress(o)
            "members" -> saveMembers(o)
            "chat" -> saveChat(o)
            "done" -> {
                val cancelled = o.optBoolean("cancelled")
                io.execute { main.post { finish(cancelled) } } // after all pending file writes
            }
            "error" -> {
                phase = Phase.IDLE
                dismissProgress()
                keepScreenOn(false)
                info("Something went wrong", o.optString("msg").ifBlank { "Unknown error" })
            }
            "diag" -> copyDiagnostics(o.optJSONObject("data"))
        }
    }

    // ------------------------------------------------------------------ picker

    private fun parseGroups(a: JSONArray?): List<Group> {
        if (a == null) return emptyList()
        val list = (0 until a.length()).mapNotNull { i ->
            val g = a.optJSONObject(i) ?: return@mapNotNull null
            val id = g.optString("id")
            if (id.isEmpty()) null else Group(id, g.optString("name").ifBlank { "Group" }, g.optInt("size"))
        }
        names = list.associate { it.id to it.name }
        return list
    }

    private fun showPicker(groups: List<Group>) {
        if (activity.isFinishing) return
        if (groups.isEmpty()) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("No groups found")
                .setMessage("This account doesn't seem to be in any group, or WhatsApp hasn't finished syncing yet. Wait a minute and try again.\n\nStill empty? Tap \"Copy details\" and send them to the developer.")
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton("Copy details") { _, _ -> requestDiagnostics() }
                .show()
            return
        }
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_groups, null)
        val search = v.findViewById<EditText>(R.id.search)
        val all = v.findViewById<CheckBox>(R.id.selectAll)
        val count = v.findViewById<TextView>(R.id.selectedCount)
        val list = v.findViewById<ListView>(R.id.groupList)
        val selected = LinkedHashSet<String>()
        lateinit var adapter: GroupAdapter

        fun refreshCount() {
            count.text = "${selected.size} selected"
            all.isChecked = adapter.visible.isNotEmpty() && adapter.visible.all { it.id in selected }
        }
        adapter = GroupAdapter(groups, selected)
        list.adapter = adapter
        list.setOnItemClickListener { _, _, pos, _ ->
            val g = adapter.visible[pos]
            if (!selected.add(g.id)) selected.remove(g.id)
            adapter.notifyDataSetChanged()
            refreshCount()
        }
        all.setOnClickListener {
            val ids = adapter.visible.map { it.id }
            if (all.isChecked) selected.addAll(ids) else selected.removeAll(ids.toSet())
            adapter.notifyDataSetChanged()
            refreshCount()
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                adapter.filter(s?.toString().orEmpty())
                refreshCount()
            }
        })
        refreshCount()

        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Group Grabber • ${groups.size} groups")
            .setView(v)
            .setPositiveButton("Export members", null)
            .setNeutralButton("Export chats", null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        dialog.setOnShowListener {
            fun chosen() = groups.filter { it.id in selected }.map { it.id }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val ids = chosen()
                if (ids.isEmpty()) { toast("Select at least one group"); return@setOnClickListener }
                dialog.dismiss()
                startMembers(ids)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val ids = chosen()
                if (ids.isEmpty()) { toast("Select at least one group"); return@setOnClickListener }
                dialog.dismiss()
                askChatLimit(ids)
            }
        }
        dialog.show()
    }

    private inner class GroupAdapter(private val groups: List<Group>, private val selected: Set<String>) : BaseAdapter() {
        var visible: List<Group> = groups
            private set

        fun filter(q: String) {
            val t = q.trim()
            visible = if (t.isEmpty()) groups else groups.filter { it.name.contains(t, ignoreCase = true) }
            notifyDataSetChanged()
        }

        override fun getCount() = visible.size
        override fun getItem(position: Int) = visible[position]
        override fun getItemId(position: Int) = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val v = convertView ?: LayoutInflater.from(activity).inflate(R.layout.item_group, parent, false)
            val g = visible[position]
            v.findViewById<CheckBox>(R.id.check).isChecked = g.id in selected
            v.findViewById<TextView>(R.id.groupName).text = g.name
            v.findViewById<TextView>(R.id.groupSize).text =
                if (g.size > 0) "${g.size} members" else "Members: tap export to load"
            return v
        }
    }

    private fun askChatLimit(ids: List<String>) {
        val labels = arrayOf("Last 1,000 messages", "Last 5,000 messages", "Last 20,000 messages", "Full history (can take long)")
        val limits = intArrayOf(1_000, 5_000, 20_000, 200_000)
        var choice = 1
        MaterialAlertDialogBuilder(activity)
            .setTitle("How many messages per group?")
            .setSingleChoiceItems(labels, choice) { _, which -> choice = which }
            .setPositiveButton("Export") { _, _ -> startChat(ids, limits[choice]) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ export

    private fun startMembers(ids: List<String>) {
        mode = Mode.MEMBERS
        phase = Phase.EXPORTING
        results.clear()
        keepScreenOn(true)
        showProgress("Exporting members… 0 of ${ids.size}", cancellable = true)
        val r = ++req
        SessionManager.grab("window.__mbGrabber.members($r, ${JSONArray(ids)});")
    }

    private fun startChat(ids: List<String>, limit: Int) {
        mode = Mode.CHAT
        phase = Phase.EXPORTING
        results.clear()
        keepScreenOn(true)
        showProgress("Loading messages…", cancellable = true)
        val r = ++req
        SessionManager.grab("window.__mbGrabber.chat($r, ${JSONArray(ids)}, $limit);")
    }

    private var curIndex = 0
    private var curTotal = 0

    private fun onProgress(o: JSONObject) {
        if (o.has("total")) { curIndex = o.optInt("i"); curTotal = o.optInt("total") }
        val text = when (mode) {
            Mode.MEMBERS -> "Exporting members… group ${curIndex + 1} of $curTotal"
            Mode.CHAT -> {
                val n = o.optInt("n")
                "Group ${curIndex + 1} of $curTotal" + if (n > 0) " • %,d messages loaded…".format(n) else " • loading…"
            }
        }
        progressText?.text = text
        if (curTotal > 0) {
            progressBar?.isIndeterminate = false
            progressBar?.setProgressCompat(((curIndex * 100) / curTotal).coerceIn(0, 100), true)
        }
    }

    private fun saveMembers(o: JSONObject) {
        val id = o.optString("id")
        val name = o.optString("name").ifBlank { names[id] ?: "Group" }
        val rowsJ = o.optJSONArray("rows") ?: JSONArray()
        val err = o.optString("error")
        val ctx = activity.applicationContext
        val folder = folder
        io.execute {
            var hidden = 0
            val rows = (0 until rowsJ.length()).map { i ->
                val r = rowsJ.optJSONObject(i) ?: JSONObject()
                val p = r.optString("phone")
                if (p.isEmpty()) hidden++
                listOf<Any?>(
                    i + 1,
                    if (p.isNotEmpty()) "+$p" else "Hidden by WhatsApp",
                    p.ifEmpty { null },
                    r.optString("name").ifEmpty { null },
                    r.optString("push").ifEmpty { null },
                    r.optString("role").ifEmpty { "Member" },
                    if (r.optBoolean("saved")) "Yes" else "No"
                )
            }
            if (rows.isEmpty()) {
                results.add(Saved(name, null, 0, 0, err.ifBlank { "no members found" }))
                return@execute
            }
            val file = Downloads.fileName(name, "xlsx")
            try {
                val bytes = Xlsx.build("Members", MEMBER_COLUMNS, rows)
                val uri = Downloads.saveBytes(ctx, folder, file, Xlsx.MIME, bytes)
                results.add(Saved(file, uri, rows.size, hidden, null))
            } catch (e: Exception) {
                results.add(Saved(file, null, 0, 0, "could not save file"))
            }
        }
    }

    private fun saveChat(o: JSONObject) {
        val id = o.optString("id")
        val name = o.optString("name").ifBlank { names[id] ?: "Group" }
        val rowsJ = o.optJSONArray("rows") ?: JSONArray()
        val err = o.optString("error")
        val ctx = activity.applicationContext
        val folder = folder
        io.execute {
            val dateFmt = SimpleDateFormat("dd-MM-yyyy", Locale.getDefault())
            val timeFmt = SimpleDateFormat("hh:mm a", Locale.getDefault())
            val rows = (0 until rowsJ.length()).map { i ->
                val r = rowsJ.optJSONObject(i) ?: JSONObject()
                val t = r.optLong("t")
                val d = Date(t * 1000)
                val p = r.optString("phone")
                listOf<Any?>(
                    i + 1,
                    if (t > 0) dateFmt.format(d) else null,
                    if (t > 0) timeFmt.format(d) else null,
                    r.optString("name").ifEmpty { null },
                    if (p.isNotEmpty()) "+$p" else null,
                    typeLabel(r.optString("type")),
                    r.optString("text")
                )
            }
            if (rows.isEmpty()) {
                results.add(Saved(name, null, 0, 0, err.ifBlank { "no messages found" }))
                return@execute
            }
            val file = Downloads.fileName("$name - Chat", "xlsx")
            try {
                val bytes = Xlsx.build("Chat", CHAT_COLUMNS, rows)
                val uri = Downloads.saveBytes(ctx, folder, file, Xlsx.MIME, bytes)
                results.add(Saved(file, uri, rows.size, 0, null))
            } catch (e: Exception) {
                results.add(Saved(file, null, 0, 0, "could not save file"))
            }
        }
    }

    private fun typeLabel(t: String) = when (t) {
        "chat" -> "Text"
        "image" -> "Photo"
        "video" -> "Video"
        "gif" -> "GIF"
        "document" -> "Document"
        "ptt" -> "Voice note"
        "audio" -> "Audio"
        "sticker" -> "Sticker"
        "location" -> "Location"
        "vcard", "multi_vcard" -> "Contact"
        "poll_creation" -> "Poll"
        "revoked" -> "Deleted"
        "event_creation" -> "Event"
        "" -> "Text"
        else -> t.replaceFirstChar { it.uppercase() }
    }

    private fun finish(cancelled: Boolean) {
        phase = Phase.IDLE
        dismissProgress()
        keepScreenOn(false)
        if (activity.isFinishing) return
        val res = synchronized(results) { results.toList() }
        val ok = res.filter { it.error == null }
        val unit = if (mode == Mode.MEMBERS) "members" else "messages"
        val sb = StringBuilder()
        if (cancelled) sb.append("Stopped. Files finished before stopping were saved.\n\n")
        if (ok.isNotEmpty()) {
            sb.append("Saved in Downloads/MultiBiz/$folder:\n")
            ok.forEach { sb.append("\n✓ ${it.label}  (%,d $unit)".format(it.count)) }
        }
        res.filter { it.error != null }.forEach { sb.append("\n✗ ${it.label} – ${it.error}") }
        val hidden = ok.sumOf { it.hidden }
        if (hidden > 0) {
            sb.append("\n\n$hidden number(s) are hidden by WhatsApp privacy (e.g. communities / members who hide their number). They are listed as \"Hidden by WhatsApp\".")
        }
        val b = MaterialAlertDialogBuilder(activity)
            .setTitle(if (ok.isEmpty()) "Nothing exported" else "Export complete")
            .setMessage(sb.toString().ifBlank { "Nothing was exported." })
        val uris = ok.mapNotNull { it.uri }
        if (uris.isNotEmpty()) {
            b.setPositiveButton("Share") { _, _ -> share(uris) }
            if (uris.size == 1) b.setNeutralButton("Open") { _, _ -> openFile(uris[0]) }
            b.setNegativeButton("Close", null)
        } else {
            b.setPositiveButton(android.R.string.ok, null)
            if (ok.isEmpty()) b.setNeutralButton("Copy details") { _, _ -> requestDiagnostics() }
        }
        b.show()
    }

    private fun share(uris: List<Uri>) {
        val i = if (uris.size == 1) {
            Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
        } else {
            Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        i.type = Xlsx.MIME
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(Intent.createChooser(i, "Share Excel file")) } catch (_: Exception) {}
    }

    private fun openFile(uri: Uri) {
        val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, Xlsx.MIME).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { activity.startActivity(i) } catch (_: Exception) { toast("No app found to open Excel files. Install Excel, Google Sheets or WPS.") }
    }

    // ------------------------------------------------------------------ diagnostics

    private fun requestDiagnostics() {
        val r = ++req
        SessionManager.grab("window.__mbGrabber.diag($r);")
    }

    private fun copyDiagnostics(d: JSONObject?) {
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("MultiBiz diagnostics", (d ?: JSONObject()).toString(2)))
        toast("Details copied. Paste them to the developer.")
    }

    // ------------------------------------------------------------------ ui helpers

    private fun showProgress(text: String, cancellable: Boolean) {
        if (activity.isFinishing) return
        if (progress?.isShowing == true) { progressText?.text = text; return }
        val v = LayoutInflater.from(activity).inflate(R.layout.dialog_progress, null)
        progressText = v.findViewById(R.id.progressText)
        progressBar = v.findViewById(R.id.progressBar)
        progressText?.text = text
        progressBar?.isIndeterminate = true
        val b = MaterialAlertDialogBuilder(activity).setView(v).setCancelable(false)
        if (cancellable) b.setNegativeButton("Stop") { _, _ -> cancel() }
        progress = b.show()
    }

    private fun dismissProgress() {
        try { progress?.dismiss() } catch (_: Exception) {}
        progress = null
        progressText = null
        progressBar = null
    }

    private fun cancel() {
        waitAttempts = 0
        main.removeCallbacksAndMessages(null)
        SessionManager.grab("if (window.__mbGrabber) window.__mbGrabber.cancel = true;")
        if (phase == Phase.EXPORTING) {
            // The page finishes the current group and reports "done" with partial results.
            showProgress("Stopping…", cancellable = false)
        } else {
            req++ // ignore a late group list
            phase = Phase.IDLE
            keepScreenOn(false)
        }
    }

    private fun keepScreenOn(on: Boolean) {
        if (on) activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun info(title: String, msg: String) {
        if (activity.isFinishing) return
        MaterialAlertDialogBuilder(activity).setTitle(title).setMessage(msg).setPositiveButton(android.R.string.ok, null).show()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_LONG).show()

    companion object {
        const val EXTRA_OPEN = "com.multibiz.hub.extra.OPEN_GROUP_GRABBER"

        private val MEMBER_COLUMNS = listOf(
            Xlsx.Column("S.No", 7.0),
            Xlsx.Column("Phone Number", 20.0),
            Xlsx.Column("Number (for import)", 20.0),
            Xlsx.Column("Saved Name", 26.0),
            Xlsx.Column("WhatsApp Name", 26.0),
            Xlsx.Column("Role", 13.0),
            Xlsx.Column("Saved in Contacts", 17.0)
        )
        private val CHAT_COLUMNS = listOf(
            Xlsx.Column("S.No", 7.0),
            Xlsx.Column("Date", 12.0),
            Xlsx.Column("Time", 10.0),
            Xlsx.Column("Sender", 24.0),
            Xlsx.Column("Sender Number", 18.0),
            Xlsx.Column("Type", 11.0),
            Xlsx.Column("Message", 70.0, wrap = true)
        )
    }
}
