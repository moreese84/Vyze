/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.vyze.app.fragments

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.vyze.app.MainViewModel
import com.vyze.app.ObjectDetectorHelper
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.vyze.app.databinding.FragmentGalleryBinding
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService

class GalleryFragment : Fragment(), ObjectDetectorHelper.DetectorListener {

    enum class MediaType {
        IMAGE,
        UNKNOWN
    }

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding
        get() = _fragmentGalleryBinding!!
    private lateinit var objectDetectorHelper: ObjectDetectorHelper

    /** Blocking ML operations are performed using this executor */
    private lateinit var backgroundExecutor: ScheduledExecutorService
    private val viewModel: MainViewModel by activityViewModels()

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            // Handle the returned Uri
            uri?.let { mediaUri ->
                when (val mediaType = loadMediaType(mediaUri)) {
                    MediaType.IMAGE -> runDetectionOnImage(mediaUri)
                    MediaType.UNKNOWN -> {
                        updateDisplayView(mediaType)
                        Toast.makeText(
                            requireContext(),
                            "Unsupported data type.",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _fragmentGalleryBinding =
            FragmentGalleryBinding.inflate(inflater, container, false)

        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentGalleryBinding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*", "video/*"))
        }

        initBottomSheetControls()
    }

    override fun onPause() {
        fragmentGalleryBinding.overlay.clear()
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        super.onPause()
    }

    private fun initBottomSheetControls() {
        updateControlsUi()
        // When clicked, lower detection score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.thresholdMinus.setOnClickListener {
            if (viewModel.currentThreshold >= 0.1) {
                viewModel.setThreshold(viewModel.currentThreshold - 0.1f)
                updateControlsUi()
            }
        }

        // When clicked, raise detection score threshold floor
        fragmentGalleryBinding.bottomSheetLayout.thresholdPlus.setOnClickListener {
            if (viewModel.currentThreshold <= 0.8) {
                viewModel.setThreshold(viewModel.currentThreshold + 0.1f)
                updateControlsUi()
            }
        }

        // When clicked, reduce the number of objects that can be detected at a time
        fragmentGalleryBinding.bottomSheetLayout.maxResultsMinus.setOnClickListener {
            if (viewModel.currentMaxResults > 1) {
                viewModel.setMaxResults(viewModel.currentMaxResults - 1)
                updateControlsUi()
            }
        }

        // When clicked, increase the number of objects that can be detected at a time
        fragmentGalleryBinding.bottomSheetLayout.maxResultsPlus.setOnClickListener {
            if (viewModel.currentMaxResults < 5) {
                viewModel.setMaxResults(viewModel.currentMaxResults + 1)
                updateControlsUi()
            }
        }

    }

    // Update the values displayed in the bottom sheet. Reset detector.
    private fun updateControlsUi() {
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        fragmentGalleryBinding.imageResult.visibility = View.GONE
        fragmentGalleryBinding.overlay.clear()
        fragmentGalleryBinding.bottomSheetLayout.maxResultsValue.text =
            viewModel.currentMaxResults.toString()
        fragmentGalleryBinding.bottomSheetLayout.thresholdValue.text =
            String.format("%.2f", viewModel.currentThreshold)

        fragmentGalleryBinding.overlay.clear()
        fragmentGalleryBinding.tvPlaceholder.visibility = View.VISIBLE
    }

    // Load and display the image.
    private fun runDetectionOnImage(uri: Uri) {
        fragmentGalleryBinding.overlay.runningMode = RunningMode.IMAGE
        setUiEnabled(false)
        backgroundExecutor = Executors.newSingleThreadScheduledExecutor()
        updateDisplayView(MediaType.IMAGE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val source = ImageDecoder.createSource(
                requireActivity().contentResolver,
                uri
            )
            ImageDecoder.decodeBitmap(source)
        } else {
            MediaStore.Images.Media.getBitmap(
                requireActivity().contentResolver,
                uri
            )
        }
            .copy(Bitmap.Config.ARGB_8888, true)
            ?.let { bitmap ->
                fragmentGalleryBinding.imageResult.setImageBitmap(bitmap)

                // Run object detection on the input image
                backgroundExecutor.execute {

                    objectDetectorHelper =
                        ObjectDetectorHelper(
                            context = requireContext(),
                            threshold = viewModel.currentThreshold,
                            maxResults = viewModel.currentMaxResults,
                            objectDetectorListener = this
                        )

                    objectDetectorHelper.detectImage(bitmap)
                        ?.let { resultBundle ->
                            activity?.runOnUiThread {
                                fragmentGalleryBinding.overlay.setResults(
                                    resultBundle.detections,
                                    bitmap.height,
                                    bitmap.width,
                                    resultBundle.letterboxPadX,
                                    resultBundle.letterboxPadY
                                )

                                setUiEnabled(true)
                                fragmentGalleryBinding.bottomSheetLayout.inferenceTimeVal.text =
                                    String.format(
                                        "%d ms",
                                        resultBundle.inferenceTime
                                    )
                            }
                        } ?: run {
                        Log.e(TAG, "Error running object detection.")
                    }

                    objectDetectorHelper.clearObjectDetector()
                }
            }
    }

    private fun updateDisplayView(mediaType: MediaType) {
        fragmentGalleryBinding.overlay.clear()
        fragmentGalleryBinding.imageResult.visibility =
            if (mediaType == MediaType.IMAGE) View.VISIBLE else View.GONE
        fragmentGalleryBinding.videoView.visibility = View.GONE
        fragmentGalleryBinding.tvPlaceholder.visibility =
            if (mediaType == MediaType.UNKNOWN) View.VISIBLE else View.GONE
    }

    // Check the type of media that user selected.
    private fun loadMediaType(uri: Uri): MediaType {
        val mimeType = context?.contentResolver?.getType(uri)
        mimeType?.let {
            if (mimeType.startsWith("image")) return MediaType.IMAGE
        }

        return MediaType.UNKNOWN
    }

    private fun setUiEnabled(enabled: Boolean) {
        fragmentGalleryBinding.fabGetContent.isEnabled = enabled
        fragmentGalleryBinding.bottomSheetLayout.thresholdMinus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.thresholdPlus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.maxResultsMinus.isEnabled =
            enabled
        fragmentGalleryBinding.bottomSheetLayout.maxResultsPlus.isEnabled =
            enabled
    }

    private fun detectError() {
        activity?.runOnUiThread {
            fragmentGalleryBinding.progress.visibility = View.GONE
            setUiEnabled(true)
            updateDisplayView(MediaType.UNKNOWN)
        }
    }

    override fun onError(error: String, errorCode: Int) {
        detectError()
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), error, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onResults(resultBundle: ObjectDetectorHelper.ResultBundle) {
        // no-op
    }

    companion object {
        private const val TAG = "GalleryFragment"
    }
}
