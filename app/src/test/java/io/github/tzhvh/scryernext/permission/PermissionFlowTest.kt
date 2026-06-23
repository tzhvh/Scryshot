package io.github.tzhvh.scryernext.permission

import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.*
import org.mockito.ArgumentMatchers.anyBoolean
import kotlin.reflect.KClass

class PermissionFlowTest {

    private lateinit var permissionState: PermissionFlow.PermissionStateProvider
    private lateinit var pageState: PermissionFlow.PageStateProvider

    @Mock
    private lateinit var viewDelegate: PermissionFlow.ViewDelegate

    private var permissions = mutableListOf(false, false, false)

    @Captor
    private lateinit var runnableCaptor: ArgumentCaptor<Runnable>

    private lateinit var flow: PermissionFlow
    private var pageStateData = mutableListOf(false, false, false)

    @Before
    fun setUp() {
        permissions = mutableListOf(false, false, false)
        pageStateData = mutableListOf(false, false, false)
        permissionState = object : PermissionFlow.PermissionStateProvider {
            override fun isOverlayGranted(): Boolean {
                return permissions[1]
            }

            override fun isPostNotificationsGranted(): Boolean {
                return permissions[0]
            }

            override fun isReadMediaGranted(): Boolean {
                return permissions[2]
            }
        }

        pageState = object : PermissionFlow.PageStateProvider {

            override fun isWelcomePageShown(): Boolean {
                return pageStateData[0]
            }

            override fun isOverlayPageShown(): Boolean {
                return pageStateData[1]
            }

            override fun isCapturePageShown(): Boolean {
                return pageStateData[2]
            }

            override fun setWelcomePageShown() {
                pageStateData[0] = true
            }

            override fun setOverlayPageShown() {
                pageStateData[1] = true
            }

            override fun setCapturePageShown() {
                pageStateData[2] = true
            }
        }

        MockitoAnnotations.initMocks(this)
        flow = PermissionFlow(permissionState, pageState, viewDelegate)
    }

    @After
    fun tearDown() {
    }

    /**
     * Test ViewDelegate
     */

    @Test
    fun welcomeState_showWelcomePage() {
        // Prepare: first launch, welcome not yet shown
        flow.start()
        verifyMethod().showWelcomePage(capture(runnableCaptor))
        runnableCaptor.value.run()

        // Test: clicking confirm marks welcome shown and proceeds
        verifyMethod().onWelcomeDone()
        assertTrue(pageState.isWelcomePageShown())
    }

    @Test
    fun overlayState_showOverlayPage() {
        // Prepare: welcome already shown, so entry transfers straight to OverlayState
        pageState.setWelcomePageShown()
        flow.start()

        // Test
        verifyMethod().showOverlayPermissionView(any(), any())
    }

    @Test
    fun overlayState_showOverlayPage_yesToRequestOverlayPermission() {
        // Prepare
        pageState.setWelcomePageShown()
        flow.start()
        verifyMethod().showOverlayPermissionView(capture(runnableCaptor), any())
        runnableCaptor.value.run()

        // Test
        verifyMethod().requestOverlayPermission()
    }

    @Test
    fun overlayState_showOverlayPage_noToFinish() {
        // Prepare
        pageState.setWelcomePageShown()
        flow.start()
        verifyMethod().showOverlayPermissionView(any(), capture(runnableCaptor))
        runnableCaptor.value.run()

        // Test
        verifyMethod().onOverlayDenied()
        verifyMethod().onPermissionFlowFinish()
    }

    @Test
    fun captureState_showCapturePage() {
        // Prepare
        flow.initialState = PermissionFlow.CaptureState(flow)
        flow.start()
        verifyMethod().showCapturePermissionView(any(), capture(runnableCaptor))
        runnableCaptor.value.run()

        // Test
        verifyMethod().onPermissionFlowFinish()
    }

    /**
     * Issue 23: PostNotificationsState sits between OverlayState.Granted and CaptureState.
     * On the JVM (no Android SDK_INT), postNotificationsStepEnabled is false, so the state
     * is a pass-through to CaptureState. This test pins that routing: overlay granted +
     * post-notifications granted → flow reaches CaptureState (which shows the capture view).
     * The API-33+ request-firing path is exercised by the manual smoke test.
     */
    @Test
    fun overlayGranted_routesThroughPostNotificationsToCapture() {
        permissions[0] = true   // post-notifications granted
        permissions[1] = true   // overlay granted
        pageState.setWelcomePageShown()

        // Test: OverlayState.Granted → PostNotificationsState (pass-through) → CaptureState,
        // which shows the capture view (same observable behavior as captureState_showCapturePage).
        flow.start()
        verifyMethod().showCapturePermissionView(any(), any())
    }

