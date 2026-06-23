/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.sortingpanel

import android.annotation.SuppressLint
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import io.github.tzhvh.scryernext.Observer
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.collectionview.showShareScreenshotDialog
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.persistence.SuggestCollectionHelper
import io.github.tzhvh.scryernext.preference.PreferenceWrapper
import io.github.tzhvh.scryernext.promote.Promoter
import io.github.tzhvh.scryernext.ui.BottomDialogFactory
import io.github.tzhvh.scryernext.ui.CollectionNameDialog
import io.github.tzhvh.scryernext.ui.ConfirmationDialog
import io.github.tzhvh.scryernext.ui.ScryerToast
import io.github.tzhvh.scryernext.util.CollectionListHelper
import io.github.tzhvh.scryernext.util.launchIO
import io.github.tzhvh.scryernext.viewmodel.ScreenshotViewModel
import java.util.*
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

class SortingPanelActivity : AppCompatActivity(), CoroutineScope {
    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SCREENSHOT_ID = "screenshot_id"
        const val EXTRA_SCREENSHOT_IDS = "screenshot_ids"
        const val EXTRA_COLLECTION_ID = "collection_id"
        const val EXTRA_SHOW_ADD_TO_COLLECTION = "collection_id"

        private const val MAX_SORTING_PANEL_CANCEL_COUNT = 2

        fun sortCollection(context: Context, collectionId: String): Intent {
            val intent = Intent(context, SortingPanelActivity::class.java)
            intent.putExtra(EXTRA_COLLECTION_ID, collectionId)
            return intent
        }

        fun sortNewScreenshot(context: Context, path: String, showAddToCollection: Boolean): Intent {
            val intent = Intent(context, SortingPanelActivity::class.java)
            intent.putExtra(EXTRA_PATH, path)
            intent.putExtra(EXTRA_SHOW_ADD_TO_COLLECTION, showAddToCollection)
            return intent
        }

        fun sortOldScreenshot(context: Context, screenshot: ScreenshotModel): Intent {
            val intent = Intent(context, SortingPanelActivity::class.java)
            intent.putExtra(EXTRA_SCREENSHOT_ID, screenshot.id)
            return intent
        }

        fun sortScreenshots(context: Context, screenshots: List<ScreenshotModel>): Intent {
            val intent = Intent(context, SortingPanelActivity::class.java)
            val list = ArrayList<String>()
            list.addAll(screenshots.map { it.id })
            intent.putStringArrayListExtra(EXTRA_SCREENSHOT_IDS, list)
            return intent
        }

        private fun isSortingNewScreenshot(intent: Intent?): Boolean {
            return intent?.getStringExtra(EXTRA_PATH)?.let {
                true
            } ?: false
        }
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + activityJob

    private val activityJob = Job()

    private val sortingPanel: SortingPanel by lazy { findViewById<SortingPanel>(R.id.sorting_panel) }

    private val screenshotViewModel: ScreenshotViewModel by lazy {
        ScreenshotViewModel.get(this)
    }

    private val unsortedScreenshots = LinkedList<ScreenshotModel>()
    private val sortedScreenshots = LinkedList<ScreenshotModel>()
    private var currentScreenshot: ScreenshotModel? = null

    private var unsortedCollection: CollectionModel? = null

    private val collectionColors = mutableListOf<Int>()

    /** This will only become non-null if we are sorting a whole collection */
    private var collectionId: String? = null

    /** True when we are sorting screenshots that haven't been reviewed by the user before */
    private val isSortingUncategorized: Boolean
        get() = (collectionId == CollectionModel.UNCATEGORIZED)

    /** True when: 1. Capture and save 2. Move screenshot to another collection  */
    private val isSortingSingleScreenshot: Boolean
        get() = (collectionId == null)

    /*  Update the timestamp of each suggest collection at once in onStop(), so
        the order of collections will keep static during multiple-sorting */
    private val suggestCollectionCreateTime = mutableListOf<Pair<CollectionModel, Long>>()

    /*  Notify promoter the first time one of the screenshots is sorted to any collection */
    private var hasNotifiedPromoter = false

    private val toast: ScryerToast by lazy {
        ScryerToast(this)
    }

    private val persistModel: PersistModel by lazy {
        ViewModelProvider(this).get(PersistModel::class.java)
    }

