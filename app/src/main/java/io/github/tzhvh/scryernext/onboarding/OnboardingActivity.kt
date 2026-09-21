/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.databinding.ActivityOnboardingBinding
import io.github.tzhvh.scryernext.databinding.OnboardingStepWelcomeBinding

/**
 * ADR 0008 — the first-run wizard. A linear spine: welcome → media access
 * (the only required step) → notifications (optional, API 33+) → floating
 * capture button (optional, last) → done. Launched from `MainActivity` when
 * [OnboardingPrefs.isOnboardingComplete] is false; not dismissible until
 * completed — optional steps are skippable, and the required step's "Not now"
 * continues the wizard (the setup hub owns the missing requirement after).
 *
 * Slice 1 shell: the welcome step and the completion exit. The remaining
 * steps land with the wizard spine (slice 2).
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private var welcomeBinding: OnboardingStepWelcomeBinding? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        showWelcome()
    }

    private fun showWelcome() {
        val step = OnboardingStepWelcomeBinding.inflate(layoutInflater, binding.stepContainer, true)
        welcomeBinding = step

        step.title.text = getString(R.string.setup_welcome_title,
                getString(R.string.app_full_name))
        step.actionButton.setOnClickListener {
            completeOnboarding()
        }
    }

    private fun completeOnboarding() {
        OnboardingPrefs.getInstance(this).setOnboardingComplete()
        finish()
    }

    /**
     * The wizard is the app's front door — back on the first step must not
     * land the user on an un-onboarded Home (MainActivity would immediately
     * re-gate). Later steps navigate back within the wizard instead.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // First step: swallow the press; the wizard owns the screen until done.
    }

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, OnboardingActivity::class.java))
        }
    }
}
