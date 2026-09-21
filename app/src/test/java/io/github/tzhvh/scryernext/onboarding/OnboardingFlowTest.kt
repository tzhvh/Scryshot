/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import io.github.tzhvh.scryernext.permission.MediaAccess
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Routing tests for the wizard's pure spine (ADR 0008). Conventions carried
 * over from `PermissionFlowTest`: plain JUnit4 + hand-rolled fakes, no
 * Robolectric. The SDK-gated step flags are constructor-injectable, so the
 * device-shaped branches (denied-stays, partial-continues, notifications
 * ordering) are hermetic; the JVM-default construction additionally pins the
 * pass-through seam (`SDK_INT == 0` ⇒ both flags false) that guards against
 * an inline `Build.VERSION.SDK_INT` check leaking into the spine.
 */
class OnboardingFlowTest {

    private class FakeGates(
        var media: MediaAccess = MediaAccess.DENIED,
        var notifications: Boolean = false,
        var overlay: Boolean = false
    ) : OnboardingGates {
        override fun mediaAccess(): MediaAccess = media
        override fun isNotificationsGranted(): Boolean = notifications
        override fun isOverlayGranted(): Boolean = overlay
    }

    /** Device shape: both optional/required OS-gated steps on the spine. */
    private fun deviceFlow(gates: FakeGates) = OnboardingFlow(
            gates, mediaStepEnabled = true, notificationsStepEnabled = true)

    /** API 29–32 shape: media on the spine, notifications off. */
    private fun pre33Flow(gates: FakeGates) = OnboardingFlow(
            gates, mediaStepEnabled = true, notificationsStepEnabled = false)

    /** The JVM-default construction: `SDK_INT == 0`, both gated steps off. */
    private fun jvmFlow(gates: FakeGates) = OnboardingFlow(gates)

    // ---------------------------------------------------------------- spine shape

    @Test
    fun spine_deviceShape_isTheFullLinearSpine() {
        val flow = deviceFlow(FakeGates())
        assertEquals(listOf(OnboardingStep.WELCOME, OnboardingStep.MEDIA,
                        OnboardingStep.NOTIFICATIONS, OnboardingStep.OVERLAY,
                        OnboardingStep.DONE),
                flow.spine())
    }

    @Test
    fun spine_pre33_omitsNotifications() {
        val flow = pre33Flow(FakeGates())
        assertEquals(listOf(OnboardingStep.WELCOME, OnboardingStep.MEDIA,
                        OnboardingStep.OVERLAY, OnboardingStep.DONE),
                flow.spine())
    }

    @Test
    fun stepEnabledDefaults_areFalseOnJvm() {
        // The seam: on the JVM SDK_INT == 0, so both gated steps default off.
        assertFalse(OnboardingFlow.mediaStepEnabled)
        assertFalse(OnboardingFlow.notificationsStepEnabled)
    }

    @Test
    fun spine_jvmDefaults_omitsSdkGatedSteps() {
        val flow = jvmFlow(FakeGates())
        assertEquals(listOf(OnboardingStep.WELCOME, OnboardingStep.OVERLAY, OnboardingStep.DONE),
                flow.spine())
    }

    // ---------------------------------------------------------------- nextAfter

    @Test
    fun nextAfter_walksTheDeviceSpine() {
        val flow = deviceFlow(FakeGates())
        assertEquals(OnboardingStep.MEDIA, flow.nextAfter(OnboardingStep.WELCOME))
        assertEquals(OnboardingStep.NOTIFICATIONS, flow.nextAfter(OnboardingStep.MEDIA))
        assertEquals(OnboardingStep.OVERLAY, flow.nextAfter(OnboardingStep.NOTIFICATIONS))
        assertEquals(OnboardingStep.DONE, flow.nextAfter(OnboardingStep.OVERLAY))
    }

    @Test
    fun nextAfter_doneIsTerminal() {
        val flow = deviceFlow(FakeGates())
        assertEquals(OnboardingStep.DONE, flow.nextAfter(OnboardingStep.DONE))
    }

    // ---------------------------------------------------------------- resolve: auto-advance

