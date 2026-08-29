package com.vyze.app.fragments

import android.annotation.SuppressLint
import android.content.res.Configuration
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import androidx.navigation.Navigation
import com.vyze.app.*
import com.vyze.app.data.MemoryDao
import com.vyze.app.data.ScanRepository
import com.vyze.app.delegates.CameraSetupDelegate
import com.vyze.app.delegates.GestureRouter
import com.vyze.app.databinding.FragmentCameraBinding
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Main camera fragment for Vyze accessibility app — VLM Snapshot Mode.
 *
 * ## Architecture
 * This fragment is a thin lifecycle shell that delegates all heavy lifting to:
 *
 * - **[CameraSetupDelegate]** — CameraX lifecycle, preview binding, ImageCapture for snapshots
 * - **[VyzeCoreController]** — Central VLM pipeline: snapshot → prompt → inference → TTS
 * - **[GestureRouter]** — Gesture-to-action routing (tap/double-tap/long-press)
 * - **[TtsViewModel]** — Singleton TTSManager across fragments
 *
 * ## Snapshot Execution Mode
 * The VLM runs **on-demand only** — never continuously. Each tap or volume key
 * press triggers a single ImageCapture.takePicture(), feeds the bitmap into the
 * VLM, and streams the response to TTS.
 */
class CameraFragment : Fragment() {

    private val TAG = "CameraFragment"

    private var _fragmentCameraBinding: FragmentCameraBinding? = null
    private val fragmentCameraBinding get() = _fragmentCameraBinding!!

    private val viewModel: MainViewModel by activityViewModels()
    private val ttsViewModel: TtsViewModel by activityViewModels()
    private val splashViewModel: SplashViewModel by viewModels()

    // ── Delegates ─────────────────────────────────────────────────────

    private lateinit var cameraSetup: CameraSetupDelegate
    private lateinit var gestureRouter: GestureRouter

    // ── VLM Pipeline ─────────────────────────────────────────────────

    private lateinit var coreController: VyzeCoreController
    private lateinit var memoryDao: MemoryDao

    // ── Managers ──────────────────────────────────────────────────────

    private lateinit var ttsManager: TTSManager
    private lateinit var hapticManager: HapticManager
    private lateinit var flashlightManager: FlashlightManager

    // ── Executors & Handlers ──────────────────────────────────────────

