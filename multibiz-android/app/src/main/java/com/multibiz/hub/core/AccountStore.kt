package com.multibiz.hub.core

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Account(
    val slot: Int,
    val name: String,
    val color: Int,
    val createdAt: Long,
    /** Optional note about what this account is used for (e.g. "Sales enquiries"). */
    val description: String = ""
) {
    /** New channel per account instance => fresh sound/settings when a slot is reused. */
    val channelId: String get() = "acc_${slot}_$createdAt"

    val initials: String
        get() {
            val words = name.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            val s = when {
                words.size >= 2 -> "${words[0].first()}${words[1].first()}"
                words.size == 1 -> words[0].take(1)
                else -> "#"
            }
            return s.uppercase()
        }
}

data class AppSettings(
    val appLock: Boolean = false,
    val secureScreen: Boolean = false
)

/**
 * Accounts + settings in one small JSON file.
 * Written ONLY by the main process (atomic write), read by every process,
 * always fresh from disk so renames are visible everywhere immediately.
 */
object AccountStore {
    private const val FILE = "accounts.json"
    private const val MAX_NAME = 30
    const val MAX_DESCRIPTION = 80
    private val lock = Any()

    private data class Data(val accounts: List<Account>, val settings: AppSettings)

    private fun file(ctx: Context) = AtomicFile(File(ctx.applicationContext.filesDir, FILE))

    private fun read(ctx: Context): Data = synchronized(lock) {
        val text = try {
            String(file(ctx).readFully(), Charsets.UTF_8)
        } catch (e: Exception) {
            return Data(emptyList(), AppSettings())
        }
        try {
            val o = JSONObject(text)
            val arr = o.optJSONArray("accounts") ?: JSONArray()
            val list = (0 until arr.length()).mapNotNull { i ->
                val j = arr.optJSONObject(i) ?: return@mapNotNull null
                val slot = j.optInt("slot")
                if (slot !in 1..Slots.MAX) null
                else Account(
                    slot, j.optString("name"), j.optInt("color"), j.optLong("createdAt"),
                    j.optString("description")
                )
            }.distinctBy { it.slot }.sortedBy { it.slot }
            val s = o.optJSONObject("settings")
            Data(
                list,
                AppSettings(
                    appLock = s?.optBoolean("appLock") ?: false,
                    secureScreen = s?.optBoolean("secureScreen") ?: false
                )
            )
        } catch (e: Exception) {
            Data(emptyList(), AppSettings())
        }
    }

    private fun write(ctx: Context, d: Data) = synchronized(lock) {
        val o = JSONObject()
        val arr = JSONArray()
        d.accounts.forEach {
            arr.put(
                JSONObject()
                    .put("slot", it.slot).put("name", it.name).put("description", it.description)
                    .put("color", it.color).put("createdAt", it.createdAt)
            )
        }
        o.put("accounts", arr)
        o.put(
            "settings",
            JSONObject().put("appLock", d.settings.appLock).put("secureScreen", d.settings.secureScreen)
        )
        val f = file(ctx)
        val out = f.startWrite()
        try {
            out.write(o.toString().toByteArray(Charsets.UTF_8))
            f.finishWrite(out)
        } catch (e: Exception) {
            f.failWrite(out)
            throw e
        }
    }

    private fun cleanDescription(d: String) =
        d.replace(Regex("\\s+"), " ").trim().take(MAX_DESCRIPTION)

    fun accounts(ctx: Context): List<Account> = read(ctx).accounts
    fun account(ctx: Context, slot: Int): Account? = accounts(ctx).firstOrNull { it.slot == slot }
    fun settings(ctx: Context): AppSettings = read(ctx).settings

    fun saveSettings(ctx: Context, s: AppSettings) = synchronized(lock) {
        write(ctx, read(ctx).copy(settings = s))
    }

    /** @return error message, or null when valid. */
    fun validateName(ctx: Context, name: String, exceptSlot: Int = 0): String? {
        val n = name.trim()
        if (n.isEmpty()) return "Name is required"
        if (n.length > MAX_NAME) return "Max $MAX_NAME characters"
        if (accounts(ctx).any { it.slot != exceptSlot && it.name.equals(n, ignoreCase = true) }) {
            return "\"$n\" already exists - use a different name"
        }
        return null
    }

    fun add(ctx: Context, name: String, color: Int, description: String = ""): Account = synchronized(lock) {
        val d = read(ctx)
        require(d.accounts.size < Slots.MAX) { "Maximum ${Slots.MAX} accounts allowed" }
        validateName(ctx, name)?.let { throw IllegalArgumentException(it) }
        val slot = (1..Slots.MAX).first { s -> d.accounts.none { it.slot == s } }
        val a = Account(slot, name.trim(), color, System.currentTimeMillis(), cleanDescription(description))
        write(ctx, d.copy(accounts = (d.accounts + a).sortedBy { it.slot }))
        a
    }

    fun update(ctx: Context, account: Account) = synchronized(lock) {
        val d = read(ctx)
        validateName(ctx, account.name, account.slot)?.let { throw IllegalArgumentException(it) }
        write(ctx, d.copy(accounts = d.accounts.map { if (it.slot == account.slot) account.copy(name = account.name.trim(), description = cleanDescription(account.description)) else it }))
    }

    fun remove(ctx: Context, slot: Int) = synchronized(lock) {
        val d = read(ctx)
        write(ctx, d.copy(accounts = d.accounts.filterNot { it.slot == slot }))
    }
}
