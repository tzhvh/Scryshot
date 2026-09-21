/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.ScryerService
import io.github.tzhvh.scryernext.databinding.ActivitySetupHubBinding
import io.github.tzhvh.scryernext.permission.MediaAccess
import io.github.tzhvh.scryernext.permission.PermissionHelper

/**
 * ADR 0008 §4 — the setup hub ("Setup status"): the surface that owns every
 * non-happy path after first run. Entry points: the Settings "Setup &
 * permissions" row, Android 14 partial photo access (this is the only screen
 * that says "you only shared selected photos" and offers re-prompting), and
 * revocation re-entry — `HomeFragment.onResume` routes a missing *required*
 * grant here once per recurrence, dismissible (backing out without granting
 * records the dismissal; a later revocation re-nudges).
 *
 * Re-renders on every resume, mirroring the old flow's re-check discipline:
 * returning from any system settings screen re-derives state live.
 */
class SetupHubActivity : AppCompatActivity() {

    private lateinit var binding: ActivitySetupHubBinding

    /**
     * Set after the hub's own media request comes back denied. Permanent
     * denial is only detectable as "denied AND no longer showing rationale" —
     * without this latch, a never-asked user (rationale also false) would be
     * offered "Open Settings" before ever seeing the system dialog.
     */
    private var mediaDeniedOnce: Boolean = false

    /** Same latch for the notifications row (drives its Open Settings fallback). */
    private var notificationsDeniedOnce: Boolean = false

    /**
     * Non-null only when HomeFragment auto-routed here for a degraded
     * required grant — such visits record the nudge dismissal on exit;
     * voluntary (Settings) visits never do.
     */
    private val nudgeSignature: String? by lazy {
        intent?.getStringExtra(EXTRA_NUDGE_SIGNATURE)
    }

