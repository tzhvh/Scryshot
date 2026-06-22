package org.mozilla.scryer.permission

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

    private var permissions = mutableListOf(false, false)

    @Captor
    private lateinit var runnableCaptor: ArgumentCaptor<Runnable>

    private lateinit var flow: PermissionFlow
    private var pageStateData = mutableListOf(false, false, false)

    @Before
    fun setUp() {
        permissions = mutableListOf(false, false)
        pageStateData = mutableListOf(false, false, false)
        permissionState = object : PermissionFlow.PermissionStateProvider {
            override fun isOverlayGranted(): Boolean {
                return permissions[1]
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
