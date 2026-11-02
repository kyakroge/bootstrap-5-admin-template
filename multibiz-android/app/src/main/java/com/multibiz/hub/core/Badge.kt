package com.multibiz.hub.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface

/** Colored circle with the account initials - used as notification large icon & shortcut icon. */
object Badge {
    fun bitmap(account: Account, size: Int = 192, circle: Boolean = true): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = account.color }
        if (circle) c.drawCircle(size / 2f, size / 2f, size / 2f, bg) else c.drawColor(account.color)
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = size * (if (account.initials.length > 1) 0.38f else 0.48f) * (if (circle) 1f else 0.7f)
        }
        val y = size / 2f - (t.descent() + t.ascent()) / 2f
        c.drawText(account.initials, size / 2f, y, t)
        return b
    }
}
