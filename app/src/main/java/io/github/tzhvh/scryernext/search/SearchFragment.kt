/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.preference.PreferenceManager
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.extension.getNavController
import io.github.tzhvh.scryernext.getSupportActionBar
import io.github.tzhvh.scryernext.setSupportActionBar
import io.github.tzhvh.scryernext.ui.ScryerToast

class SearchFragment : Fragment() {
    private val PREF_SEARCH_FEEDBACK_HAS_SHOWN = "pref_search_feedback_has_shown"

    private val toast: ScryerToast by lazy {
        ScryerToast(context!!)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val layout = inflater.inflate(R.layout.fragment_search_empty, container, false)

        if (!hasSearchFeedbackShown()) {
            layout.findViewById<TextView>(R.id.subtitle).text = getString(R.string.onboarding_search_content_survey)
            layout.findViewById<Button>(R.id.positive_button).visibility = View.VISIBLE
            layout.findViewById<Button>(R.id.positive_button).setOnClickListener {
                doAfterFeedback()
            }
            layout.findViewById<Button>(R.id.negative_button).visibility = View.VISIBLE
            layout.findViewById<Button>(R.id.negative_button).setOnClickListener {
                doAfterFeedback()
            }
        }

        return layout
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setHasOptionsMenu(true)
        setupActionBar()

    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> getNavController()?.navigateUp()
        }

        return super.onOptionsItemSelected(item)
    }

    private fun setupActionBar() {
        view?.let {
            setSupportActionBar(activity, it.findViewById(R.id.toolbar))
        }
        getSupportActionBar(activity).apply {
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun doAfterFeedback() {
        setSearchFeedbackHasShown()
        toast.show(getString(R.string.onboarding_search_content_feedback), Toast.LENGTH_SHORT)
        getNavController()?.navigateUp()
    }

    private fun setSearchFeedbackHasShown() {
        context?.let {
            PreferenceManager.getDefaultSharedPreferences(it).edit()
                    .putBoolean(PREF_SEARCH_FEEDBACK_HAS_SHOWN, true)
                    .apply()
        }
    }

    private fun hasSearchFeedbackShown(): Boolean {
        return context?.let {
            PreferenceManager.getDefaultSharedPreferences(it)
                    .getBoolean(PREF_SEARCH_FEEDBACK_HAS_SHOWN, false)
        } ?: false
    }
}
