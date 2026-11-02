package com.multibiz.hub.ui

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.multibiz.hub.R
import com.google.android.material.imageview.ShapeableImageView
import com.multibiz.hub.core.Account
import com.multibiz.hub.core.Avatars
import com.multibiz.hub.core.StatusBus

class AccountAdapter(
    private val onOpen: (Account) -> Unit,
    private val onMore: (Account, View) -> Unit
) : RecyclerView.Adapter<AccountAdapter.VH>() {

    private var rows: List<Pair<Account, StatusBus.Status>> = emptyList()

    fun submit(list: List<Pair<Account, StatusBus.Status>>) {
        rows = list
        @Suppress("NotifyDataSetChanged")
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val badge: TextView = v.findViewById(R.id.badge)
        val avatar: ShapeableImageView = v.findViewById(R.id.avatar)
        val name: TextView = v.findViewById(R.id.name)
        val status: TextView = v.findViewById(R.id.status)
        val unread: TextView = v.findViewById(R.id.unread)
        val more: ImageButton = v.findViewById(R.id.more)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        VH(LayoutInflater.from(parent.context).inflate(R.layout.item_account, parent, false))

    override fun getItemCount() = rows.size

    override fun onBindViewHolder(h: VH, position: Int) {
        val (a, s) = rows[position]
        val ctx = h.itemView.context
        h.badge.text = a.initials
        h.badge.backgroundTintList = ColorStateList.valueOf(a.color)
        val dp = Avatars.load(ctx, a.slot)
        if (dp != null) {
            h.avatar.setImageBitmap(dp)
            h.avatar.strokeColor = ColorStateList.valueOf(a.color)
            h.avatar.visibility = View.VISIBLE
            h.badge.visibility = View.INVISIBLE
        } else {
            h.avatar.setImageDrawable(null)
            h.avatar.visibility = View.GONE
            h.badge.visibility = View.VISIBLE
        }
        h.name.text = a.name
        val (text, colorRes) = when (s.state) {
            StatusBus.STATE_READY -> ctx.getString(R.string.status_ready) to R.color.status_ok
            StatusBus.STATE_QR -> ctx.getString(R.string.status_qr_short) to R.color.status_warn
            StatusBus.STATE_OFFLINE -> ctx.getString(R.string.status_offline) to R.color.status_warn
            StatusBus.STATE_LOADING -> ctx.getString(R.string.status_loading) to R.color.status_muted
            else -> ctx.getString(R.string.status_tap) to R.color.status_muted
        }
        h.status.text = "Slot ${a.slot} • $text"
        h.status.setTextColor(ContextCompat.getColor(ctx, colorRes))
        h.unread.visibility = if (s.unread > 0) View.VISIBLE else View.GONE
        h.unread.text = if (s.unread > 99) "99+" else s.unread.toString()
        h.unread.backgroundTintList = ColorStateList.valueOf(a.color)
        h.itemView.setOnClickListener { onOpen(a) }
        h.more.setOnClickListener { onMore(a, it) }
        h.itemView.setOnLongClickListener { onMore(a, h.more); true }
    }
}
