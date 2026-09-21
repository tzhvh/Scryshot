/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.databinding.ActivityOnboardingBinding
import io.github.tzhvh.scryernext.databinding.OnboardingStepDoneBinding
import io.github.tzhvh.scryernext.databinding.OnboardingStepMediaBinding
import io.github.tzhvh.scryernext.databinding.OnboardingStepNotificationsBinding
import io.github.tzhvh.scryernext.databinding.OnboardingStepOverlayBinding
import io.github.tzhvh.scryernext.databinding.OnboardingStepWelcomeBinding
import io.github.tzhvh.scryernext.filemonitor.ExternalScreenshotSync
import io.github.tzhvh.scryernext.permission.MediaAccess
import io.github.tzhvh.scryernext.permission.PermissionHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ADR 0008 — the first-run wizard. A linear spine: welcome → media access
 * (the only required step) → notifications (optional, API 33+) → floating
 * capture button (optional, last) → done. Launched from `MainActivity` when
 * [OnboardingPrefs.isOnboardingComplete] is false; not dismissible until
 * completed. The overlay step is skippable, the media step's "Not now"
 * continues the wizard, and the notifications step has **no app-level skip**:
 * its CTA always surfaces the native consent dialog — that dialog is the only
 * decision surface (see the postNotifications launcher comment), and either
 * outcome advances. The setup hub owns missing requirements afterwards.
 *
 * Routing is [OnboardingFlow]'s (pure, JVM-tested); this activity is the
 * view-binding + launcher plumbing around it. All steps are inflated once
 * into the container and switched by visibility. The media step renders
 * three sub-states on one screen: explainer, declined (rationale + Try
 * again / Not now), and permanently-denied (Open Settings deep link).
 */
class OnboardingActivity : AppCompatActivity() {

    private lateinit var binding: ActivityOnboardingBinding

    private val gates = object : OnboardingGates {
        override fun mediaAccess(): MediaAccess {
            return PermissionHelper.getMediaAccess(applicationContext)
        }

        override fun isNotificationsGranted(): Boolean {
            return PermissionHelper.hasPostNotificationsPermission(applicationContext)
        }

        override fun isOverlayGranted(): Boolean {
            return PermissionHelper.hasOverlayPermission(applicationContext)
        }
    }

    private val flow by lazy { OnboardingFlow(gates) }

    private lateinit var welcomeBinding: OnboardingStepWelcomeBinding
    private lateinit var mediaBinding: OnboardingStepMediaBinding
    private lateinit var notificationsBinding: OnboardingStepNotificationsBinding
    private lateinit var overlayBinding: OnboardingStepOverlayBinding
    private lateinit var doneBinding: OnboardingStepDoneBinding
    private val stepViews = HashMap<OnboardingStep, View>()

    private var currentStep: OnboardingStep = OnboardingStep.WELCOME

    /**
     * Whether the system media dialog has been dismissed at least once this
     * wizard run. Only after that can a DENIED gate be presented as declined /
     * permanently-denied — the plain explainer must render before the first
     * ask (the thing short.html lacked was the *afterwards*; not the reverse).
     */
    private var mediaDialogShown: Boolean = false

    /** The done screen's corpus count fires once; re-entry (onResume) re-binds only. */
    private var doneCountStarted: Boolean = false
    private var doneCountJob: Job? = null