    /**
     * Issue 23: PostNotificationsState direct entry also chains to CaptureState.
     */
    @Test
    fun postNotificationsState_passesThroughToCapture() {
        permissions[0] = true   // post-notifications granted → pass-through
        flow.initialState = PermissionFlow.PostNotificationsState(flow)
        flow.start()
        verifyMethod().showCapturePermissionView(any(), any())
    }

    /**
     * On JVM, readMediaStepEnabled is false (SDK_INT < Q), so ReadMediaState is a
     * pass-through to PostNotificationsState → CaptureState. This test confirms the
     * routing: overlay granted → ReadMediaState (pass-through) → PostNotificationsState
     * (pass-through) → CaptureState shows the capture view.
     */
    @Test
    fun overlayGranted_readMediaPassesThroughToCapture() {
        permissions[0] = true   // post-notifications granted
        permissions[1] = true   // overlay granted
        permissions[2] = false  // read-media not granted, but step disabled on JVM
        pageState.setWelcomePageShown()

        flow.start()
        verifyMethod().showCapturePermissionView(any(), any())
    }

    /**
     * Regression for issue 25: on a real device the user walks through the overlay
     * dialog *before* granting overlay. OverlayState.FirstTimeRequest marks
     * overlayPageShown at dialog-show time (to suppress re-asking). When the user then
     * returns from Settings with overlay granted, OverlayState.Granted must still route
     * through the post-overlay onboarding (ReadMedia → PostNotifications → Capture) —
     * not skip to Finish.
     *
     * On JVM both runtime-permission steps are pass-throughs, so the observable
     * assertion is: after the FirstTimeRequest → Granted round-trip, capture view is
     * shown (not Finish-only).
     */
    @Test
    fun overlayGranted_afterFirstTimeRequest_routesThroughCapture() {
        permissions[0] = true   // post-notifications granted → pass-through
        permissions[1] = false  // overlay not granted yet
        pageState.setWelcomePageShown()

        // Step 1: start → OverlayState.FirstTimeRequest shows the overlay dialog and
        // marks overlayPageShown (this is what poisons the old isOverlayPageShown gate).
        flow.start()
        assertTrue(flow.state is PermissionFlow.OverlayState.FirstTimeRequest)
        assertTrue(pageState.isOverlayPageShown())

        // Step 2: user grants overlay in Settings, returns, onResume() → start() again.
        permissions[1] = true
        flow.start()

        // Must reach CaptureState and show the capture view — not Finish-only.
        verifyMethod().showCapturePermissionView(any(), any())
    }

    /**
     * ReadMediaState direct entry also chains to PostNotificationsState → CaptureState
     * on JVM (pass-through).
     */
    @Test
    fun readMediaState_passesThroughToCapture() {
        permissions[0] = true   // post-notifications granted → pass-through
        flow.initialState = PermissionFlow.ReadMediaState(flow)
        flow.start()
        verifyMethod().showCapturePermissionView(any(), any())
    }

    /**
     * Issue 25: onPostNotificationsResult() advances from PostNotificationsState
     * to CaptureState, independent of the permission result.
     */
    @Test
    fun onPostNotificationsResult_advancesToCaptureState() {
        flow.initialState = PermissionFlow.PostNotificationsState(flow)
        flow.start()

        // Simulate the system dialog being dismissed (grant or deny — always advance).
        flow.onPostNotificationsResult()
        assertTrue(flow.isFinished())
    }

    /**
     * Test flow state transfer
     */

    @Test
    fun welcomeShown_overlayNotGranted_transferToOverlayState() {
        // Welcome already shown: entry (WelcomeState) transfers straight to OverlayState
        pageState.setWelcomePageShown()

        // Test: First time
        flow.start()
        // Overlay not granted + overlay page not yet shown → FirstTimeRequest
        assertTrue(flow.state is PermissionFlow.OverlayState.FirstTimeRequest)

        // Test: Second time, overlay page shown but still not granted → Finish (deny path)
        flow.start()
        assertTrue(flow.state is PermissionFlow.FinishState)
    }

    @Test
    fun welcomeShown_overlayGranted_transferToFinish() {
        permissions[1] = true
        pageState.setWelcomePageShown()

        // Test: First time — overlay granted, overlay page not shown → Capture → Finish
        flow.start()
        assertTrue(flow.state is PermissionFlow.FinishState)

        // Test: Second time, directly finish the flow
        flow.start()
        assertTrue(flow.state is PermissionFlow.FinishState)
    }

    private fun verifyMethod(): PermissionFlow.ViewDelegate {
        return Mockito.verify<PermissionFlow.ViewDelegate>(this.viewDelegate)
    }

    private fun <T> any(): T {
        Mockito.any<T>()
        return castNull()
    }

    inline fun <reified T : Any> capture(captor: ArgumentCaptor<T>): T {
        return captor.capture() ?: createInstance()
    }

    inline fun <reified T : Any> createInstance(): T {
        return createInstance(T::class)
    }

    fun <T : Any> createInstance(kClass: KClass<T>): T {
        return castNull()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> castNull(): T = null as T
}