    private lateinit var backgroundExecutor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())

    // ── State ─────────────────────────────────────────────────────────

    @Volatile
    private var isCameraActive = false

    // ── Snapshot Tap Feedback ─────────────────────────────────────────

    private val snapshotTapFeedback = object : Runnable {
        override fun run() {
            // No-op: just a placeholder for future tap feedback animation
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentCameraBinding = FragmentCameraBinding.inflate(inflater, container, false)
        return fragmentCameraBinding.root
    }

    @SuppressLint("MissingPermission")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        backgroundExecutor = Executors.newSingleThreadExecutor()

        // ── Initialize Managers ──────────────────────────────────────
        ttsManager = ttsViewModel.ttsManager
        hapticManager = HapticManager(requireContext().applicationContext)
        flashlightManager = FlashlightManager()

        // ── Initialize Memory + VLM Pipeline ─────────────────────────
        // Reuse the VyzeCoreController that LoadingFragment already initialized.
        // If it's not available (e.g. deep link), create a fallback one.
        val app = requireActivity().applicationContext as VyzeApplication
        memoryDao = app.memoryDao

        coreController = app.coreController ?: VyzeCoreController(
            context = requireContext().applicationContext,
            ttsManager = ttsManager,
            memoryDao = memoryDao
        )

        coreController.onInferenceComplete = { response ->
            activity?.runOnUiThread {
                try {
                    if (isAdded && _fragmentCameraBinding != null) {
                        Log.d(TAG, "VLM response: ${response.take(100)}...")
                        fragmentCameraBinding.diagnosticOverlay.text = "Ready"
                        fragmentCameraBinding.diagnosticOverlay.setTextColor(0xFF00FF00.toInt())
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "onInferenceComplete UI error: ${e.message}")
                }
            }
        }

        // Show engine status in the overlay
        try {
            val backend = coreController.getEngineBackend()
            if (coreController.isEngineReady()) {
                fragmentCameraBinding.diagnosticOverlay.text = "Ready [$backend]"
                fragmentCameraBinding.diagnosticOverlay.setTextColor(0xFF00FF00.toInt())
            } else {
                fragmentCameraBinding.diagnosticOverlay.text = "VLM not ready — tap to retry"
                fragmentCameraBinding.diagnosticOverlay.setTextColor(0xFFFF4444.toInt())
                fragmentCameraBinding.diagnosticOverlay.setOnClickListener {
                    coreController.onStatusUpdate = { msg ->
                        activity?.runOnUiThread {
                            if (isAdded && _fragmentCameraBinding != null) {
                                fragmentCameraBinding.diagnosticOverlay.text = msg
                                val isErr = msg.startsWith("Error") || msg.contains("failed")
                                fragmentCameraBinding.diagnosticOverlay.setTextColor(
                                    if (isErr) 0xFFFF4444.toInt() else 0xFF00FF00.toInt()
                                )
                            }
                        }
                    }
                    coreController.initialize()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Engine status UI error: ${e.message}")
        }

        // ── Initialize Camera ────────────────────────────────────────
        cameraSetup = CameraSetupDelegate()
        cameraSetup.setContext(requireContext().applicationContext)

        // ── Initialize Gesture Router ────────────────────────────────
        gestureRouter = GestureRouter(
            context = requireContext(),
            ttsManager = ttsManager,
            hapticManager = hapticManager,
            colorAnalyzer = ColorAnalyzer(),
            scanRepository = ScanRepository(requireContext().applicationContext),
            mainHandler = mainHandler
        )

        // Single tap → trigger VLM snapshot
        gestureRouter.onSingleTapAction = { x, y ->
            mainHandler.removeCallbacks(snapshotTapFeedback)
            triggerVlmSnapshot("User tapped at position (${x.toInt()}, ${y.toInt()})")
        }

        // Double tap → describe surroundings
        gestureRouter.onDoubleTapAction = {
            triggerVlmSnapshot("Describe what is in front of me and around me for navigation.")
        }

        // Long press → detailed scene description
        gestureRouter.onLongPressAction = {
            triggerVlmSnapshot("Give me a detailed description of my surroundings including obstacles, furniture, and navigation paths.")
        }

        gestureRouter.attach(fragmentCameraBinding.cameraContainer)

        // ── Initialize Camera on Background Thread ───────────────────
        backgroundExecutor.execute {
            fragmentCameraBinding.viewFinder.post {
                setUpCamera()
            }
        }

        // ── Onboarding ───────────────────────────────────────────────
        setupOnboarding()

        // ── Volume Key Support ───────────────────────────────────────
        setupVolumeKeyTrigger()

        // ── Initial Status ───────────────────────────────────────────
        fragmentCameraBinding.diagnosticOverlay.text = "Ready"
        fragmentCameraBinding.diagnosticOverlay.setTextColor(0xFF00FF00.toInt())
    }

    override fun onResume() {
        super.onResume()
        // Permissions are handled by LoadingFragment before navigating here.
        // Only re-request if somehow we got here without permissions.
        if (!PermissionsFragment.hasPermissions(requireContext())) {
            Log.w(TAG, "Permissions missing — requesting via PermissionsFragment")
            try {
                Navigation.findNavController(requireActivity(), R.id.fragment_container)
                    .navigate(CameraFragmentDirections.actionCameraToPermissions())
            } catch (e: Exception) {
                Log.e(TAG, "Navigation to PermissionsFragment failed: ${e.message}")
            }
        }

        if (cameraSetup.cameraProvider != null) {
            cameraSetup.rebindCamera(
                requireContext(), this,
                fragmentCameraBinding.viewFinder
            )
        }

        isCameraActive = true
    }

    override fun onPause() {
        super.onPause()
        isCameraActive = false
        cameraSetup.releaseCamera()
    }

    override fun onDestroyView() {
        _fragmentCameraBinding = null
        super.onDestroyView()

        if (this::gestureRouter.isInitialized) gestureRouter.detach()

        // CRITICAL: Do NOT destroy the shared coreController from VyzeApplication.
        // It holds the VLM engine state that LoadingFragment initialized.
        // Only destroy if we created a local fallback instance.
        val app = try {
            requireActivity().applicationContext as VyzeApplication
        } catch (e: Exception) { null }
        if (app?.coreController != coreController && this::coreController.isInitialized) {
            // This was a locally-created fallback — safe to destroy
            coreController.destroy()
        }

        backgroundExecutor.shutdown()
        backgroundExecutor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)

        if (this::ttsManager.isInitialized) ttsManager.onDestroy()
        if (this::hapticManager.isInitialized) hapticManager.cancel()

        cameraSetup.destroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        cameraSetup.updateRotation(fragmentCameraBinding.viewFinder.display)
    }

    // ══════════════════════════════════════════════════════════════════
    // Camera Setup
    // ══════════════════════════════════════════════════════════════════

    private fun setUpCamera() {
        cameraSetup.setupCamera(
            context = requireContext(),
            lifecycleOwner = this,
            previewView = fragmentCameraBinding.viewFinder,
            flashlightMgr = flashlightManager
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // VLM Snapshot Trigger
    // ══════════════════════════════════════════════════════════════════

    /**
     * Capture a snapshot and run VLM inference.
     *
     * Uses ImageCapture.takePicture() to get a single frame, then feeds it
     * into VyzeCoreController for on-demand VLM inference.
     */
    private fun triggerVlmSnapshot(query: String) {
        CrashLogFile.log(TAG, "triggerVlmSnapshot: engineReady=${coreController.isEngineReady()}, inferring=${coreController.isCurrentlyInferring()}")

        if (!coreController.isEngineReady()) {
            fragmentCameraBinding.diagnosticOverlay.text = "Model still loading..."
            fragmentCameraBinding.diagnosticOverlay.setTextColor(0xFFFFAA00.toInt())
            Log.d(TAG, "VLM not ready yet")
            return
        }

        if (coreController.isCurrentlyInferring()) {
            fragmentCameraBinding.diagnosticOverlay.text = "Already analyzing..."
            fragmentCameraBinding.diagnosticOverlay.setTextColor(0xFFFFAA00.toInt())
            Log.d(TAG, "Engine busy — ignoring tap")
            return
        }

        hapticManager.vibrateTap()
        fragmentCameraBinding.diagnosticOverlay.text = "Capturing snapshot..."
        CrashLogFile.log(TAG, "Calling cameraSetup.takeSnapshot()")

        cameraSetup.takeSnapshot(
            onBitmap = { bitmap ->
                try {
                    CrashLogFile.log(TAG, "onBitmap callback: ${bitmap.width}x${bitmap.height}")

                    // If engine is already inferring, recycle this bitmap immediately
                    // to prevent OOM from accumulating full-res bitmaps.
                    if (coreController.isCurrentlyInferring()) {
                        CrashLogFile.log(TAG, "Engine busy — recycling bitmap")
                        bitmap.recycle()
                        activity?.runOnUiThread {
                            if (isAdded && _fragmentCameraBinding != null) {
                                fragmentCameraBinding.diagnosticOverlay.text = "Already analyzing..."
                            }
                        }
                        return@takeSnapshot
                    }

                    activity?.runOnUiThread {
                        if (isAdded && _fragmentCameraBinding != null) {
                            fragmentCameraBinding.diagnosticOverlay.text = "Analyzing..."
                        }
                    }
                    CrashLogFile.log(TAG, "Calling coreController.triggerSnapshot()")
                    coreController.triggerSnapshot(bitmap, query)
                } catch (e: Throwable) {
                    CrashLogFile.logError(TAG, "onBitmap callback error: ${e.javaClass.simpleName}: ${e.message}", e)
                    try { bitmap.recycle() } catch (_: Throwable) {}
                }
            },
            onError = { error ->
                CrashLogFile.logError(TAG, "Snapshot failed: $error")
                Log.e(TAG, "Snapshot failed: $error")
                activity?.runOnUiThread {
                    if (isAdded && _fragmentCameraBinding != null) {
                        ttsManager.speakImmediate("Failed to capture image. $error")
                        fragmentCameraBinding.diagnosticOverlay.text = "Snapshot failed"
                    }
                }
            }
        )
    }

    // ══════════════════════════════════════════════════════════════════
    // Volume Key Trigger
    // ══════════════════════════════════════════════════════════════════

    private fun setupVolumeKeyTrigger() {
        fragmentCameraBinding.viewFinder.isFocusableInTouchMode = true
        fragmentCameraBinding.viewFinder.requestFocus()
        fragmentCameraBinding.viewFinder.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN) {
                when (keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN -> {
                        triggerVlmSnapshot("User pressed volume key to trigger scene description.")
                        true
                    }
                    else -> false
                }
            } else false
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Onboarding
    // ══════════════════════════════════════════════════════════════════

    private fun setupOnboarding() {
        val prefs = requireContext().getSharedPreferences("vyze_prefs", 0)
        val isFirstLaunch = prefs.getBoolean("first_launch", true)

        if (isFirstLaunch) {
            fragmentCameraBinding.onboardingOverlay.visibility = View.VISIBLE
            mainHandler.postDelayed({
                if (isAdded) {
                    ttsManager.speakImmediate(
                        "Welcome to Vyze. Tap anywhere to hear what's in front of you. " +
                        "Double tap for a scene description. Long press for a detailed description. " +
                        "You can also use volume keys to trigger descriptions. Tap to begin."
                    )
                }
            }, 1500L)

            fragmentCameraBinding.onboardingOverlay.setOnClickListener {
                prefs.edit().putBoolean("first_launch", false).apply()
                fragmentCameraBinding.onboardingOverlay.visibility = View.GONE
                ttsManager.speakImmediate("Ready. Tap anywhere to start.")
            }
        }
    }
}
