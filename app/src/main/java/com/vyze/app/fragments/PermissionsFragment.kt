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

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.Navigation
import com.vyze.app.R

private val PERMISSIONS_REQUIRED = arrayOf(
    Manifest.permission.CAMERA,
    Manifest.permission.RECORD_AUDIO
)

/**
 * The sole purpose of this fragment is to request permissions and, once granted, display the camera
 * fragment to the user.
 *
 * On Android 11+ (API 30+), also checks [Environment.isExternalStorageManager()]
 * for `MANAGE_EXTERNAL_STORAGE`. This is non-blocking — if the user denies it,
 * we still navigate to camera but the model must be loaded from bundled assets.
 */
class PermissionsFragment : Fragment() {

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            val allGranted = permissions.values.all { it }
            if (allGranted) {
                Toast.makeText(
                    context,
                    "All permissions granted",
                    Toast.LENGTH_LONG
                ).show()
            } else {
                // Check which permissions were denied
                val denied = permissions.filter { !it.value }.keys
                Toast.makeText(
                    context,
                    "Denied permissions: ${denied.joinToString(", ")}",
                    Toast.LENGTH_LONG
                ).show()
            }
            // Always navigate to camera — storage permission is non-blocking
            checkAndRequestStoragePermission()
        }

    /**
     * Launcher for the "All files access" settings page on Android 11+.
     * Non-blocking: navigates to camera regardless of result.
     */
    private val storageSettingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // User returned from Settings — check result and navigate
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
                Environment.isExternalStorageManager()
            ) {
                Toast.makeText(context, "Storage access granted", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(
                    context,
                    "Storage access not granted — model will load from bundled assets",
                    Toast.LENGTH_LONG
                ).show()
            }
            navigateToCamera()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val missingPermissions = PERMISSIONS_REQUIRED.filter {
            ContextCompat.checkSelfPermission(requireContext(), it) !=
                    PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isEmpty()) {
            // Camera/audio permissions already granted — check storage next
            checkAndRequestStoragePermission()
        } else {
            requestPermissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    /**
     * On Android 11+ (API 30+), check if `MANAGE_EXTERNAL_STORAGE` is granted.
     * If not, open the system settings page so the user can enable it.
     * This is non-blocking — we navigate to camera either way.
     */
    private fun checkAndRequestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                Toast.makeText(
                    requireContext(),
                    "For VLM model loading from Downloads, grant 'All files access' in the next screen",
                    Toast.LENGTH_LONG
                ).show()

                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = android.net.Uri.parse("package:${requireContext().packageName}")
                }
                storageSettingsLauncher.launch(intent)
                return
            }
        }
        // Android 10 and below, or already granted — proceed directly
        navigateToCamera()
    }

    private fun navigateToCamera() {
        lifecycleScope.launchWhenStarted {
            Navigation.findNavController(
                requireActivity(),
                R.id.fragment_container
            )
                .navigate(PermissionsFragmentDirections.actionPermissionsToCamera())
        }
    }

    companion object {

        /** Convenience method used to check if all permissions required by this app are granted */
        fun hasPermissions(context: Context) =
            PERMISSIONS_REQUIRED.all {
                ContextCompat.checkSelfPermission(
                    context,
                    it
                ) == PackageManager.PERMISSION_GRANTED
            }
    }
}
