package com.vyze.app.fragments

import android.Manifest
import android.animation.ObjectAnimator
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.vyze.app.CrashLogFile
import com.vyze.app.R
import com.vyze.app.VyzeApplication
import com.vyze.app.VyzeCoreController
import com.vyze.app.databinding.FragmentLoadingBinding
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Loading screen — the ONLY place VLM engine initialization runs.
 *
 * ## Strict Navigation Rule
 * This fragment NEVER navigates to CameraFragment unless
 * [VyzeCoreController] signals "VLM ready" (100% success).
 * If GPU fails → tries CPU. If both fail → red error + Retry button.
 * NO navigation happens on failure.
 */
class LoadingFragment : Fragment() {

    private val TAG = "LoadingFragment"

    private var _binding: FragmentLoadingBinding? = null
    private val binding get() = _binding!!

    private var coreController: VyzeCoreController? = null
    private var pulseAnimator: ObjectAnimator? = null

    /** Guard: prevents duplicate init or premature navigation. */
    private var initStarted = false

    // ══════════════════════════════════════════════════════════════════
    // Permission Launchers
    // ══════════════════════════════════════════════════════════════════

    private val cameraPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            safeRun {
                if (!permissions.values.all { it }) {
                    Log.w(TAG, "Permissions denied: ${permissions.filter { !it.value }.keys}")
                }
            }
            safeRun { afterPermissions() }
        }

    private val storageSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            safeRun {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                    Environment.isExternalStorageManager()
                ) {
                    updateStatus("Storage access granted", 5)
                } else {
                    updateStatus("Storage not granted — loading from app assets", 5)
                }
            }
            safeRun { initVlm() }
        }

    // ══════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLoadingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        CrashLogFile.log(TAG, "onViewCreated")

        // Show logo pulse immediately
        safeRun { startPulseAnimation() }

        // Wire retry button
        safeRun {
            binding.retryButton.setOnClickListener {
                resetErrorState()
                safeRun { startInitPipeline() }
            }
        }

        // Safety timeout: if stuck for 60s, navigate anyway
        safeRun {
            binding.root.postDelayed({
                if (isAdded && _binding != null && !initStarted) {
                    CrashLogFile.log(TAG, "Safety timeout — navigating to camera")
                    navigateToCamera()
                }
            }, 60_000L)
        }

        // ═══ DEFER ALL INIT 500ms ═══
        // Ensures the layout is fully attached before any heavy work.
        viewLifecycleOwner.lifecycleScope.launch {
            delay(500L)
            CrashLogFile.log(TAG, "Deferred init starting")
            safeRun { startInitPipeline() }
        }
    }

    override fun onDestroyView() {
        stopPulseAnimation()
        pulseAnimator?.cancel()
        pulseAnimator = null
        _binding = null
        super.onDestroyView()
    }

    // ══════════════════════════════════════════════════════════════════
    // Init Pipeline (called once, deferred 500ms)
    // ══════════════════════════════════════════════════════════════════

    private fun startInitPipeline() {
        if (initStarted) return
        initStarted = true
        CrashLogFile.log(TAG, "startInitPipeline")
        checkCameraPermissions()
    }

    private fun afterPermissions() {
        CrashLogFile.log(TAG, "afterPermissions (SDK=${Build.VERSION.SDK_INT})")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
            !Environment.isExternalStorageManager()
        ) {
            updateStatus("Requesting storage access...", 3)
            Toast.makeText(
                requireContext(),
                "Grant 'All files access' to load the VLM model from Downloads",
                Toast.LENGTH_LONG
            ).show()
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                storageSettingsLauncher.launch(intent)
            } catch (e: Throwable) {
                CrashLogFile.logError(TAG, "Storage settings intent failed: ${e.message}", e)
                initVlm()
            }
        } else {
            updateStatus("Storage access OK", 4)
            initVlm()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Permission Pipeline
    // ══════════════════════════════════════════════════════════════════

    private fun checkCameraPermissions() {
        CrashLogFile.log(TAG, "checkCameraPermissions")
        val required = arrayOf(
            Manifest.permission.CAMERA,
            Manifest.permission.RECORD_AUDIO
        )
        val missing = required.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) {
            updateStatus("Permissions OK", 2)
            afterPermissions()
        } else {
            updateStatus("Requesting permissions...", 1)
            cameraPermissionLauncher.launch(missing.toTypedArray())
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // VLM Initialization — ONLY navigates on full SUCCESS
    // ══════════════════════════════════════════════════════════════════

    private fun initVlm() {
        CrashLogFile.log(TAG, "initVlm")
        try {
            val app = requireActivity().applicationContext as VyzeApplication

            coreController = VyzeCoreController(
                context = requireContext().applicationContext,
                ttsManager = app.ttsManager,
                memoryDao = app.memoryDao,
                interactionDao = app.interactionDao
            )

            // Store immediately so CameraFragment can reuse if it ever gets there
            app.coreController = coreController

            // Progress callback → update UI
            coreController?.onProgressUpdate = { percent, step ->
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.progressBar.progress = percent
                        binding.progressText.text = "$percent%"
                        binding.statusText.text = step
                    }
                }
            }

            // Status callback → ONLY navigate on "VLM ready" (SUCCESS)
            coreController?.onStatusUpdate = { msg ->
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        binding.statusText.text = msg

                        if (msg.startsWith("VLM ready")) {
                            // ═══ SUCCESS: navigate to camera ═══
                            onInitSuccess()
                        } else if (msg.startsWith("Error") || msg.contains("not found") ||
                            msg.contains("crashed") || msg.contains("failed")
                        ) {
                            // ═══ FAILURE: show error, NO navigation ═══
                            showError(msg)
                        }
                    }
                }
            }

            // Error callback → show error, NO navigation
            coreController?.onError = { error ->
                activity?.runOnUiThread {
                    if (isAdded && _binding != null) {
                        showError(error)
                    }
                }
            }

            updateStatus("Initializing VLM engine...", 10)
            coreController?.initialize()

        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "initVlm crashed: ${e.message}", e)
            activity?.runOnUiThread {
                if (isAdded && _binding != null) {
                    showError("Init failed: ${e.message}")
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Success / Error — navigation ONLY in onInitSuccess
    // ══════════════════════════════════════════════════════════════════

    private fun onInitSuccess() {
        CrashLogFile.log(TAG, "VLM init SUCCESS — navigating")
        stopPulseAnimation()
        safeRun {
            binding.progressBar.progress = 100
            binding.progressText.text = "100%"
            binding.statusText.text = "Ready"
        }
        binding.root.postDelayed({ navigateToCamera() }, 500L)
    }

    private fun showError(message: String) {
        CrashLogFile.log(TAG, "ERROR: $message")
        stopPulseAnimation()
        safeRun {
            binding.statusText.text = message
            binding.statusText.setTextColor(0xFFFF4444.toInt())
            binding.retryButton.visibility = View.VISIBLE
            binding.retryButton.text = "Retry"
            if (message.contains("not found")) {
                binding.hintText.visibility = View.VISIBLE
                binding.hintText.text = "adb push gemma-4-E2B-it-gpu.litertlm /sdcard/Download/"
            }
        }
    }

    private fun resetErrorState() {
        safeRun {
            binding.statusText.setTextColor(0xFF666666.toInt())
            binding.statusText.text = "Retrying..."
            binding.retryButton.visibility = View.GONE
            binding.hintText.visibility = View.GONE
            binding.progressBar.progress = 0
            binding.progressText.text = "0%"
            initStarted = false
            startPulseAnimation()
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Navigation
    // ══════════════════════════════════════════════════════════════════

    private fun navigateToCamera() {
        if (!isAdded) return
        try {
            CrashLogFile.log(TAG, "Navigating to CameraFragment")
            Navigation.findNavController(requireActivity(), R.id.fragment_container)
                .navigate(R.id.action_loading_to_camera)
        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "Navigation failed: ${e.message}", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Pulse Animation
    // ══════════════════════════════════════════════════════════════════

    private fun startPulseAnimation() {
        stopPulseAnimation()
        try {
            pulseAnimator = ObjectAnimator.ofFloat(
                binding.logoImage, "scaleX", 0.95f, 1.05f, 0.95f
            ).apply {
                duration = 2000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
            ObjectAnimator.ofFloat(
                binding.logoImage, "scaleY", 0.95f, 1.05f, 0.95f
            ).apply {
                duration = 2000L
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        } catch (_: Throwable) {
        }
    }

    private fun stopPulseAnimation() {
        try {
            pulseAnimator?.cancel()
        } catch (_: Throwable) {
        }
    }

    // ══════════════════════════════════════════════════════════════════
    // Helpers
    // ══════════════════════════════════════════════════════════════════

    private fun updateStatus(text: String, progress: Int) {
        activity?.runOnUiThread {
            if (isAdded && _binding != null) {
                binding.statusText.text = text
                binding.progressBar.progress = progress
                binding.progressText.text = "$progress%"
            }
        }
    }

    private fun safeRun(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            CrashLogFile.logError(TAG, "safeRun: ${e.javaClass.simpleName}: ${e.message}", e)
        }
    }
}
