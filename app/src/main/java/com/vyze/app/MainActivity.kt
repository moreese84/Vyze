package com.vyze.app

import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import com.vyze.app.databinding.ActivityMainBinding

/**
 * Main entry point into the Vyze app. Single-activity pattern with
 * Navigation component hosting all fragments.
 *
 * ## Crash Safety
 * The entire onCreate() is wrapped in a try-catch. If ANY Throwable occurs
 * during splash setup, layout inflation, or navigation init, the crash is
 * caught and a red error TextView is shown so the user can report it.
 *
 * A [superCalled] flag prevents calling super.onCreate() twice in the
 * catch block, which would throw IllegalStateException and prevent
 * the red error screen from rendering.
 */
class MainActivity : AppCompatActivity() {

    private var activityMainBinding: ActivityMainBinding? = null
    private val viewModel: MainViewModel by viewModels()
    private var hapticManager: HapticManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        // Track whether super.onCreate() has already been called.
        // If it was called in the try block, we must NOT call it again
        // in the catch block — Android throws IllegalStateException.
        var superCalled = false

        try {
            CrashLogFile.log(TAG, "onCreate start")

            // Install splash screen BEFORE super.onCreate()
            val splashScreen = installSplashScreen()
            splashScreen.setKeepOnScreenCondition { !SplashViewModel.isMlReady }

            super.onCreate(savedInstanceState)
            superCalled = true

            // Haptic confirmation for low-vision users
            try {
                hapticManager = HapticManager(applicationContext)
                hapticManager?.vibrateTap()
            } catch (e: Throwable) {
                Log.e(TAG, "HapticManager init failed: ${e.message}")
            }

            activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
            setContentView(activityMainBinding!!.root)

            // Verify NavHostFragment is present (non-fatal if missing)
            try {
                supportFragmentManager.findFragmentById(R.id.fragment_container) as? NavHostFragment
            } catch (e: Throwable) {
                Log.e(TAG, "NavHostFragment lookup failed: ${e.message}")
            }

            // Back press: finish the activity
            onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    finish()
                }
            })

            CrashLogFile.log(TAG, "onCreate completed successfully")

        } catch (e: Throwable) {
            // ═══ CRITICAL SAFETY NET ═══
            Log.e(TAG, "FATAL onCreate crash: ${e.javaClass.simpleName}: ${e.message}", e)
            CrashLogFile.logError(TAG, "FATAL onCreate crash", e)
            CrashLogFile.flush()

            // ONLY call super.onCreate() if it hasn't been called yet.
            // If superCalled is true, the activity is already in a valid state
            // and we can just replace the content view with the error screen.
            if (!superCalled) {
                try {
                    super.onCreate(savedInstanceState)
                    superCalled = true
                } catch (_: Throwable) {
                    // super.onCreate() itself crashed — can't recover the activity.
                    // Log and bail out — the system will show "Vyze has stopped".
                    Log.e(TAG, "super.onCreate() failed in catch block — unrecoverable")
                    return
                }
            }

            // Build a fallback error screen programmatically
            try {
                val errorText = buildString {
                    appendLine("VYZE LAUNCH ERROR")
                    appendLine()
                    appendLine("${e.javaClass.simpleName}: ${e.message}")
                    appendLine()
                    appendLine("Stack trace:")
                    appendLine(e.stackTraceToString())
                }

                val scrollView = ScrollView(this)
                val textView = TextView(this).apply {
                    text = errorText
                    setTextColor(Color.RED)
                    setBackgroundColor(Color.BLACK)
                    textSize = 12f
                    setPadding(32, 32, 32, 32)
                    setOnLongClickListener {
                        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        clipboard.setPrimaryClip(
                            android.content.ClipData.newPlainText("crash", errorText)
                        )
                        Toast.makeText(this@MainActivity, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        true
                    }
                }

                scrollView.addView(textView)
                setContentView(scrollView)
            } catch (e2: Throwable) {
                // Even the error screen failed — log it and show a toast as last resort
                Log.e(TAG, "Error screen itself crashed: ${e2.message}")
                try {
                    Toast.makeText(this, "VYZE CRASH: ${e.message}", Toast.LENGTH_LONG).show()
                } catch (_: Throwable) {
                    // Nothing more we can do
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        hapticManager?.cancel()
    }

    companion object {
        private const val TAG = "MainActivity"
    }
}
