/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.onboarding

import android.content.Context
import androidx.preference.PreferenceManager

/**
 * ADR 0008 — the onboarding state model: the single "setup done" flag plus
 * Phase 3b's immutable access-model flag, both in the default
 * SharedPreferences (the established mechanism — the old `PermissionFlow`
 * page-state provider used the same file; no DataStore, not the norm here).
 *
 * `onboarding_complete` means "the user completed the wizard", **not** "every
 * grant is present" — a user who taps "Not now" on the media step finishes
 * onboarding with the requirement missing, and the setup hub owns the
 * missing-requirement state afterwards.
 */
class OnboardingPrefs private constructor(context: Context) {

    private val prefs = PreferenceManager.getDefaultSharedPreferences(context)

    fun isOnboardingComplete(): Boolean {
        return prefs.getBoolean(KEY_ONBOARDING_COMPLETE, false)
    }

    fun setOnboardingComplete() {
        prefs.edit().putBoolean(KEY_ONBOARDING_COMPLETE, true).apply()
    }

    /**
     * The access model in force for this install. Phase 3b's one-way door:
     * always [ACCESS_MODEL_MEDIA_STORE] until the SAF mode exists, and the
     * flag is written at first run (see [applyLegacyMigration]) so no install
     * ever faces a retroactive choice.
     */
    fun accessModel(): String {
        return prefs.getString(KEY_ACCESS_MODEL, ACCESS_MODEL_MEDIA_STORE)
                ?: ACCESS_MODEL_MEDIA_STORE
    }

    companion object {
        const val KEY_ONBOARDING_COMPLETE = "onboarding_complete"
        const val KEY_ACCESS_MODEL = "access_model"
        const val ACCESS_MODEL_MEDIA_STORE = "media_store"

        /**
         * Legacy `PermissionFlow` page-state key that doubled as "the old flow
         * finished" ([PermissionFlow.OverlayState.Granted]'s issue-25 gate).
         * Read exactly once, by [applyLegacyMigration]; never written again.
         * `welcome_page_shown` / `overlay_page_shown` are dead by the same
         * cutover — not read, not deleted.
         */
        private const val KEY_LEGACY_CAPTURE_PAGE_SHOWN = "capture_page_shown"

        @Volatile
        private var instance: OnboardingPrefs? = null

        fun getInstance(context: Context): OnboardingPrefs {
            return instance ?: synchronized(this) {
                instance ?: OnboardingPrefs(context.applicationContext).also { instance = it }
            }
        }

        /**
         * One-time startup migration (runs from `ScryerApplication.onCreate`,
         * before any activity can consult the gate). Idempotent:
         *
         * - `access_model` is pinned on first run so Phase 3b's
         *   immutable-choice mechanic already has its flag.
         * - `capture_page_shown == true` translates to
         *   `onboarding_complete = true` — existing installs never see the
         *   wizard. Partially-onboarded installs (the flag absent or false)
         *   correctly run the new wizard once.
         */
        fun applyLegacyMigration(context: Context) {
            val prefs = PreferenceManager.getDefaultSharedPreferences(context.applicationContext)
            val edit = prefs.edit()
            if (!prefs.contains(KEY_ACCESS_MODEL)) {
                edit.putString(KEY_ACCESS_MODEL, ACCESS_MODEL_MEDIA_STORE)
            }
            if (prefs.getBoolean(KEY_LEGACY_CAPTURE_PAGE_SHOWN, false)
                    && !prefs.contains(KEY_ONBOARDING_COMPLETE)) {
                edit.putBoolean(KEY_ONBOARDING_COMPLETE, true)
            }
            edit.apply()
        }
    }
}