    private val readMediaLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // The result map is advisory; the gate re-derives from the system.
        showStep(flow.resolve(currentStep))
    }

    private val postNotificationsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Notifications policy (user decision, 2026-09-21): the native dialog
        // is the ONLY consent surface — the wizard shows it unconditionally
        // and never offers an app-level skip around it. Once the user has
        // decided natively (either way), the step advances: grant → resolve
        // skips forward; deny → nextAfter, no counter-offer, no nag. Our
        // notification call-paths are permission-uniform either way (notify()
        // drops silently and the bulk job's dataSync FGS promotion is
        // unaffected — the notification is merely not displayed), so denial
        // costs visibility, not lifecycle.
        if (gates.isNotificationsGranted()) {
            showStep(flow.resolve(OnboardingStep.NOTIFICATIONS))
        } else {
            showStep(flow.nextAfter(OnboardingStep.NOTIFICATIONS))
        }
    }

    private val overlaySettingsLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { _ ->
        if (gates.isOverlayGranted()) {
            SetupActions.enableFloatingButton(this)
        }
        showStep(flow.resolve(currentStep))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityOnboardingBinding.inflate(layoutInflater)
        setContentView(binding.root)

        if (savedInstanceState != null) {
            currentStep = savedInstanceState.getSerializable(KEY_STEP) as? OnboardingStep
                    ?: OnboardingStep.WELCOME
            mediaDialogShown = savedInstanceState.getBoolean(KEY_MEDIA_DIALOG_SHOWN, false)
            doneCountStarted = savedInstanceState.getBoolean(KEY_DONE_COUNT_STARTED, false)
        }

        inflateSteps()

        if (savedInstanceState == null) {
            showStep(OnboardingStep.WELCOME)
        } else {
            showStep(flow.resolve(currentStep))
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putSerializable(KEY_STEP, currentStep)
        outState.putBoolean(KEY_MEDIA_DIALOG_SHOWN, mediaDialogShown)
        outState.putBoolean(KEY_DONE_COUNT_STARTED, doneCountStarted)
    }

    override fun onResume() {
        super.onResume()
        // Returning from a Settings screen the wizard didn't launch (user
        // granted in the background): re-derive. WELCOME and DONE are stable
        // under resolve; the gated steps re-derive cheaply.
        if (currentStep != OnboardingStep.WELCOME) {
            showStep(flow.resolve(currentStep))
        }
    }

    private fun inflateSteps() {
        welcomeBinding = OnboardingStepWelcomeBinding.inflate(layoutInflater,
                binding.stepContainer, true)
        mediaBinding = OnboardingStepMediaBinding.inflate(layoutInflater,
                binding.stepContainer, true)
        notificationsBinding = OnboardingStepNotificationsBinding.inflate(layoutInflater,
                binding.stepContainer, true)
        overlayBinding = OnboardingStepOverlayBinding.inflate(layoutInflater,
                binding.stepContainer, true)
        doneBinding = OnboardingStepDoneBinding.inflate(layoutInflater,
                binding.stepContainer, true)

        stepViews[OnboardingStep.WELCOME] = welcomeBinding.root
        stepViews[OnboardingStep.MEDIA] = mediaBinding.root
        stepViews[OnboardingStep.NOTIFICATIONS] = notificationsBinding.root
        stepViews[OnboardingStep.OVERLAY] = overlayBinding.root
        stepViews[OnboardingStep.DONE] = doneBinding.root

        bindWelcome()
        bindMedia()
        bindNotifications()
        bindOverlay()
        bindDone()
    }

    private fun bindWelcome() {
        welcomeBinding.title.text = getString(R.string.setup_welcome_title,
                getString(R.string.app_full_name))
        welcomeBinding.actionButton.setOnClickListener {
            showStep(flow.resolve(OnboardingStep.MEDIA))
        }
    }

    private fun bindMedia() {
        mediaBinding.privacyBody.text = getString(R.string.setup_media_privacy,
                getString(R.string.app_full_name))
        mediaBinding.positiveButton.setOnClickListener {
            when (mediaBinding.positiveButton.tag) {
                SetupRowAction.OPEN_SETTINGS -> SetupActions.openAppDetails(this)
                else -> requestMediaAccess()
            }
        }
        mediaBinding.negativeButton.setOnClickListener {
            showStep(flow.nextAfter(OnboardingStep.MEDIA))
        }
    }

    private fun requestMediaAccess() {
        mediaDialogShown = true
        readMediaLauncher.launch(PermissionHelper.getReadMediaPermissionStrings())
    }

    private fun bindNotifications() {
        notificationsBinding.previewTitle.text = getString(
                R.string.setup_notifications_preview_title, getString(R.string.app_full_name))
        notificationsBinding.positiveButton.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                postNotificationsLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun bindOverlay() {
        overlayBinding.lead.text = getString(R.string.setup_overlay_lead,
                getString(R.string.app_full_name))
        overlayBinding.positiveButton.setOnClickListener {
            PermissionHelper.getOverlayPermissionIntent(this)?.let { intent ->
                overlaySettingsLauncher.launch(intent)
            }
        }
        overlayBinding.negativeButton.setOnClickListener {
            // Sticky decline (ADR 0008): write once through the settings
            // repository so the Settings switch mirrors it; the hub owns
            // recovery if the user changes their mind later.
            ScryerApplication.getSettingsRepository().floatingEnable = false
            showStep(flow.nextAfter(OnboardingStep.OVERLAY))
        }
    }

    private fun bindDone() {
        doneBinding.actionButton.setOnClickListener {
            completeOnboarding(
                    startBulk = doneBinding.actionButton.tag == DoneAction.START_BULK)
        }
    }

    // ------------------------------------------------------------------ rendering

    private fun showStep(step: OnboardingStep) {
        currentStep = step
        for ((_, view) in stepViews) {
            view.visibility = View.GONE
        }
        stepViews[step]?.visibility = View.VISIBLE

        when (step) {
            OnboardingStep.MEDIA -> renderMedia()
            // NOTIFICATIONS and OVERLAY render statically — their launcher
            // callbacks and skip handlers drive the transitions.
            OnboardingStep.NOTIFICATIONS, OnboardingStep.OVERLAY, OnboardingStep.WELCOME -> Unit
            OnboardingStep.DONE -> renderDone()
        }
    }

    /**
     * The media step's three sub-states. `resolve` guarantees the gate is
     * DENIED by the time this renders — anything else auto-advanced.
     */
    private fun renderMedia() {
        if (gates.mediaAccess() != MediaAccess.DENIED) {
            showStep(flow.resolve(OnboardingStep.MEDIA))
            return
        }

        val appName = getString(R.string.app_full_name)
        mediaBinding.previewTitle.text = getString(R.string.setup_media_preview_title, appName)

        // Preview the real dialog for the running OS (ADR 0008 §3.2).
        val api34Plus = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
        mediaBinding.previewRow33.visibility = if (api34Plus) View.GONE else View.VISIBLE
        mediaBinding.previewRow34.visibility = if (api34Plus) View.VISIBLE else View.GONE
        mediaBinding.previewNote34.visibility = if (api34Plus) View.VISIBLE else View.GONE

        if (!mediaDialogShown) {
            // Plain explainer: one full-width CTA, no notice.
            mediaBinding.notice.visibility = View.GONE
            mediaBinding.negativeButton.visibility = View.GONE
            mediaBinding.positiveButton.text = getString(R.string.setup_media_action_allow)
            mediaBinding.positiveButton.tag = SetupRowAction.NONE
            return
        }

        val permanent = !shouldShowRequestPermissionRationale(
                PermissionHelper.getPrimaryReadMediaPermission())
        if (permanent) {
            mediaBinding.notice.text = getString(R.string.setup_media_permanent_notice, appName)
            mediaBinding.positiveButton.text = getString(R.string.setup_media_permanent_action)
            mediaBinding.positiveButton.tag = SetupRowAction.OPEN_SETTINGS
        } else {
            mediaBinding.notice.text = getString(R.string.setup_media_declined_notice, appName)
            mediaBinding.positiveButton.text = getString(R.string.setup_media_declined_retry)
            mediaBinding.positiveButton.tag = SetupRowAction.NONE
        }
        mediaBinding.notice.visibility = View.VISIBLE
        mediaBinding.negativeButton.visibility = View.VISIBLE
    }

    // NOTIFICATIONS and OVERLAY render statically — their launcher callbacks
    // and skip handlers drive the transitions.

    private fun renderDone() {
        if (doneCountStarted) {
            return
        }
        doneCountStarted = true

        doneBinding.title.text = getString(R.string.setup_done_title,
                getString(R.string.app_full_name))
        doneBinding.actionButton.visibility = View.GONE
        doneBinding.countProgress.visibility = View.VISIBLE

        // PRD §3.5: count *unindexed* candidates on entering the done screen.
        // The sync runs first (inserting gallery rows — the ingestion work
        // queue), then the count reads `processed = false` rows: exactly what
        // "Start indexing" will ingest, not every fetchable screenshot. A
        // denied media grant degrades to an empty fetch — the N = 0 branch.
        doneCountJob = lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                runCatching {
                    val repository = ScryerApplication.getScreenshotRepository()
                    ExternalScreenshotSync(
                            applicationContext, repository,
                            pruneUnreadable = gates.mediaAccess() == MediaAccess.GRANTED
                    ).sync()
                    repository.getUnprocessedScreenshotList().size
                }.getOrElse {
                    android.util.Log.w("OnboardingActivity", "corpus count failed", it)
                    0
                }
            }
            if (currentStep != OnboardingStep.DONE) {
                return@launch
            }
            doneBinding.countProgress.visibility = View.GONE
            val appName = getString(R.string.app_full_name)
            if (count > 0) {
                doneBinding.body.text = resources.getQuantityString(
                        R.plurals.setup_done_corpus, count, count, appName)
                doneBinding.actionButton.text = getString(R.string.setup_done_action_index)
                doneBinding.actionButton.tag = DoneAction.START_BULK
            } else {
                doneBinding.body.text = getString(R.string.setup_done_empty, appName)
                doneBinding.actionButton.text =
                        getString(R.string.setup_done_action_enter, appName)
                doneBinding.actionButton.tag = DoneAction.ENTER
            }
            doneBinding.actionButton.visibility = View.VISIBLE
        }
    }

    // ------------------------------------------------------------------ exit

    /**
     * ADR 0008 §3.5 — with one wiring detail the PRD's CTA depends on: the
     * bulk trigger ingests the gallery's unprocessed rows (MediaStoreProducer
     * reads `processed = false` rows — Model B), and on a fresh install those
     * rows exist only after the external-screenshot sync has run, which the
     * Home resume hasn't reached yet. So: sync first, *then* fire. Home's own
     * resume-sync is idempotent over this one. (The original bug: the worker
     * ran against an empty queue and reported Completed with zero docs.)
     */
    private fun completeOnboarding(startBulk: Boolean) {
        doneCountJob?.cancel()
        OnboardingPrefs.getInstance(this).setOnboardingComplete()
        if (startBulk) {
            // The existing user-initiated bulk trigger (CONTEXT.md's trigger
            // model) — progress surfaces via the banner/notification machinery.
            // The work queue (unprocessed rows) was populated by renderDone's
            // sync; nothing further to prepare here.
            ScryerApplication.getIngestionSession().startBulk()
        }
        finish()
    }

    /**
     * The wizard is the app's front door — back must not land the user on an
     * un-onboarded Home (MainActivity would immediately re-gate). The spine
     * is a straight line in v1 (no mode screen to go back to); swallows the
     * press on every step.
     */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Deliberately not calling super.
    }

    /** The done CTA's intent, typed instead of smuggled through tag strings. */
    private enum class DoneAction { ENTER, START_BULK }

    companion object {
        private const val KEY_STEP = "onboarding_step"
        private const val KEY_MEDIA_DIALOG_SHOWN = "onboarding_media_dialog_shown"
        private const val KEY_DONE_COUNT_STARTED = "onboarding_done_count_started"

        fun start(context: Context) {
            context.startActivity(Intent(context, OnboardingActivity::class.java))
        }
    }
}
