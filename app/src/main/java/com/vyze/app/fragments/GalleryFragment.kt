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
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import com.vyze.app.databinding.FragmentGalleryBinding

/**
 * Gallery fragment for viewing images.
 *
 * Simplified for VLM architecture — no object detection or bounding boxes.
 * Users can pick an image from their gallery and the VLM can describe it.
 */
class GalleryFragment : Fragment() {

    private var _fragmentGalleryBinding: FragmentGalleryBinding? = null
    private val fragmentGalleryBinding get() = _fragmentGalleryBinding!!

    private val getContent =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
            uri?.let { mediaUri ->
                val mimeType = context?.contentResolver?.getType(mediaUri)
                if (mimeType?.startsWith("image") == true) {
                    loadImage(mediaUri)
                } else {
                    Toast.makeText(requireContext(), "Unsupported file type.", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _fragmentGalleryBinding = FragmentGalleryBinding.inflate(inflater, container, false)
        return fragmentGalleryBinding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        fragmentGalleryBinding.fabGetContent.setOnClickListener {
            getContent.launch(arrayOf("image/*"))
        }
    }

    override fun onPause() {
        if (fragmentGalleryBinding.videoView.isPlaying) {
            fragmentGalleryBinding.videoView.stopPlayback()
        }
        fragmentGalleryBinding.videoView.visibility = View.GONE
        super.onPause()
    }

    private fun loadImage(uri: Uri) {
        try {
            val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val source = ImageDecoder.createSource(requireActivity().contentResolver, uri)
                ImageDecoder.decodeBitmap(source).copy(Bitmap.Config.ARGB_8888, true)
            } else {
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.getBitmap(requireActivity().contentResolver, uri)
            }

            if (bitmap != null) {
                fragmentGalleryBinding.imageResult.setImageBitmap(bitmap)
                fragmentGalleryBinding.imageResult.visibility = View.VISIBLE
                fragmentGalleryBinding.tvPlaceholder.visibility = View.GONE
            } else {
                Toast.makeText(requireContext(), "Failed to load image.", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load image: ${e.message}", e)
            Toast.makeText(requireContext(), "Failed to load image.", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TAG = "GalleryFragment"
    }
}
