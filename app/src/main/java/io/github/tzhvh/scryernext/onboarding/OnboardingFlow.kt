/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import android.os.Build
import io.github.tzhvh.scryernext.permission.MediaAccess

/** The wizard spine, in presentation order (ADR 0008). */
enum class OnboardingStep {
    WELCOME,
    MEDIA,
    NOTIFICATIONS,
    OVERLAY,
    DONE
}

/**
 * The wizard's live gate states. Injected so [OnboardingFlow]'s routing is a
 * pure, JVM-testable function of gate data — the seam `PermissionFlow`'s
 * `PermissionStateProvider` provided.
 */
interface OnboardingGates {
    fun mediaAccess(): MediaAccess
    fun isNotificationsGranted(): Boolean
    fun isOverlayGranted(): Boolean
}

/**
 * ADR 0008 — the wizard's routing logic: the step spine, its order, and the
 * auto-advance rule ("a step whose grant already exists is skipped").
 *
 * The SDK-gated step flags are constructor-injected with the platform
 * defaults (companion constants reading `SDK_INT`). On the JVM the defaults
 * are false (`SDK_INT == 0`) — the same hermetic pass-through convention the
 * old `PermissionFlow` tests relied on — but unlike that design, a test can
 * also construct the flow with the flags *enabled* and exercise the real
 * device branches (denied-stays, partial-continues) without a device.
 *
 * Not persisted: the flow instance lives with the activity, and grants are
 * the only persisted truth. If the process dies mid-wizard, the wizard
 * restarts at WELCOME and the auto-advance rule walks it past whatever is
 * already granted — no separate "declined" flags (the setup hub owns
 * missing requirements after completion).
 */
class OnboardingFlow(
    private val gates: OnboardingGates,
    private val mediaStepEnabled: Boolean = Defaults.mediaStepEnabled,
    private val notificationsStepEnabled: Boolean = Defaults.notificationsStepEnabled
) {

    companion object {
        /**
         * Whether the POST_NOTIFICATIONS step is on the spine — only on
         * Android 13+. Below 33 the permission doesn't exist; the step
         * doesn't render (PRD §3.3).
         */
        val notificationsStepEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /**
         * Whether the media step is on the spine. With minSdk 29, always true
         * on device; false only on the JVM (see class KDoc).
         */
        val mediaStepEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
    }

    private object Defaults {
        val mediaStepEnabled = Companion.mediaStepEnabled
        val notificationsStepEnabled = Companion.notificationsStepEnabled
    }

    /**
     * The spine in presentation order, minus steps disabled on this OS:
     * welcome → media (required) → notifications (optional, 33+) →
     * floating button (optional, last) → done.
     */
    fun spine(): List<OnboardingStep> {
        val steps = ArrayList<OnboardingStep>()
        steps.add(OnboardingStep.WELCOME)
        if (mediaStepEnabled) {
            steps.add(OnboardingStep.MEDIA)
        }
        if (notificationsStepEnabled) {
            steps.add(OnboardingStep.NOTIFICATIONS)
        }
        steps.add(OnboardingStep.OVERLAY)
        steps.add(OnboardingStep.DONE)
        return steps
    }

    /** The next spine step after [step]; DONE is terminal (DONE → DONE). */
    fun nextAfter(step: OnboardingStep): OnboardingStep {
        val spine = spine()
        val index = spine.indexOf(step)
        if (index < 0 || index == spine.size - 1) {
            return OnboardingStep.DONE
        }
        return spine[index + 1]
    }

    /**
     * The step that should actually render when the wizard arrives at [entry]:
     * walking the canonical step order from [entry], a *disabled* step is
     * transparent and a *satisfied* gate (grant exists, or partial — which is
     * success-adjacent per ADR 0008) is skipped; the walk stops at the first
     * enabled, unsatisfied step, or DONE.
     *
     * The walk starts at [entry], never before it — a mid-wizard re-derive
     * must not walk backwards over steps the user already passed (a media
     * revocation mid-wizard is the hub's problem after completion, not a
     * reason to replay earlier steps).
     *
     * Explicit user continuation ("Not now" / "Skip") bypasses this rule and
     * goes straight to [nextAfter] — the caller then resolves that step.
     */
    fun resolve(entry: OnboardingStep): OnboardingStep {
        val order = listOf(OnboardingStep.WELCOME, OnboardingStep.MEDIA,
                OnboardingStep.NOTIFICATIONS, OnboardingStep.OVERLAY, OnboardingStep.DONE)
        var index = order.indexOf(entry)
        if (index < 0) {
            return OnboardingStep.DONE
        }
        while (index < order.size - 1) {
            val step = order[index]
            if (!isStepEnabled(step) || isStepSatisfied(step)) {
                index++
            } else {
                return step
            }
        }
        return OnboardingStep.DONE
    }

    private fun isStepEnabled(step: OnboardingStep): Boolean {
        return when (step) {
            OnboardingStep.MEDIA -> mediaStepEnabled
            OnboardingStep.NOTIFICATIONS -> notificationsStepEnabled
            else -> true
        }
    }

    private fun isStepSatisfied(step: OnboardingStep): Boolean {
        return when (step) {
            OnboardingStep.MEDIA -> gates.mediaAccess() != MediaAccess.DENIED
            OnboardingStep.NOTIFICATIONS -> gates.isNotificationsGranted()
            OnboardingStep.OVERLAY -> gates.isOverlayGranted()
            else -> false
        }
    }
}
