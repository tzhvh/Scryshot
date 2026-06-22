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

        /** Whether the POST_NOTIFICATIONS step is active at all — only on Android 13+. */
        val postNotificationsStepEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

        /**
         * Whether the READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE step is active. With minSdk 29,
         * always active — the app needs this permission to query foreign MediaStore rows.
         * On JVM (tests), SDK_INT is 0 so this returns false → pass-through.
         */
        val readMediaStepEnabled: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q

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

                override fun isReadMediaGranted(): Boolean {
                    if (!readMediaStepEnabled) {
                        return true
                    }
                    return weakActivity.get()?.let {
                        PermissionHelper.hasReadMediaPermission(it)
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

    /**
     * Called when the POST_NOTIFICATIONS system dialog has been dismissed (grant or deny).
     * Advances to CaptureState unconditionally — capture is unaffected by denial; only
     * capture-result notifications are gated by this permission.
     */
    fun onPostNotificationsResult() {
        state = state.transfer(CaptureState(this))
    }

    /**
     * Called when the READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE system dialog has been
     * dismissed (grant or deny). Advances to PostNotificationsState unconditionally —
     * capture is unaffected; only foreign-screenshot discovery is gated.
     */
    fun onReadMediaResult() {
        state = state.transfer(PostNotificationsState(this))
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

        /** Fire the system READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE permission request. */
        fun requestReadMediaPermission()

        fun launchSystemSettingPage()
    }

    interface PermissionStateProvider {
        fun isOverlayGranted(): Boolean

        /** Issue 23: whether POST_NOTIFICATIONS is granted (always true below Android 13). */
        fun isPostNotificationsGranted(): Boolean

        /** Whether READ_MEDIA_IMAGES / READ_EXTERNAL_STORAGE is granted. */
        fun isReadMediaGranted(): Boolean
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

                // Gate the post-overlay onboarding (ReadMedia → PostNotifications →
                // Capture) on the capture page being shown — CaptureState is the terminal
                // step and sets that flag itself, so capturePageShown == "the whole
                // post-overlay onboarding already ran once".
                //
                // Do NOT gate on isOverlayPageShown: FirstTimeRequest sets that flag the
                // moment it shows the overlay dialog, *before* the user grants overlay
                // (it's how we suppress re-asking). So on the normal path the user
                // returns from Settings with overlay granted but isOverlayPageShown
                // already true, and gating on it would skip straight to Finish — never
                // prompting for READ_MEDIA_IMAGES or POST_NOTIFICATIONS. ReadMedia /
                // PostNotifications / Capture each self-gate via their own permission /
                // page checks, so re-routing through them on every granted-overlay entry
                // is safe.
                return if (flow.pageState.isCapturePageShown()) {
                    transfer(FinishState(flow))
                } else {
                    flow.pageState.setOverlayPageShown()
                    transfer(ReadMediaState(flow))
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
     * Requests READ_MEDIA_IMAGES (API 33+) / READ_EXTERNAL_STORAGE (API 29–32) so the
     * read path (ScreenshotFetcher, MediaProviderDelegate) can query foreign MediaStore
     * rows — screenshots the system or other apps saved outside the app's own
     * Pictures/ScreenshotGo/ folder.
     *
     * The actual system request is fired by the ViewDelegate; the result comes back
     * asynchronously via [onReadMediaResult], which advances to PostNotificationsState.
     * If the step is disabled (JVM tests) or the permission is already granted, this
     * state is a pass-through.
     */
    open class ReadMediaState(private val flow: PermissionFlow) : State {
        override fun execute(): State {
            return if (readMediaStepEnabled && !flow.permissionState.isReadMediaGranted()) {
                flow.viewDelegate.requestReadMediaPermission()
                this
            } else {
                transfer(PostNotificationsState(flow))
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
