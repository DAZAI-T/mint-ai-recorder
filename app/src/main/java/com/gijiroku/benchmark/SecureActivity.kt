package com.gijiroku.benchmark

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat

abstract class SecureActivity : AppCompatActivity() {
    private var displayedLanguage = "ja"

    override fun attachBaseContext(newBase: android.content.Context) {
        AppLanguage.initialize(newBase)
        displayedLanguage = AppLanguage.code
        super.attachBaseContext(AppLanguage.localized(newBase))
    }

    override fun onResume() {
        super.onResume()
        if (displayedLanguage != AppLanguage.code) recreate()
    }

    private var lockOverlay: View? = null
    private var authenticating = false
    private var actionAfterUnlock: (() -> Unit)? = null

    override fun setContentView(view: View?) {
        super.setContentView(view)
        if (view == null) return
        val left = view.paddingLeft
        val top = view.paddingTop
        val right = view.paddingRight
        val bottom = view.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(view) { target, insets ->
            val safe = insets.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout() or
                    androidx.core.view.WindowInsetsCompat.Type.ime()
            )
            target.setPadding(left + safe.left, top + safe.top, right + safe.right, bottom + safe.bottom)
            insets
        }
        androidx.core.view.ViewCompat.requestApplyInsets(view)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppearanceSettings(this).apply()
        super.onCreate(savedInstanceState)
        androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
            .isAppearanceLightNavigationBars = resources.getBoolean(R.bool.voice_light_bars)
        applyScreenshotProtection()
    }

    override fun onStart() {
        super.onStart()
        applyScreenshotProtection()
        if (AppLockController.requiresAuthentication(this)) {
            showLockOverlay()
            window.decorView.post { authenticate(lockScreen = true, title = AppLanguage.text("アプリのロックを解除", "Unlock app")) }
        } else {
            hideLockOverlay()
        }
    }

    override fun onStop() {
        if (!isChangingConfigurations && !authenticating) AppLockController.markBackgrounded()
        super.onStop()
    }

    protected fun authenticateForSensitiveAction(title: String, onSuccess: () -> Unit) {
        authenticate(lockScreen = false, title = title, onSuccess = onSuccess)
    }

    protected fun runWhenAppUnlocked(action: () -> Unit) {
        if (!AppLockController.requiresAuthentication(this)) {
            action()
            return
        }
        actionAfterUnlock = action
        showLockOverlay()
        if (!authenticating) authenticate(lockScreen = true, title = AppLanguage.text("アプリのロックを解除", "Unlock app"))
    }

    protected fun refreshScreenshotProtection() {
        applyScreenshotProtection()
    }

    private fun applyScreenshotProtection() {
        if (ScreenshotProtectionSettings(this).screenshotsAllowed) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    private fun authenticate(
        lockScreen: Boolean,
        title: String,
        onSuccess: (() -> Unit)? = null
    ) {
        if (authenticating || isFinishing || isDestroyed) return
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(authenticators) != BiometricManager.BIOMETRIC_SUCCESS) {
            if (!lockScreen) {
                Toast.makeText(this, AppLanguage.text("端末の画面ロックを設定してから実行してください", "Set up your device screen lock first"), Toast.LENGTH_LONG).show()
            }
            return
        }

        authenticating = true
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    AppLockController.markAuthenticated()
                    if (lockScreen) hideLockOverlay()
                    onSuccess?.invoke()
                    val queued = actionAfterUnlock
                    actionAfterUnlock = null
                    queued?.invoke()
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                    actionAfterUnlock = null
                    if (!lockScreen && errorCode != BiometricPrompt.ERROR_USER_CANCELED &&
                        errorCode != BiometricPrompt.ERROR_NEGATIVE_BUTTON
                    ) {
                        Toast.makeText(this@SecureActivity, AppLanguage.text("認証できませんでした", "Authentication failed"), Toast.LENGTH_SHORT).show()
                    }
                }
            }
        )
        val promptInfo = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(AppLanguage.text("生体認証または端末のPIN・パターン・パスワードを使用します", "Use biometrics or your device PIN, pattern, or password"))
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(promptInfo)
    }

    private fun showLockOverlay() {
        if (lockOverlay != null) return
        val content = findViewById<ViewGroup>(android.R.id.content)
        val message = TextView(this).apply {
            text = AppLanguage.text("ロック中\n認証すると内容を表示します", "Locked\nAuthenticate to view content")
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
        }
        val overlay = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(24, 24, 24))
            elevation = 10_000f
            addView(
                message,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
        content.addView(
            overlay,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        lockOverlay = overlay
    }

    private fun hideLockOverlay() {
        val overlay = lockOverlay ?: return
        (overlay.parent as? ViewGroup)?.removeView(overlay)
        lockOverlay = null
    }
}