    private val shouldShowCollectionPanel: Boolean
        get() = if (intent.hasExtra(EXTRA_SHOW_ADD_TO_COLLECTION)) {
            intent.getBooleanExtra(EXTRA_SHOW_ADD_TO_COLLECTION, true)

        } else {
            true
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sorting_panel)

        loadCollectionColorList()
        loadScreenshots(intent, this::onLoadScreenshotsSuccess)
        if (isSortingNewScreenshot(intent)) {
            initActionBar()
        }
        initSortingPanel()
    }

    override fun onStart() {
        super.onStart()
        this.lifecycle.addObserver(this.sortingPanel)
    }

    override fun onStop() {
        this.lifecycle.removeObserver(this.sortingPanel)

        for ((suggestCollection, createTime) in suggestCollectionCreateTime) {
            suggestCollection.createdDate = createTime
            launchIO {
                screenshotViewModel.updateCollection(suggestCollection)
            }
        }
        super.onStop()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        ViewModelProvider(this).get(PersistModel::class.java).reset()
        loadScreenshots(intent, this::onLoadScreenshotsSuccess)
    }

    override fun onBackPressed() {
        if (isSortingUncategorized) {
            val dialog = ConfirmationDialog.build(this,
                    getString(R.string.dialogue_skipsorting_title_skip),
                    getString(R.string.dialogue_skipsorting_action_skip),
                    DialogInterface.OnClickListener { _, _ ->
                        flushToUnsortedCollection()
                        finishAndRemoveTask()
                    },
                    getString(android.R.string.cancel),
                    DialogInterface.OnClickListener { _, _ ->
                    })
            dialog.viewHolder.message?.text = getString(R.string.dialogue_skipsorting_content_moveto)
            dialog.viewHolder.subMessage?.visibility = View.VISIBLE
            dialog.viewHolder.subMessage?.text = getString(R.string.dialogue_skipsorting_content_count,
                    unsortedScreenshots.size + 1)
            dialog.asAlertDialog().show()
        } else {
            launch(Dispatchers.Main.immediate) {
                showNoMoreDialogIfNeeded()
                super.onBackPressed()
            }

        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_sorting_panel, menu)

        menu.findItem(R.id.action_share)?.let {
            val icon = it.icon
            if (icon != null) {
                val wrapped = DrawableCompat.wrap(icon).mutate()
                DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, R.color.white))
            }
        }

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> {
                currentScreenshot?.let { showShareScreenshotDialog(this, it) }
            }
            else -> return super.onOptionsItemSelected(item)
        }

        return true
    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initActionBar() {
        findViewById<View>(R.id.toolbar_background).visibility = View.VISIBLE
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.visibility = View.VISIBLE
        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            showDeleteScreenshotDialog(Runnable {
                launch(Dispatchers.Main.immediate) {
                    showAddedToast(unsortedCollection, unsortedScreenshots.isNotEmpty())
                    showNoMoreDialogIfNeeded()
                    finishAndRemoveTask()
                }
            }, Runnable {
                launchIO {
                    currentScreenshot?.let {
                        ScryerApplication.getScreenshotRepository().deleteScreenshot(it)
                        try {
                            contentResolver.delete(android.net.Uri.parse(it.uri), null, null)
                        } catch (e: Exception) {
                            android.util.Log.w("SortingPanelActivity", "Cannot delete capture content", e)
                        }
                    }
                }
                finishAndRemoveTask()
            })
        }

        supportActionBar?.apply {
            setDisplayShowTitleEnabled(false)
        }
    }

    private fun showDeleteScreenshotDialog(action: Runnable, negativeAction: Runnable) {
        val dialog = BottomDialogFactory.create(this, R.layout.dialog_bottom)
        dialog.findViewById<ConstraintLayout>(R.id.top_layout)?.minHeight = 0
        dialog.findViewById<TextView>(R.id.title)?.visibility = View.GONE
        dialog.findViewById<TextView>(R.id.subtitle)?.text = getString(R.string.sheet_saveordelete_content_screenshot)
        dialog.findViewById<View>(R.id.dont_ask_again_checkbox)?.visibility = View.GONE

        dialog.findViewById<TextView>(R.id.positive_button)?.apply {
            setText(R.string.sheet_saveordelete_action_save)
            setOnClickListener {
                action.run()
                dialog.dismiss()
            }
        }

        dialog.findViewById<TextView>(R.id.negative_button)?.apply {
            setText(R.string.action_delete)
            setOnClickListener {
                negativeAction.run()
                dialog.dismiss()
            }
        }

        dialog.setOnCancelListener {
            dialog.dismiss()
        }

        dialog.show()
    }

    private fun flushToUnsortedCollection() {
        showAddedToast(unsortedCollection, false)
        launchIO {
            screenshotViewModel.batchMove(unsortedScreenshots, CollectionModel.CATEGORY_NONE)
        }
    }

    private fun loadCollectionColorList() {
        val typedArray = resources.obtainTypedArray(R.array.collection_colors)
        val length = typedArray.length()
        for (i in 0 until length) {
            val color = typedArray.getColor(i, ContextCompat.getColor(this, R.color.primaryTeal))
            collectionColors.add(color)
        }
        typedArray.recycle()
    }

    private fun initSortingPanel() {
        val collectionData = screenshotViewModel.getCollections()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                collectionData.collect { collection ->
                    unsortedCollection = collection.find { it.id == CollectionModel.CATEGORY_NONE }
                }
            }
        }

        sortingPanel.collectionSource = collectionData
        sortingPanel.showCollectionPanel = shouldShowCollectionPanel
        sortingPanel.callback = object : SortingPanelAdapter.Callback {
            override fun onClickStart(collection: CollectionModel) {
                onCollectionClickStart(collection)
            }

            override fun onClickFinish(collection: CollectionModel) {
                onCollectionClickFinish(collection)
            }

            override fun onNewCollectionClick() {
                onNewCollectionClicked()
            }
        }
    }

    private fun onNewModelAvailable() {
        if (unsortedScreenshots.isEmpty()) {
            finishAndRemoveTask()
            return
        }

        currentScreenshot = this.unsortedScreenshots.removeFirst()?.apply {
            sortedScreenshots.addLast(this)
            onScreenshotViewed(this)

            sortingPanel.screenshot = this
            sortingPanel.setProgress(sortedScreenshots.size, sortedScreenshots.size + unsortedScreenshots.size)

            if (!isSortingNewScreenshot(intent) && unsortedScreenshots.isEmpty()) {
                sortingPanel.setActionText(getString(android.R.string.cancel))
            }
        }
    }

    private fun onScreenshotViewed(screenshot: ScreenshotModel) {
        if (!shouldShowCollectionPanel) {
            return
        }

        if (screenshot.collectionId == CollectionModel.UNCATEGORIZED) {
            screenshot.collectionId = CollectionModel.CATEGORY_NONE
            launchIO {
                screenshotViewModel.updateScreenshots(listOf(screenshot))
            }
        }
    }

    private fun loadScreenshots(intent: Intent?, onFinished: (list: List<ScreenshotModel>) -> Unit) {
        if (persistModel.isLoaded()) {
            onFinished.invoke(persistModel.getScreenshots())
            return
        }

        intent ?: run {
            onLoadScreenshotsFailed()
            return
        }
        collectionId = null

        launch(Dispatchers.Main) {
            when {
                intent.hasExtra(EXTRA_PATH) -> {
                    loadNewScreenshot(getFilePath(intent))
                }

                intent.hasExtra(EXTRA_SCREENSHOT_ID) -> {
                    intent.getStringExtra(EXTRA_SCREENSHOT_ID)?.let { loadOldScreenshot(it) }
                }

                intent.hasExtra(EXTRA_COLLECTION_ID) -> {
                    collectionId = intent.getStringExtra(EXTRA_COLLECTION_ID)
                    collectionId?.let {
                        loadCollection(it)
                    }
                }

                intent.hasExtra(EXTRA_SCREENSHOT_IDS) -> {
                    val list: List<String>? = intent.getStringArrayListExtra(EXTRA_SCREENSHOT_IDS)
                    list?.let {
                        loadScreenshots(list)
                    }
                }

                else -> {
                    null
                }

            }?.let {
                persistModel.onScreenshotLoaded(it)
                onFinished.invoke(it)

            }?: onLoadScreenshotsFailed()
        }
    }

    private suspend fun loadScreenshots(
            ids: List<String>
    ): List<ScreenshotModel> = withContext(Dispatchers.Default) {
        ids.mapNotNull {
            screenshotViewModel.getScreenshot(it)
        }
    }

    private suspend fun loadNewScreenshot(
            path: String
    ): List<ScreenshotModel>? = withContext(Dispatchers.Default) {
        createNewScreenshot(path)?.let {
            val result = listOf(it)
            screenshotViewModel.addScreenshot(result)
            result
        }
    }

    private suspend fun loadOldScreenshot(
            screenshotId: String
    ): List<ScreenshotModel>? = withContext(Dispatchers.Default) {
        screenshotViewModel.getScreenshot(screenshotId)?.let {
            listOf(it)
        }
    }

    private suspend fun loadCollection(
            collectionId: String
    ): List<ScreenshotModel>? = withContext(Dispatchers.Default) {
        val idList = if (collectionId == CollectionModel.CATEGORY_NONE) {
            listOf(CollectionModel.UNCATEGORIZED, CollectionModel.CATEGORY_NONE)
        } else {
            listOf(collectionId)
        }

        screenshotViewModel.getScreenshotList(idList)
    }

    private fun onLoadScreenshotsSuccess(screenshots: List<ScreenshotModel>) {
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.INITIALIZED)) {
            return
        }

        this.sortedScreenshots.clear()
        this.unsortedScreenshots.clear()

        val panelModel = ViewModelProvider(this).get(PersistModel::class.java)
        val currentIndex = panelModel.getCurrentIndex()

        val sorted = screenshots.sortedByDescending { it.lastModified }
        this.sortedScreenshots.addAll(sorted.subList(0, currentIndex))
        this.unsortedScreenshots.addAll(sorted.subList(currentIndex, screenshots.size))

        if (screenshots.size == 1) {
            if (isSortingNewScreenshot(intent)) {
                sortingPanel.setActionTextVisibility(View.GONE)
            } else {
                sortingPanel.setActionText(getString(android.R.string.cancel))
            }
            sortingPanel.setProgressVisibility(View.INVISIBLE)
            sortingPanel.setFakeLayerVisibility(View.INVISIBLE)
        } else {
            sortingPanel.setActionText(getString(R.string.multisorting_action_next))
            sortingPanel.setProgressVisibility(View.VISIBLE)
            sortingPanel.setFakeLayerVisibility(View.VISIBLE)
        }

        sortingPanel.setActionCallback {
            if (isSortingUncategorized || isSortingNewScreenshot(intent)) {
                showAddedToast(unsortedCollection, unsortedScreenshots.isNotEmpty())
            }
            onNewModelAvailable()
            panelModel.onNextScreenshot()
        }

        if (!shouldShowCollectionPanel) {
            Handler().postDelayed({ finishAndRemoveTask() }, 1000)
        }

        onNewModelAvailable()
    }

    private fun showAddedToast(model: CollectionModel?, stickToCollapsedPanel: Boolean) {
        model?.let {
            val yOffset = if (sortingPanel.isCollapse() && stickToCollapsedPanel) {
                sortingPanel.getCollapseHeight()
            } else {
                0
            }
            toast.show(getString(R.string.snackbar_addto, it.name), Toast.LENGTH_SHORT, yOffset)
        }
    }

    private fun onLoadScreenshotsFailed() {
        finishAndRemoveTask()
    }

    private fun onCollectionClickStart(collection: CollectionModel) {
        launch(Dispatchers.Main.immediate) {
            val screenshot = currentScreenshot ?: return@launch

            if (SuggestCollectionHelper.isSuggestCollection(collection)) {
                // Once the user selects a suggest collection, we update its id to
                // a random UUID, so that it will be treated as a normal collection from then on

                collection.color = CollectionListHelper.nextCollectionColor(
                        this@SortingPanelActivity, true)
                withContext(Dispatchers.Default) {
                    screenshotViewModel.updateCollectionId(collection, UUID.randomUUID().toString())
                }
                suggestCollectionCreateTime.add(Pair(collection, System.currentTimeMillis()))
            }

            screenshot.collectionId = collection.id
            withContext(Dispatchers.Default) {
                screenshotViewModel.updateScreenshots(listOf(screenshot))
            }

            if (isSortingSingleScreenshot) {
                showAddedToast(collection, false)
            }
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun onCollectionClickFinish(collection: CollectionModel) {
        onNewModelAvailable()
        persistModel.onNextScreenshot()

        onScreenshotSorted()
    }

    private fun onScreenshotSorted() {
        if (isSortingNewScreenshot(intent)) {
            PreferenceWrapper(this).resetPanelCancelCount()
        }

        if (hasNotifiedPromoter) {
            return
        }
        hasNotifiedPromoter = true
        Promoter.onScreenshotSorted(this)
    }

    private fun onNewCollectionClicked() {
        // Since suggest collection is visible on sorting panel, it's reasonable to show error msg
        // when user input a name identical to suggest collection, there's no need to exclude
        // suggest collection when matching for conflict name, set excludeSuggestion to false
        CollectionNameDialog.createNewCollection(this, screenshotViewModel, false) {
            showAddedToast(it, true)
            onCollectionClickStart(it)
            onCollectionClickFinish(it)
        }
    }

    private fun createNewScreenshot(uri: String): ScreenshotModel? {
        if (uri.isNotEmpty()) {
            val (displayName, size) = resolveScreenshotMetadata(uri)
            return ScreenshotModel(
                    uri = uri,
                    displayName = displayName,
                    size = size,
                    lastModified = System.currentTimeMillis(),
                    collectionId = CollectionModel.UNCATEGORIZED)
        }
        return null
    }

    /**
     * Issue 21: resolve display name + size from the captured screenshot's content URI so
     * they are cached on the model at insert time.
     */
    private fun resolveScreenshotMetadata(uri: String): Pair<String, Long> {
        var displayName = ""
        var size = 0L
        try {
            contentResolver.query(
                    android.net.Uri.parse(uri),
                    arrayOf(
                            android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                            android.provider.MediaStore.Images.Media.SIZE),
                    null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.DISPLAY_NAME)
                    val sizeIdx = cursor.getColumnIndex(android.provider.MediaStore.Images.Media.SIZE)
                    if (nameIdx >= 0) displayName = cursor.getString(nameIdx) ?: ""
                    if (sizeIdx >= 0) size = cursor.getLong(sizeIdx)
                }
            }
        } catch (e: SecurityException) {
            displayName = ""
        }
        return displayName to size
    }

    private fun getFilePath(intent: Intent): String {
        // Issue 21: the capture pipeline always delivers a content:// URI here (issue 20);
        // the legacy File-existence gate is gone since paths are no longer filesystem locators.
        return intent.getStringExtra(EXTRA_PATH) ?: ""
    }

    private suspend fun showNoMoreDialogIfNeeded() = suspendCoroutine<Unit> { cont ->
        val pref = PreferenceWrapper(this@SortingPanelActivity)
        if (shouldShowCollectionPanel && isSortingNewScreenshot(intent) && pref.isNoMoreSortingDialogEnabled()) {
            val count = pref.getPanelCancelCount()
            pref.increasePanelCancelCount()
            if ((count + 1) >= MAX_SORTING_PANEL_CANCEL_COUNT) {
                showNoMoreDialog {
                    cont.resume(Unit)
                }
            } else {
                cont.resume(Unit)
            }
        } else {
            cont.resume(Unit)
        }
    }

    private fun showNoMoreDialog(onFinished: () -> Unit) {
        val dialog = ConfirmationDialog.build(this,
                getString(R.string.dialogue_stopasking_title_stop),
                getString(R.string.notification_action_stop),
                DialogInterface.OnClickListener { _, _ ->
                    ScryerApplication.getSettingsRepository().addToCollectionEnable = false
                    onFinished.invoke()
                },
                getString(R.string.dialogue_stopasking_action_alwaysask),
                DialogInterface.OnClickListener { _, _ ->
                    PreferenceWrapper(this).disableNoMoreSortingDialog()
                    onFinished.invoke()
                })
        dialog.viewHolder.message?.text = getString(R.string.dialogue_stopasking_content_stop, getString(R.string.app_name_go))
        dialog.viewHolder.subMessage?.visibility = View.VISIBLE
        dialog.asAlertDialog().apply {
            setOnCancelListener {
                onFinished.invoke()
            }
            setCanceledOnTouchOutside(false)
            show()
        }
    }
}

class PersistModel : ViewModel() {
    private var screenshots = mutableListOf<ScreenshotModel>()
    private var isLoaded = false
    private var currentIdx = 0

    fun onScreenshotLoaded(screenshots: List<ScreenshotModel>) {
        this.screenshots.clear()
        this.screenshots.addAll(screenshots)
        isLoaded = true
    }

    fun getScreenshots(): List<ScreenshotModel> {
        return screenshots
    }

    fun isLoaded(): Boolean {
        return isLoaded
    }

    fun getCurrentIndex(): Int {
        return currentIdx
    }

    fun onNextScreenshot() {
        currentIdx++
    }

    fun reset() {
        if (isLoaded) {
            isLoaded = false
            screenshots.clear()
            currentIdx = 0
        }
    }
}
