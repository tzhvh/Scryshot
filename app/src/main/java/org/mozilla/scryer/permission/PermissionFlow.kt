/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.scryer.permission

import android.content.Context
import android.os.Build
import android.preference.PreferenceManager
import androidx.fragment.app.FragmentActivity
import org.mozilla.scryer.MainActivity
import java.lang.ref.WeakReference

class PermissionFlow(private var permissionState: PermissionStateProvider,
                     private var pageState: PageStateProvider,
                     private val viewDelegate: ViewDelegate) {
    companion object {
        private const val KEY_WELCOME_PAGE_SHOWN = "welcome_page_shown"
        private const val KEY_OVERLAY_PAGE_SHOWN = "overlay_page_shown"
        private const val KEY_CAPTURE_PAGE_SHOWN = "capture_page_shown"

        /** Request code for the POST_NOTIFICATIONS runtime permission (issue 23, API 33+). */
        const val REQUEST_CODE_POST_NOTIFICATIONS = 1001

        /** Whether the POST_NOTIFICATIONS step is active at all — only on Android 13+. */
        val postNotificationsStepEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        fun createDefaultPermissionProvider(activity: FragmentActivity?): PermissionStateProvider {
            val weakActivity = WeakReference<FragmentActivity>(activity)
            return object : PermissionFlow.PermissionStateProvider {

                override fun isOverlayGranted(): Boolean {
                    return weakActivity.get()?.let {
                        PermissionHelper.hasOverlayPermission(it)
                    }?: false
                }

                override fun isPostNotificationsGranted(): Boolean {
                    // Below Android 13 the permission doesn't exist → treat as granted so the
                    // flow skips the state entirely (the guard in OverlayState also short-circuits).
                    if (!postNotificationsStepEnabled) {
                        return true
                    }
                    return weakActivity.get()?.let {
                        PermissionHelper.hasPostNotificationsPermission(it)
                    } ?: false
                }
            }
        }

        fun createDefaultPageStateProvider(context: Context?): PageStateProvider {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context?.applicationContext)
            return object : PermissionFlow.PageStateProvider {

                override fun isWelcomePageShown(): Boolean {
                    return prefs.getBoolean(KEY_WELCOME_PAGE_SHOWN, false)
                }

                override fun isOverlayPageShown(): Boolean {
                    return prefs.getBoolean(KEY_OVERLAY_PAGE_SHOWN, false)
                }

                override fun isCapturePageShown(): Boolean {
                    return prefs.getBoolean(KEY_CAPTURE_PAGE_SHOWN, false)
                }

                override fun setWelcomePageShown() {
                    updatePrefs(KEY_WELCOME_PAGE_SHOWN, true)
                }

                override fun setOverlayPageShown() {
                    updatePrefs(KEY_OVERLAY_PAGE_SHOWN, true)
                }

                override fun setCapturePageShown() {
                    updatePrefs(KEY_CAPTURE_PAGE_SHOWN, true)
                }

                private fun updatePrefs(key: String, value: Boolean) {
                    prefs.edit().putBoolean(key, value).apply()
                }
            }
        }
    }

    var initialState: State = WelcomeState(this)
    var state: State = initialState

    fun start() {
        state = initialState.execute()
    }

    fun isFinished(): Boolean {
        return state is FinishState
    }

    @Suppress("UNUSED_PARAMETER")
    fun onPermissionResult(requestCode: Int, results: BooleanArray) {
        if (results.isEmpty()) {
            return
        }
        // Issue 23: POST_NOTIFICATIONS result. The result is observed on the next
        // PermissionFlow.start() (called from HomeFragment.onResume) via
        // isPostNotificationsGranted(); no synchronous state push is needed here. Whether
        // the user granted or denied, capture proceeds — only capture-result notifications
        // are gated by this permission (the foreground-service notification is exempt by type).
        if (requestCode == REQUEST_CODE_POST_NOTIFICATIONS) {
            state = state.transfer(CaptureState(this))
        }
    }

    interface ViewDelegate {
        fun showWelcomePage(action: Runnable)

        fun showOverlayPermissionView(action: Runnable, negativeAction: Runnable)
        fun showCapturePermissionView(action: Runnable, negativeAction: Runnable)

        fun onWelcomeDone()
        fun onOverlayGranted()
        fun onOverlayDenied()

        fun onPermissionFlowFinish()

        fun requestOverlayPermission()

        /** Issue 23: fire the system POST_NOTIFICATIONS permission request (API 33+). */
        fun requestPostNotificationsPermission()

        fun launchSystemSettingPage()
    }

    interface PermissionStateProvider {
        fun isOverlayGranted(): Boolean

        /** Issue 23: whether POST_NOTIFICATIONS is granted (always true below Android 13). */
        fun isPostNotificationsGranted(): Boolean
    }

    interface PageStateProvider {
        fun isWelcomePageShown(): Boolean
        fun isOverlayPageShown(): Boolean
        fun isCapturePageShown(): Boolean

        fun setWelcomePageShown()
        fun setOverlayPageShown()
        fun setCapturePageShown()
    }

    interface State {
        fun execute(): State
        fun transfer(state: State): State {
            return state.execute()
        }
    }

    /**
     * First-run welcome screen. Storage was a runtime permission on API < 29 and the
     * welcome screen used to double as the storage-permission ask; with minSdk 29 the app
     * writes to its own Pictures/ScreenshotGo/ without any storage permission, so this
     * state is now a pure one-shot welcome: show the screen on first launch, then proceed
     * to the overlay onboarding.
     */
    open class WelcomeState(private val flow: PermissionFlow) : State {
        override fun execute(): State {
            return transfer(if (flow.pageState.isWelcomePageShown()) {
                OverlayState(flow)
            } else {
                FirstTimeWelcome(flow)
            })
        }

        class FirstTimeWelcome(private val flow: PermissionFlow) : WelcomeState(flow) {
            override fun execute(): State {
                flow.viewDelegate.showWelcomePage(Runnable {
                    flow.pageState.setWelcomePageShown()
                    flow.viewDelegate.onWelcomeDone()
                    flow.state = transfer(OverlayState(flow))
                })
                return this
            }
        }
    }

    open class OverlayState(private val flow: PermissionFlow) : State {
        override fun execute(): State {
            return transfer(when {
                flow.permissionState.isOverlayGranted() -> Granted(flow)
                flow.pageState.isOverlayPageShown() -> NonFirstTimeRequest(flow)
                else -> FirstTimeRequest(flow)
            })
        }

        class Granted(private val flow: PermissionFlow) : State {
            override fun execute(): State {
                flow.viewDelegate.onOverlayGranted()

                return if (flow.pageState.isOverlayPageShown()) {
                    transfer(FinishState(flow))
                } else {
                    flow.pageState.setOverlayPageShown()
                    // Issue 23: route through PostNotificationsState (Android 13+) before
                    // CaptureState. PostNotificationsState short-circuits to CaptureState when
                    // the step is disabled (< API 33) or already granted.
                    transfer(PostNotificationsState(flow))
                }
            }
        }

        class FirstTimeRequest(private val flow: PermissionFlow) : OverlayState(flow) {
            override fun execute(): State {
                flow.pageState.setOverlayPageShown()
                flow.viewDelegate.showOverlayPermissionView(Runnable {
                    flow.viewDelegate.requestOverlayPermission()
                }, Runnable {
                    flow.viewDelegate.onOverlayDenied()
                    flow.state = transfer(FinishState(flow))
                })
                return this
            }
        }

        class NonFirstTimeRequest(private val flow: PermissionFlow) : OverlayState(flow) {
            override fun execute(): State {
                flow.viewDelegate.onOverlayDenied()
                return transfer(FinishState(flow))
            }
        }
    }

    /**
     * Issue 23: requests POST_NOTIFICATIONS (Android 13+) between the overlay step and
     * capture onboarding. Per the harmonization decision, this requests the permission
     * directly from the system — no explanatory bottom dialog. It always chains to
     * [CaptureState] whether the user grants or denies: capture is unaffected; only the
     * screenshot-detected result notification is gated (the foreground-service notification
     * is exempt by type). The actual system request is fired by the ViewDelegate; the result
     * comes back asynchronously via [onPermissionResult], which advances to CaptureState.
     * If the step is disabled (< API 33) or the permission is already granted, this state
     * is a pass-through.
     */
    open class PostNotificationsState(private val flow: PermissionFlow) : State {
        override fun execute(): State {
            return if (postNotificationsStepEnabled && !flow.permissionState.isPostNotificationsGranted()) {
                flow.viewDelegate.requestPostNotificationsPermission()
                this
            } else {
                transfer(CaptureState(flow))
            }
        }
    }

    open class CaptureState(private val flow: PermissionFlow) : State {
        override fun execute(): State {
            return transfer(if (flow.pageState.isCapturePageShown()) {
                NonFirstTimeRequest(flow)
            } else {
                FirstTimeRequest(flow)
            })
        }

        class FirstTimeRequest(private val flow: PermissionFlow) : CaptureState(flow) {
            override fun execute(): State {
                flow.viewDelegate.showCapturePermissionView(Runnable {}, Runnable {})
                flow.pageState.setCapturePageShown()
                return transfer(FinishState(flow))
            }
        }

        class NonFirstTimeRequest(private val flow: PermissionFlow) : CaptureState(flow) {
            override fun execute(): State {
                return transfer(FinishState(flow))
            }
        }
    }

    class FinishState(private val flow: PermissionFlow) : State {
        override fun execute(): State {
            flow.viewDelegate.onPermissionFlowFinish()
            return this
        }
    }
}
