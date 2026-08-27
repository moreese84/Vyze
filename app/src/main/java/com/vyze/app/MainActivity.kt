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

package com.vyze.app

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.vyze.app.databinding.ActivityMainBinding

/**
 * Main entry point into our app. This app follows the single-activity pattern, and all
 * functionality is implemented in the form of fragments.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private lateinit var hapticManager: HapticManager

    override fun onCreate(savedInstanceState: Bundle?) {
        // Install splash screen BEFORE super.onCreate() — reads Theme.App.Starting
        // from the manifest and renders the dark background with the Vyze mark.
        val splashScreen = installSplashScreen()

        // Hold the splash screen visible until the ML pipeline has finished
        // initializing on the background thread.  Without this, the splash
        // dismisses instantly and the user sees a dead camera preview for
        // 1-3 seconds while models bind — especially confusing for low-vision
        // users who rely on haptic/TTS confirmation.
        splashScreen.setKeepOnScreenCondition { !SplashViewModel.isMlReady }

        super.onCreate(savedInstanceState)

        // ── Immediate haptic confirmation ──────────────────────────────
        // Low-vision users need tactile proof that the process is alive
        // before ML models finish binding. This fires instantly on launch
        // while the splash is still held by setKeepOnScreenCondition.
        hapticManager = HapticManager(applicationContext)
        hapticManager.vibrateTap()

        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        activityMainBinding.navigation.setupWithNavController(navController)
        activityMainBinding.navigation.setOnNavigationItemReselectedListener {
            // ignore the reselection
        }
    }

    override fun onBackPressed() {
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::hapticManager.isInitialized) hapticManager.cancel()
    }
}