    private val readMediaLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        render()
    }

    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) {
            notificationsDeniedOnce = true
        }
        render()
    }

    private val overlayLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (PermissionHelper.hasOverlayPermission(this)) {
            SetupActions.enableFloatingButton(this)
        }
        render()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState != null) {
            mediaDeniedOnce = savedInstanceState.getBoolean(KEY_MEDIA_DENIED_ONCE, false)
            notificationsDeniedOnce =
                    savedInstanceState.getBoolean(KEY_NOTIFICATIONS_DENIED_ONCE, false)
        }
        binding = ActivitySetupHubBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.mediaAction.setOnClickListener {
            if (binding.mediaAction.tag == SetupRowAction.OPEN_SETTINGS) {
                SetupActions.openAppDetails(this)
            } else {
                requestMediaAccess()
            }
        }
        binding.notificationsAction.setOnClickListener {
            if (binding.notificationsAction.tag == SetupRowAction.OPEN_SETTINGS) {
                launchNotificationSettings()
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                postNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        binding.overlayAction.setOnClickListener {
            PermissionHelper.getOverlayPermissionIntent(this)?.let { intent ->
                overlayLauncher.launch(intent)
            }
        }

        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_MEDIA_DENIED_ONCE, mediaDeniedOnce)
        outState.putBoolean(KEY_NOTIFICATIONS_DENIED_ONCE, notificationsDeniedOnce)
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun render() {
        renderMedia()
        renderNotifications()
        renderOverlay()
    }

    private fun renderMedia() {
        val context = this
        when (PermissionHelper.getMediaAccess(context)) {
            MediaAccess.GRANTED -> {
                bindRowState(binding.mediaState, binding.mediaAction,
                        R.string.setup_hub_state_granted, R.color.primaryTeal, action = null)
                binding.mediaAction.tag = SetupRowAction.NONE
                binding.mediaPartialNote.visibility = View.GONE
            }
            MediaAccess.PARTIAL -> {
                bindRowState(binding.mediaState, binding.mediaAction,
                        R.string.setup_hub_state_partial, R.color.errorRed,
                        action = R.string.setup_hub_action_review_photos)
                // Clear any stale tag from a previous DENIED-permanent render —
                // otherwise "Review photos" would deep-link to App Details.
                binding.mediaAction.tag = SetupRowAction.NONE
                binding.mediaPartialNote.text = getString(R.string.setup_hub_media_partial_note,
                        getString(R.string.app_full_name))
                binding.mediaPartialNote.visibility = View.VISIBLE
            }
            MediaAccess.DENIED -> {
                bindRowState(binding.mediaState, binding.mediaAction,
                        R.string.setup_hub_state_not_granted, R.color.errorRed,
                        action = R.string.setup_hub_action_set_up)
                binding.mediaPartialNote.visibility = View.GONE

                val permanent = mediaDeniedOnce && !shouldShowRequestPermissionRationale(
                        PermissionHelper.getPrimaryReadMediaPermission())
                if (permanent) {
                    binding.mediaAction.text = getString(R.string.setup_hub_action_open_settings)
                    binding.mediaAction.tag = SetupRowAction.OPEN_SETTINGS
                    binding.mediaPartialNote.visibility = View.VISIBLE
                    binding.mediaPartialNote.text =
                            getString(R.string.setup_hub_media_permanent_note)
                } else {
                    binding.mediaAction.text = getString(R.string.setup_hub_action_set_up)
                    binding.mediaAction.tag = SetupRowAction.NONE
                }
            }
        }
    }

    private fun renderNotifications() {
        val enabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        binding.notificationsRow.visibility = if (enabled) View.VISIBLE else View.GONE
        if (!enabled) {
            return
        }
        if (PermissionHelper.hasPostNotificationsPermission(this)) {
            bindRowState(binding.notificationsState, binding.notificationsAction,
                    R.string.setup_hub_state_granted, R.color.primaryTeal, action = null)
            binding.notificationsAction.tag = SetupRowAction.NONE
            return
        }
        // Permanent denial: the runtime request would auto-deny without ever
        // showing UI, so the row falls back to the app's native notification
        // settings — the only control the OS still offers.
        val permanent = notificationsDeniedOnce && !shouldShowRequestPermissionRationale(
                android.Manifest.permission.POST_NOTIFICATIONS)
        if (permanent) {
            bindRowState(binding.notificationsState, binding.notificationsAction,
                    R.string.setup_hub_state_off, R.color.grey50,
                    action = R.string.setup_hub_action_open_settings)
            binding.notificationsAction.tag = SetupRowAction.OPEN_SETTINGS
        } else {
            bindRowState(binding.notificationsState, binding.notificationsAction,
                    R.string.setup_hub_state_off, R.color.grey50,
                    action = R.string.setup_hub_action_turn_on)
            binding.notificationsAction.tag = SetupRowAction.NONE
        }
    }

    private fun renderOverlay() {
        if (PermissionHelper.hasOverlayPermission(this)) {
            bindRowState(binding.overlayState, binding.overlayAction,
                    R.string.setup_hub_state_on, R.color.primaryTeal, action = null)
        } else {
            bindRowState(binding.overlayState, binding.overlayAction,
                    R.string.setup_hub_state_off, R.color.grey50,
                    action = R.string.setup_hub_action_enable)
        }
    }

    private fun bindRowState(stateView: android.widget.TextView, actionButton: View,
                             stateRes: Int, colorRes: Int, action: Int?) {
        stateView.setText(stateRes)
        stateView.setTextColor(ContextCompat.getColor(this, colorRes))
        if (action == null) {
            actionButton.visibility = View.GONE
        } else {
            actionButton.visibility = View.VISIBLE
            (actionButton as android.widget.Button).setText(action)
        }
    }

    private fun requestMediaAccess() {
        readMediaLauncher.launch(PermissionHelper.getReadMediaPermissionStrings())
    }

    /** The app's native notification-settings page (POST_NOTIFICATIONS recovery). */
    private fun launchNotificationSettings() {
        val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        intent.putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
        startActivity(intent)
    }

    override fun onDestroy() {
        // ADR 0008 §4 — a *nudged* visit records the dismissal on any exit
        // (back, gesture nav, predictive back) while its condition persists,
        // so HomeFragment won't re-route for this recurrence. A voluntary
        // Settings visit never records one. Resolving the condition clears
        // the signature (Home does this on resume), so a later recurrence
        // re-nudges.
        val signature = nudgeSignature
        if (isFinishing && signature != null) {
            val prefs = OnboardingPrefs.getInstance(this)
            when (PermissionHelper.getMediaAccess(this)) {
                MediaAccess.DENIED -> prefs.dismissHubNudge(OnboardingPrefs.NUDGE_MEDIA_DENIED)
                MediaAccess.PARTIAL -> prefs.dismissHubNudge(OnboardingPrefs.NUDGE_MEDIA_PARTIAL)
                else -> prefs.clearHubNudge()
            }
        }
        super.onDestroy()
    }

    companion object {
        private const val KEY_MEDIA_DENIED_ONCE = "setup_hub_media_denied_once"
        private const val KEY_NOTIFICATIONS_DENIED_ONCE = "setup_hub_notifications_denied_once"
        private const val EXTRA_NUDGE_SIGNATURE = "setup_hub_nudge_signature"

        /** Voluntary visit (Settings entry point) — never records a dismissal. */
        fun start(context: Context) {
            context.startActivity(Intent(context, SetupHubActivity::class.java))
        }

        /** Auto-routed visit (revocation re-entry) — records the dismissal on exit. */
        fun startNudged(context: Context, signature: String) {
            val intent = Intent(context, SetupHubActivity::class.java)
            intent.putExtra(EXTRA_NUDGE_SIGNATURE, signature)
            context.startActivity(intent)
        }
    }
}
