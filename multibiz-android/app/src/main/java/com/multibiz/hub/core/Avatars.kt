package com.multibiz.hub.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The account's own WhatsApp profile photo (DP), cached per slot in
 * files/avatars/slot_N.png. Written only by that slot's process; read by every process.
 */
object Avatars {
    private const val TAG = "MultiBiz.Avatar"
    private const val SIZE = 256
    private const val MAX_BYTES = 5 * 1024 * 1024
    private const val REFRESH_MS = 12 * 60 * 60_000L
    private val io = Executors.newSingleThreadExecutor()

    private val cache = HashMap<Int, Pair<Long, Bitmap>>()

    fun file(ctx: Context, slot: Int) = File(ctx.applicationContext.filesDir, "avatars/slot_$slot.png")

    /** Decoded avatar or null. Cached in memory until the file changes. */
    @Synchronized
    fun load(ctx: Context, slot: Int): Bitmap? {
        val f = file(ctx, slot)
        if (!f.exists()) { cache.remove(slot); return null }
        val stamp = f.lastModified()
        cache[slot]?.let { if (it.first == stamp) return it.second }
        val bmp = try { BitmapFactory.decodeFile(f.path) } catch (_: Exception) { null } ?: return null
        cache[slot] = stamp to bmp
        return bmp
    }

    @Synchronized
    fun delete(ctx: Context, slot: Int) {
        cache.remove(slot)
        file(ctx, slot).delete()
    }

    fun circle(src: Bitmap, size: Int): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = size.toFloat() / minOf(src.width, src.height)
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate((size - src.width * scale) / 2f, (size - src.height * scale) / 2f)
        })
        Canvas(out).drawCircle(size / 2f, size / 2f, size / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.shader = shader })
        return out
    }

    private fun isAllowed(url: String): Boolean {
        val u = try { Uri.parse(url) } catch (_: Exception) { return false }
        val h = u.host ?: return false
        return u.scheme == "https" && (h.endsWith(".whatsapp.net") || h.endsWith(".whatsapp.com"))
    }

    /** Called in the account process when the page reports its own profile photo URL. */
    fun update(ctx: Context, slot: Int, url: String, onSaved: () -> Unit) {
        if (!isAllowed(url)) return
        val app = ctx.applicationContext
        val key = url.substringBefore('?')
        val prefs = app.getSharedPreferences(Slots.prefsName(slot), Context.MODE_PRIVATE)
        val f = file(app, slot)
        val fresh = f.exists() && System.currentTimeMillis() - f.lastModified() < REFRESH_MS
        if (fresh && prefs.getString("avatarKey", null) == key) return
        io.execute {
            try {
                val bmp = download(url) ?: return@execute
                f.parentFile?.mkdirs()
                val tmp = File(f.parentFile, f.name + ".tmp")
                FileOutputStream(tmp).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (!tmp.renameTo(f)) { tmp.delete(); return@execute }
                prefs.edit().putString("avatarKey", key).apply()
                synchronized(this) { cache.remove(slot) }
                onSaved()
            } catch (e: Exception) {
                Log.w(TAG, "slot $slot avatar download failed: $e")
            }
        }
    }

    private fun download(url: String): Bitmap? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 15_000
            c.readTimeout = 15_000
            c.instanceFollowRedirects = true
            if (c.responseCode != 200) return null
            val bytes = c.inputStream.use { input ->
                val buf = java.io.ByteArrayOutputStream()
                val chunk = ByteArray(16 * 1024)
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    buf.write(chunk, 0, n)
                    if (buf.size() > MAX_BYTES) return null
                }
                buf.toByteArray()
            }
            val src = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            // Center-crop to a square SIZE x SIZE.
            val side = minOf(src.width, src.height)
            val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            return Bitmap.createScaledBitmap(square, SIZE, SIZE, true)
        } finally {
            c.disconnect()
        }
    }
}
