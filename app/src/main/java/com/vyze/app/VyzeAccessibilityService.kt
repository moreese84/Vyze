package com.vyze.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * Lightweight AccessibilityService shell for Vyze.
 *
 * Provides TalkBack integration and accessibility event forwarding
 * when the app is in the foreground. Does NOT intercept system-wide
 * gestures by default.
 */
class VyzeAccessibilityService : AccessibilityService() {

    private val TAG = "VyzeAccessibility"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var isVyzeActive = false

    override fun onServiceConnected() {
        super.onServiceConnected()

        serviceInfo = serviceInfo.apply {
            eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
            feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN or
                AccessibilityServiceInfo.FEEDBACK_HAPTIC
            flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
            notificationTimeout = 100
        }

        instance = this
        Log.d(TAG, "Vyze AccessibilityService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString() ?: return
        isVyzeActive = pkg == packageName

        if (isVyzeActive) {
            when (event.eventType) {
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                    Log.d(TAG, "Screen change: ${event.className}")
                }
            }
        }
    }

    override fun onInterrupt() {
        Log.d(TAG, "Vyze AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        mainHandler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Vyze AccessibilityService destroyed")
    }

    /**
     * Broadcast an announcement to any listening components.
     * The actual TTS output is handled by TTSManager in the foreground activity.
     */
    fun announceForAccessibility(message: String) {
        if (!isVyzeActive) return
        val intent = Intent(ACTION_ANNOUNCEMENT).apply {
            putExtra(EXTRA_MESSAGE, message)
            setPackage(packageName)
        }
        sendBroadcast(intent)
        Log.d(TAG, "Announced: $message")
    }

    fun isServiceEnabled(): Boolean {
        val serviceName = "$packageName/${this.javaClass.canonicalName}"
        val enabledServices = android.provider.Settings.Secure.getString(
            contentResolver, "enabled_accessibility_services"
        ) ?: return false
        return enabledServices.contains(serviceName)
    }

    companion object {
        private const val TAG = "VyzeAccessibility"
        const val ACTION_ANNOUNCEMENT = "com.vyze.app.ANNOUNCEMENT"
        const val EXTRA_MESSAGE = "message"

        @Volatile
        var instance: VyzeAccessibilityService? = null
            private set

        fun announce(message: String) {
            instance?.announceForAccessibility(message)
        }
    }
}
