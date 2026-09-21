/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import android.content.Context

/**
 * The production [RecentSearches.PrefsStore]: one newline-joined string in SharedPreferences,
 * in the app's `zvec_phase21` file (the same file the last_modified backfill marker lives in —
 * one prefs file per phase, named constants shared through [PREFS_FILE]).
 */
class RecentSearchesPrefs(context: Context) : RecentSearches.PrefsStore {

    private val prefs = context.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)

    override fun read(): String? = prefs.getString(KEY, null)

    override fun write(value: String) {
        prefs.edit().putString(KEY, value).apply()
    }

    companion object {
        const val PREFS_FILE = "zvec_phase21"
        private const val KEY = "recent_searches"
    }
}
