package org.mozilla.scryer.setting

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AlertDialog
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.Button
import org.mozilla.scryer.*
import org.mozilla.scryer.permission.PermissionHelper
import org.mozilla.scryer.preference.PreferenceWrapper
import org.mozilla.scryer.promote.PromoteRatingHelper
import org.mozilla.scryer.promote.PromoteShareHelper

class SettingsFragment : PreferenceFragmentCompat(), Preference.OnPreferenceChangeListener, Preference.OnPreferenceClickListener {

    private val enableCaptureService: SwitchPreferenceCompat by lazy { findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_enable_capture_service))!! }
    private val enableFloatingScreenshotButton: SwitchPreferenceCompat by lazy { findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_enable_floating_screenshot_button))!! }
    private val enableAddToCollectionButton: SwitchPreferenceCompat by lazy { findPreference<SwitchPreferenceCompat>(getString(R.string.pref_key_enable_add_to_collection))!! }
    private val giveFeedbackPreference: Preference by lazy { findPreference<Preference>(getString(R.string.pref_key_give_feedback))!! }
    private val shareWithFriendsPreference: Preference by lazy { findPreference<Preference>(getString(R.string.pref_key_share_with_friends))!! }
    private val aboutPreference: Preference by lazy { findPreference<Preference>(getString(R.string.pref_key_about))!! }

    private var overlayPermissionRequested = false
    private var debugClicks = 0

    private val pref: PreferenceWrapper? by lazy {
        context?.let {
            PreferenceWrapper(it)
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        setupActionBar()
    }

    private fun setupActionBar() {
        getSupportActionBar(activity).apply {
            setDisplayHomeAsUpEnabled(true)
            updateActionBarTitle(this)
        }
    }

    private fun updateActionBarTitle(actionBar: ActionBar) {
        actionBar.title = getString(R.string.menu_home_action_settings)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.settings, rootKey)

        val settingsRepository = ScryerApplication.getSettingsRepository()

        enableCaptureService.onPreferenceChangeListener = this
        enableCaptureService.isIconSpaceReserved = false

        enableCaptureService.isChecked = settingsRepository.serviceEnabled
        enableCaptureServiceChildItems(settingsRepository.serviceEnabled)
        settingsRepository.serviceEnabledObserver.observe(this, Observer {
            enableCaptureService.isChecked = it
            enableCaptureServiceChildItems(it)
        })

        enableFloatingScreenshotButton.title = getString(R.string.settings_list_fab, getString(R.string.app_name_go))
        enableFloatingScreenshotButton.isChecked = settingsRepository.floatingEnable
        enableFloatingScreenshotButton.onPreferenceChangeListener = this
        settingsRepository.floatingEnableObservable.observe(this, Observer { enabled ->
            enableFloatingScreenshotButton.isChecked = enabled
            onFloatingEnableStateChanged(enabled)
        })

        enableAddToCollectionButton.isChecked = settingsRepository.addToCollectionEnable
        enableAddToCollectionButton.onPreferenceChangeListener = this
        settingsRepository.addToCollectionEnableObservable.observe(this, Observer { enabled ->
            enableAddToCollectionButton.isChecked = enabled
        })

        giveFeedbackPreference.onPreferenceClickListener = this
        giveFeedbackPreference.isIconSpaceReserved = false

        shareWithFriendsPreference.onPreferenceClickListener = this
        shareWithFriendsPreference.isIconSpaceReserved = false

        aboutPreference.onPreferenceClickListener = this
        aboutPreference.isIconSpaceReserved = false
    }

    override fun onResume() {
        super.onResume()
        checkOverlayPermission()
    }

    override fun onPreferenceChange(preference: Preference, newValue: Any): Boolean {
        val repository = ScryerApplication.getSettingsRepository()

        if (preference == enableCaptureService) {
            val enable = newValue as Boolean
            val intent = Intent(activity, ScryerService::class.java)
            intent.action = if (enable) {
                ScryerService.ACTION_ENABLE_SERVICE
            } else {
                ScryerService.ACTION_DISABLE_SERVICE
            }
            activity?.startService(intent)

            repository.serviceEnabled = enable

            // Reset the flag not to show the prompt dialog
            // especially when user stops the service from notification
            if (enable) {
                pref?.setShouldPromptEnableService(false)
            }

            return true
        } else if (preference == enableFloatingScreenshotButton) {
            repository.floatingEnable = newValue as Boolean
            return true
        } else if (preference == enableAddToCollectionButton) {
            repository.addToCollectionEnable = newValue as Boolean
            pref?.disableNoMoreSortingDialog()
            return true
        }

        return false
    }

    override fun onPreferenceClick(preference: Preference): Boolean {
        when (preference) {
            giveFeedbackPreference -> context?.let { showFeedbackDialog(it); return true }
            shareWithFriendsPreference -> context?.let {
                showShareAppDialog(it)
                return true
            }
            aboutPreference -> context?.let { showAboutPage(); return true }
        }

        return false
    }

    private fun enableCaptureServiceChildItems(enable: Boolean) {
        enableFloatingScreenshotButton.isVisible = enable
        enableAddToCollectionButton.isVisible = enable
    }

    private fun showFeedbackDialog(context: Context) {
        val dialog = AlertDialog.Builder(context).create()

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_give_feedback, null as ViewGroup?)
        dialogView.findViewById<Button>(R.id.dialog_give_feedback_btn_go_rate).setOnClickListener {
            goToPlayStore(context)
            dialog?.dismiss()
        }
        dialogView.findViewById<Button>(R.id.dialog_give_feedback_btn_feedback).setOnClickListener {
            goToFeedback(context)
            dialog?.dismiss()
        }
        dialog.setView(dialogView)
        dialog.setCanceledOnTouchOutside(true)
        dialog.show()
    }

    private fun showShareAppDialog(context: Context) {
        PromoteShareHelper.showShareAppDialog(context)
    }

    private fun showAboutPage() {
        activity?.supportFragmentManager
                ?.beginTransaction()
                ?.replace(R.id.container, AboutFragment())
                ?.addToBackStack(AboutFragment.TAG)
                ?.commitAllowingStateLoss()
    }

    private fun goToPlayStore(context: Context) {
        PromoteRatingHelper.goToPlayStore(context)
    }

    private fun goToFeedback(context: Context) {
        PromoteRatingHelper.goToFeedback(context)
    }

    private fun checkOverlayPermission() {
        val context = context ?: return
        val hasPermission = PermissionHelper.hasOverlayPermission(context)

        if (hasPermission) {
            if (overlayPermissionRequested) {
                overlayPermissionRequested = false
                // Since overlayPermissionRequested will only be set to true after user toggles on
                // the switch without overlay permission, in which case the preference value has already
                // been set to "true", no need to set it again here
                enableCaptureButton()
            }
        } else {
            // Permission disabled after resume => set false to repo
            ScryerApplication.getSettingsRepository().floatingEnable = false
        }
    }

    private fun onFloatingEnableStateChanged(enabled: Boolean) {
        val activity = activity ?: return

        if (enabled) {
            if (PermissionHelper.hasOverlayPermission(activity)) {
                enableCaptureButton()
            } else {
                overlayPermissionRequested = true
                PermissionHelper.requestOverlayPermission(activity, MainActivity.REQUEST_CODE_OVERLAY_PERMISSION)
            }
        } else {
            val intent = Intent(activity, ScryerService::class.java)
            intent.action = ScryerService.ACTION_DISABLE_CAPTURE_BUTTON
            activity.startService(intent)
        }
    }

    private fun enableCaptureButton() {
        val intent = Intent(activity, ScryerService::class.java)
        intent.action = ScryerService.ACTION_ENABLE_CAPTURE_BUTTON
        activity?.startService(intent)
    }



    companion object {
        private const val DEBUG_CLICKS_THRESHOLD = 18
    }
}
