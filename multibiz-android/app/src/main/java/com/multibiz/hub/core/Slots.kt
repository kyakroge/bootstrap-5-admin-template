package com.multibiz.hub.core

import android.app.Activity
import android.app.Service
import com.multibiz.hub.session.AccountActivity1
import com.multibiz.hub.session.AccountActivity2
import com.multibiz.hub.session.AccountActivity3
import com.multibiz.hub.session.AccountActivity4
import com.multibiz.hub.session.AccountActivity5
import com.multibiz.hub.session.SessionService1
import com.multibiz.hub.session.SessionService2
import com.multibiz.hub.session.SessionService3
import com.multibiz.hub.session.SessionService4
import com.multibiz.hub.session.SessionService5

/** A slot = one isolated account process. Hard limit: 5. */
object Slots {
    const val MAX = 5

    private val activities: Array<Class<out Activity>> = arrayOf(
        AccountActivity1::class.java, AccountActivity2::class.java, AccountActivity3::class.java,
        AccountActivity4::class.java, AccountActivity5::class.java
    )
    private val services: Array<Class<out Service>> = arrayOf(
        SessionService1::class.java, SessionService2::class.java, SessionService3::class.java,
        SessionService4::class.java, SessionService5::class.java
    )

    fun activity(slot: Int): Class<out Activity> = activities[slot - 1]
    fun service(slot: Int): Class<out Service> = services[slot - 1]
    fun suffix(slot: Int) = "wa_slot_$slot"
    fun prefsName(slot: Int) = "prefs_${suffix(slot)}"
    fun processSuffix(slot: Int) = ":wa$slot"

    fun slotOfProcess(processName: String?): Int {
        if (processName == null) return 0
        val i = processName.lastIndexOf(":wa")
        if (i < 0) return 0
        val n = processName.substring(i + 3).toIntOrNull() ?: return 0
        return if (n in 1..MAX) n else 0
    }
}
