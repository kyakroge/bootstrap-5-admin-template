package com.multibiz.hub.session

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import java.io.File
import java.util.concurrent.Executors

/** Saves media/documents into Downloads/MultiBiz/<Account name>/ */
object Downloads {
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private fun clean(s: String) = s.replace(Regex("[\\\\/:*?\"<>|\\n\\r]"), "_").trim().take(120)

    private fun ext(mime: String) =
        MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ""

    private fun toast(ctx: Context, msg: String) = main.post { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }

    fun saveDataUrl(ctx: Context, account: String, dataUrl: String, name: String, mime: String) {
        io.execute {
            try {
                require(dataUrl.startsWith("data:"))
                val comma = dataUrl.indexOf(',')
                require(comma > 0)
                val header = dataUrl.substring(5, comma)
                val realMime = mime.ifBlank { header.substringBefore(';') }.ifBlank { "application/octet-stream" }
                val bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT)
                var fileName = clean(name)
                if (fileName.isEmpty()) fileName = "MultiBiz_${System.currentTimeMillis()}${ext(realMime)}"
                write(ctx, clean(account), fileName, realMime, bytes)
                toast(ctx, "Saved to Downloads/MultiBiz/${clean(account)}")
            } catch (e: Exception) {
                toast(ctx, "Download failed")
            }
        }
    }

    private fun write(ctx: Context, folder: String, name: String, mime: String, bytes: ByteArray) {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/MultiBiz/$folder")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val resolver = ctx.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: error("insert failed")
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "MultiBiz/$folder")
            dir.mkdirs()
            File(dir, name).writeBytes(bytes)
        }
    }

    fun enqueue(ctx: Context, url: String, ua: String?, cd: String?, mime: String?, account: String) {
        try {
            val name = clean(URLUtil.guessFileName(url, cd, mime))
            val req = DownloadManager.Request(Uri.parse(url))
                .setMimeType(mime)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .addRequestHeader("User-Agent", ua ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setTitle(name)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, "MultiBiz/${clean(account)}/$name")
            ctx.getSystemService(DownloadManager::class.java).enqueue(req)
            toast(ctx, "Downloading $name")
        } catch (_: Exception) {
            toast(ctx, "Download failed")
        }
    }
}
