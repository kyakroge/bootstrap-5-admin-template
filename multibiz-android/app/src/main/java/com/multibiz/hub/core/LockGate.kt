package com.multibiz.hub.core

import android.app.Activity
import android.content.Context
import android.os.SystemClock
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Optional fingerprint / face / PIN lock. State is per process. */
object LockGate {
    const val EXTRA_UNLOCKED = "com.multibiz.hub.extra.UNLOCKED"
    private const val AUTH = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    private const val GRACE_MS = 30_000L

    private var unlocked = false
    private var backgroundAt = 0L
    private var prompting = false

    fun onActivityStart() {
        if (backgroundAt > 0 && SystemClock.elapsedRealtime() - backgroundAt > GRACE_MS) unlocked = false
        backgroundAt = 0
    }

    fun onActivityStop(activity: Activity) {
        if (!activity.isChangingConfigurations) backgroundAt = SystemClock.elapsedRealtime()
    }

    fun isLocked(ctx: Context) = AccountStore.settings(ctx).appLock && !unlocked
    fun markUnlocked() { unlocked = true; backgroundAt = 0 }

    fun canUseLock(ctx: Context) =
        BiometricManager.from(ctx).canAuthenticate(AUTH) == BiometricManager.BIOMETRIC_SUCCESS

    fun prompt(activity: FragmentActivity, title: String, onSuccess: () -> Unit) {
        if (prompting) return
        if (!canUseLock(activity)) { markUnlocked(); onSuccess(); return }
        prompting = true
        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    markUnlocked()
                    onSuccess()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle("Verify it's you")
                .setAllowedAuthenticators(AUTH)
                .build()
        )
    }
}