    @Test
    fun resolve_everythingGranted_skipsStraightToDone() {
        val flow = deviceFlow(FakeGates(
                media = MediaAccess.GRANTED, notifications = true, overlay = true))
        assertEquals(OnboardingStep.DONE, flow.resolve(OnboardingStep.MEDIA))
    }

    @Test
    fun resolve_partialMedia_isNotABlocker() {
        // ADR 0008: partial photo access is success-adjacent in the wizard —
        // only the hub surfaces it.
        val flow = deviceFlow(FakeGates(media = MediaAccess.PARTIAL))
        assertEquals(OnboardingStep.NOTIFICATIONS, flow.resolve(OnboardingStep.MEDIA))
    }

    @Test
    fun resolve_deniedMedia_staysOnMedia() {
        val flow = deviceFlow(FakeGates(media = MediaAccess.DENIED))
        assertEquals(OnboardingStep.MEDIA, flow.resolve(OnboardingStep.MEDIA))
    }

    @Test
    fun resolve_notificationsDenied_staysOnNotifications() {
        val flow = deviceFlow(FakeGates(media = MediaAccess.GRANTED))
        assertEquals(OnboardingStep.NOTIFICATIONS, flow.resolve(OnboardingStep.NOTIFICATIONS))
    }

    @Test
    fun resolve_notificationsGranted_skipsToOverlay() {
        val flow = deviceFlow(FakeGates(media = MediaAccess.GRANTED, notifications = true))
        assertEquals(OnboardingStep.OVERLAY, flow.resolve(OnboardingStep.NOTIFICATIONS))
    }

    @Test
    fun resolve_overlayUngranted_staysOnOverlay() {
        val flow = deviceFlow(FakeGates(media = MediaAccess.GRANTED, notifications = true))
        assertEquals(OnboardingStep.OVERLAY, flow.resolve(OnboardingStep.OVERLAY))
    }

    @Test
    fun resolve_welcomeAndDoneNeverAutoAdvance() {
        val flow = deviceFlow(FakeGates(
                media = MediaAccess.GRANTED, notifications = true, overlay = true))
        assertEquals(OnboardingStep.WELCOME, flow.resolve(OnboardingStep.WELCOME))
        assertEquals(OnboardingStep.DONE, flow.resolve(OnboardingStep.DONE))
    }

    @Test
    fun resolve_partialWalksPastNotificationsToo() {
        val flow = deviceFlow(FakeGates(media = MediaAccess.PARTIAL, notifications = true))
        assertEquals(OnboardingStep.OVERLAY, flow.resolve(OnboardingStep.MEDIA))
    }

    // ---------------------------------------------------------------- resolve: pass-through seam

    @Test
    fun resolve_disabledMediaStepOnJvm_isTransparentToTheFirstEnabledGate() {
        // On the JVM the media step is off the spine: resolve walks through it
        // (and the equally-disabled notifications step) to the first enabled,
        // unsatisfied step — it neither strands the wizard nor skips gates.
        val flow = jvmFlow(FakeGates(media = MediaAccess.DENIED))
        assertEquals(OnboardingStep.OVERLAY, flow.resolve(OnboardingStep.MEDIA))
    }

    @Test
    fun resolve_disabledNotificationsStepOnJvm_passesThrough() {
        val flow = jvmFlow(FakeGates(overlay = false))
        assertEquals(OnboardingStep.OVERLAY, flow.resolve(OnboardingStep.NOTIFICATIONS))
    }

    @Test
    fun resolve_pre33Shape_deniedMedia_staysOnMedia() {
        val flow = pre33Flow(FakeGates(media = MediaAccess.DENIED))
        assertEquals(OnboardingStep.MEDIA, flow.resolve(OnboardingStep.MEDIA))
    }

    @Test
    fun resolve_pre33Shape_notificationsDisabled_passesThroughToOverlay() {
        val flow = pre33Flow(FakeGates(media = MediaAccess.GRANTED))
        assertEquals(OnboardingStep.OVERLAY, flow.resolve(OnboardingStep.NOTIFICATIONS))
    }
}
