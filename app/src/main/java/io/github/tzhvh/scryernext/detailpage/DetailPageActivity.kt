/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.detailpage

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.provider.Settings
import android.text.util.Linkify
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.viewpager.widget.ViewPager
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.floatingactionbutton.FloatingActionButton
import com.google.android.material.snackbar.Snackbar
import com.google.mlkit.vision.text.Text
import kotlinx.coroutines.*
import me.saket.bettermovementmethod.BetterLinkMovementMethod
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.collectionview.OnDeleteScreenshotListener
import io.github.tzhvh.scryernext.collectionview.showDeleteScreenshotDialog
import io.github.tzhvh.scryernext.collectionview.showScreenshotInfoDialog
import io.github.tzhvh.scryernext.collectionview.showShareScreenshotDialog
import io.github.tzhvh.scryernext.databinding.ActivityDetailPageBinding
import io.github.tzhvh.scryernext.ingestion.MlKitOcrStage
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotContentModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.preference.PreferenceWrapper
import io.github.tzhvh.scryernext.promote.Promoter
import io.github.tzhvh.scryernext.sortingpanel.SortingPanelActivity
import io.github.tzhvh.scryernext.ui.ScryerToast
import io.github.tzhvh.scryernext.viewmodel.ScreenshotViewModel
import kotlin.coroutines.CoroutineContext

class DetailPageActivity : AppCompatActivity(), CoroutineScope {

    private lateinit var binding: ActivityDetailPageBinding

    companion object Launcher {
        private const val EXTRA_SCREENSHOT_ID = "screenshot_id"
        private const val EXTRA_COLLECTION_ID = "collection_id"
        private const val EXTRA_SEARCH_KEYWORD = "search_keyword"

        private const val SUPPORT_SLIDE = true

        private const val IMAGE_SCALE_NORMAL_MODE = 1f
        private const val IMAGE_SCALE_TEXT_MODE = 0.9f

        fun showDetailPage(context: Context, screenshot: ScreenshotModel, srcView: View?,
                           collectionId: String? = null, searchKeyword: String? = null) {
            val intent = Intent(context, DetailPageActivity::class.java)
//            val bundle = srcView?.let {
//                ViewCompat.getTransitionName(it)?.let { transitionName ->
//                    val option = ActivityOptionsCompat.makeSceneTransitionAnimation(
//                            context as Activity, srcView, transitionName)
//                    option.toBundle()
//                }
//            }
            intent.putExtra(EXTRA_SCREENSHOT_ID, screenshot.id)
            collectionId?.let {
                intent.putExtra(EXTRA_COLLECTION_ID, collectionId)
            }
            searchKeyword?.let {
                intent.putExtra(EXTRA_SEARCH_KEYWORD, searchKeyword)
            }
            (context as AppCompatActivity).startActivity(intent)
        }
    }

    private var shareMenu: MenuItem? = null
    private var moveToMenu: MenuItem? = null
    private var screenshotInfoMenu: MenuItem? = null
    private var deleteMenu: MenuItem? = null
    private var selectAllMenu: MenuItem? = null

    /** Where did the user came from to this page **/
    private val srcCollectionId: String? by lazy {
        intent?.getStringExtra(EXTRA_COLLECTION_ID)
    }

    private val searchKeyword: String? by lazy {
        intent?.getStringExtra(EXTRA_SEARCH_KEYWORD)
    }

    private val screenshotId: String by lazy {
        val id = intent?.getStringExtra(EXTRA_SCREENSHOT_ID)
        id ?: throw IllegalArgumentException("invalid screenshot id")
    }

    private val viewModel: ScreenshotViewModel by lazy {
        ScreenshotViewModel.get(this)
    }

    private lateinit var screenshots: List<ScreenshotModel>
    private val loadingViewController: LoadingViewGroup by lazy {
        LoadingViewGroup(this)
    }

    private val prefs = PreferenceWrapper(this)

    private var isRecognizing = false
    private var isTextMode = false
    private var isEnterTransitionPostponed = true

    private val adapter = DetailPageAdapter()
    private lateinit var graphicOverlayHelper: GraphicOverlayHelper

    /* whether the user has run ocr on the current image before swiping to the next one */
    private var hasRunOcr = false

    /**
     * Issue 15 (option b): the ML Kit decode+OCR mechanism, shared with the ingestion
     * engine. DetailPage keeps its own orchestration ([Result] taxonomy, dimension check,
     * `isRecognizing` write gate, [Promoter] call); only the ML Kit call site moves off
     * the legacy [io.github.tzhvh.scryernext.scan.OcrTextHelper]. Lazy like the other
     * Activity-owned dependencies; the default `TextRecognizer` is itself lazy inside the
     * stage's companion.
     */
    private val ocrStage by lazy { MlKitOcrStage() }

    private val screenSize: RectF by lazy {
        val size = Point().apply {
            windowManager.defaultDisplay.getRealSize(this)
        }
        RectF(0f, 0f, size.x.toFloat(), size.y.toFloat())
    }

    private val itemCallback = object : DetailPageAdapter.ItemCallback {
        override fun onItemClicked(item: ScreenshotModel) {
            toggleActionBar()
        }

        override fun onItemLoaded(item: ScreenshotModel) {
            if (isEnterTransitionPostponed && item.id == screenshotId) {
                isEnterTransitionPostponed = false
                supportStartPostponedEnterTransition()
            }
        }
    }

    private val imageStateCallback = object : DetailPageAdapter.ImageStateCallback {
        override fun onScaleChanged(pageView: DetailPageAdapter.PageView) {
            binding.viewPager.pageLocked = pageView.isScaled()
        }
    }

    private val activityJob = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + activityJob

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        binding = ActivityDetailPageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        graphicOverlayHelper = GraphicOverlayHelper(binding.graphicOverlay)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        supportPostponeEnterTransition()

        initActionBar()
        initViewPager()
        initFab()
        initPanel()

        updateUI()

        if (!prefs.isOcrOnboardingShown()) {
            showOcrOnboarding()
        }

    }

    override fun onDestroy() {
        activityJob.cancel()
        super.onDestroy()
    }

    override fun onBackPressed() {
        when {
            isTextMode -> {
                isTextMode = false
                updateUI()
            }

            supportActionBar?.isShowing != true -> {
                toggleActionBar()
            }

            else -> {
                super.onBackPressed()
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_detail, menu)

        shareMenu = menu.findItem(R.id.action_share)
        shareMenu?.icon?.let { icon ->
            val wrapped = DrawableCompat.wrap(icon).mutate()
            DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, R.color.white))
        }

        moveToMenu = menu.findItem(R.id.action_move_to)
        if (srcCollectionId == CollectionModel.CATEGORY_NONE) {
            moveToMenu?.let {
                it.icon?.let { icon ->
                    val wrapped = DrawableCompat.wrap(icon).mutate()
                    DrawableCompat.setTint(wrapped, ContextCompat.getColor(this, R.color.white))
                }
                it.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
        }
        screenshotInfoMenu = menu.findItem(R.id.action_screenshot_info)

        deleteMenu = menu.findItem(R.id.action_delete)

        selectAllMenu = menu.findItem(R.id.action_select_all)
        selectAllMenu?.isVisible = false

        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.action_share -> {
                showShareScreenshotDialog(this, screenshots[binding.viewPager.currentItem])
            }
            R.id.action_move_to -> {
                startActivity(SortingPanelActivity.sortOldScreenshot(this,
                        screenshots[binding.viewPager.currentItem]))
            }
            R.id.action_screenshot_info -> {
                showScreenshotInfoDialog(this, screenshots[binding.viewPager.currentItem])
            }
            R.id.action_delete -> {
                showDeleteScreenshotDialog(this, screenshots[binding.viewPager.currentItem],
                        object : OnDeleteScreenshotListener {
                            override fun onDeleteScreenshot() {
                                finish()
                            }
                        })
            }
            R.id.action_select_all -> {
                selectAllBlocks()
            }
            else -> return super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun initActionBar() {
        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)

        toolbar.setNavigationOnClickListener {
            onBackPressed()
        }

        toolbar.setOnTouchListener { _, event ->
            routeUnhandledEventToOverlay(event)
        }

        supportActionBar?.apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
    }

    /* Toolbar always eat all touch events regardless they are handled or not, workaround here to
     * dispatch touch event to underlying graphic overlay */
    private fun routeUnhandledEventToOverlay(event: MotionEvent): Boolean {
        val scale = IMAGE_SCALE_TEXT_MODE
        val leftDiff = binding.viewPager.measuredWidth * (1 - scale) / 2f
        val x = (event.x - leftDiff) / scale
        val y = event.y / scale + (binding.toolbar.layoutParams as ViewGroup.MarginLayoutParams).topMargin
        event.setLocation(x, y)
        return binding.graphicOverlay.dispatchTouchEvent(event)
    }

    private fun initViewPager() {
        launch(Dispatchers.Main) {
            screenshots = getScreenshots().sortedByDescending { it.lastModified }
            binding.viewPager.adapter = adapter.apply {
                screenshots = this@DetailPageActivity.screenshots
                itemCallback = this@DetailPageActivity.itemCallback
                imageStateCallback = this@DetailPageActivity.imageStateCallback
            }
            binding.viewPager.addOnPageChangeListener(object : ViewPager.SimpleOnPageChangeListener() {
                override fun onPageSelected(position: Int) {
                    hasRunOcr = false
                }
            })
            binding.viewPager.currentItem = screenshots.indexOfFirst { it.id == screenshotId }
        }
    }

    private fun initFab() {
        val fab = findViewById<FloatingActionButton>(R.id.text_mode_fab)

        val fabListener = View.OnClickListener {
            when (it.id) {
                R.id.text_mode_fab -> {
                    isRecognizing = true
                    startRecognition()
                }

                R.id.cancel_fab -> {
                    isRecognizing = false
                    updateUI()
                }
            }
        }

        fab.setOnClickListener(fabListener)
        binding.cancelFab.setOnClickListener(fabListener)
    }

    private fun initPanel() {
        BottomSheetBehavior.from(binding.textModePanelContent)
                .setBottomSheetCallback(object : BottomSheetBehavior.BottomSheetCallback() {
                    override fun onSlide(bottomSheet: View, slideOffset: Float) {

                    }

                    override fun onStateChanged(bottomSheet: View, newState: Int) {
                        if (newState == BottomSheetBehavior.STATE_COLLAPSED) {
                            unselectAllBlocks()
                            switchToHintModePanelLayout()
                        }

                        if (newState == BottomSheetBehavior.STATE_EXPANDED
                                && graphicOverlayHelper.getSelectedText() == "") {
                            BottomSheetBehavior.from(binding.textModePanelContent).state = BottomSheetBehavior.STATE_COLLAPSED
                        }
                    }
                })

        binding.textModePanelTextView.movementMethod = BetterLinkMovementMethod.newInstance().setOnLinkClickListener { _, _ ->
            false
        }

        binding.textModePanelHint.setOnClickListener { }
    }

    private fun startRecognition() {
        val appContext = applicationContext
        launch(Dispatchers.Main) {
            updateUI()

            val screenshot = screenshots[binding.viewPager.currentItem]
            val result = withContext(Dispatchers.Default) {
                runTextRecognition(screenshot)
            }

            if (result is Result.Success) {
                if (result is Result.WeiredImageSize) {
                    ScryerToast.makeText(this@DetailPageActivity,
                            getString(R.string.detail_ocr_error_edgecase),
                            Toast.LENGTH_SHORT).show()
                }

                // §7.2 (issue 15): a success (incl. WeiredImageSize success-with-warning)
                // writes the recognized text + processed=true. Write runs on Default so the
                // isRecognizing gate below still sees the right state regardless of write timing.
                writeOcrResultToDb(screenshot, result.value.text)

                if (isRecognizing) {
                    isTextMode = true
                    processTextRecognitionResult(result.value)
                    updateUI()
                    if (!hasRunOcr) {
                        hasRunOcr = true
                        Promoter.onOcrButtonClicked(appContext)
                    }
                }

            } else if (result is Result.Unavailable) {
                showConnectPromptSnackbar()

            } else if (result is Result.Failed) {
                // §7.2 (issue 15): a permanent-content failure (corrupt/illegible) now writes
                // processed=true-but-empty instead of writing nothing. The legacy path left such
                // files processed=false, so issue 13's DiscoveryWorker re-counted them forever
                // (the backlog notification that never clears — ADR 0004 §7.2). Unavailable is a
                // transient failure (re-attemptable) and still writes nothing, by contrast.
                writeOcrResultToDb(screenshot, "")
                ScryerToast.makeText(this@DetailPageActivity,
                        getString(R.string.detail_ocr_error_failed),
                        Toast.LENGTH_SHORT).show()
            }

            isRecognizing = false
            updateUI()
        }
    }

    /**
     * DetailPage's single-file write (issue 15). Mirrors the engine's [RoomWriteSink] two-step
     * — content row + `processed=true` on the screenshot row — against the repository exposed by
     * [viewModel] (which delegates [ScreenshotRepository]). Uses [ScreenshotContentModel] for the
     * text row and copies the in-hand [ScreenshotModel] with `processed=true`, exactly as
     * `RoomWriteSink.commit` does for the bulk path. Transient (Unavailable) outcomes do NOT call
     * this — they leave the row unprocessed so the next run re-attempts.
     */
    private suspend fun writeOcrResultToDb(screenshot: ScreenshotModel, contentText: String) {
        viewModel.updateScreenshotContent(ScreenshotContentModel(screenshot.id, contentText))
        viewModel.updateScreenshots(listOf(screenshot.copy(processed = true)))
    }

    private fun showConnectPromptSnackbar() {
        Snackbar.make(binding.snackbarContainer, R.string.detail_ocr_error_module, Snackbar.LENGTH_LONG).apply {
            setAction(R.string.detail_ocr_error_action_connect) {
                startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
            }
            show()
        }
    }

    private suspend fun runTextRecognition(screenshot: ScreenshotModel): Result {
        // Issue 15 (option b): DetailPage owns the READ — opens the ContentResolver stream and
        // hands the stage `bytes`, mirroring how IngestionEngine consumes Candidate.byteHandle.
        // The stage (MlKitOcrStage.recognize) owns DECODE+OCR over those bytes — the same stage
        // boundary the engine honours. The legacy OcrTextHelper.extractText/decodeFromUri path
        // is no longer referenced from DetailPage (Phase 4.5 deletes the class outright).
        val bytes = try {
            val resolver = contentResolver
            resolver.openInputStream(android.net.Uri.parse(screenshot.uri))?.use { input ->
                input.readBytes()
            } ?: return Result.Failed("decode failed: unable to open screenshot uri")
        } catch (e: Error) {
            return Result.Failed("decode failed: " + e.message)
        }

        // Probe the bitmap bounds cheaply (no allocation) for the dimension check, without
        // decoding the full bitmap — the stage decodes internally. Mirrors the standard
        // inJustDecodeBounds pattern; keeps recognize(bytes) returning only Text + outcome.
        val size = try {
            probeBitmapSize(bytes) ?: return Result.Failed("decode failed: unreadable bitmap header")
        } catch (e: Throwable) {
            return Result.Failed("decode failed: " + e.message)
        }

        return when (val outcome = ocrStage.recognize(bytes)) {
            is OcrTextResult.Success -> {
                if (isValidSize(size.width, size.height)) {
                    Result.Success(outcome.text)
                } else {
                    Result.WeiredImageSize(
                            outcome.text,
                            "weird image size: ${size.width}x${size.height}"
                    )
                }
            }
            is OcrTextResult.TransientFailure ->
                Result.Unavailable("recognize failed: " + outcome.cause.message)
            OcrTextResult.PermanentContentFailure ->
                Result.Failed("recognize failed: permanent content failure")
        }
    }

    /**
     * Cheap dimension probe via `inJustDecodeBounds = true` — decodes only the bitmap
     * header (no pixel allocation), so [isValidSize] can run without holding the full
     * decoded [Bitmap] (which now lives inside the OCR stage).
     */
    private fun probeBitmapSize(bytes: ByteArray): BitmapSize? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        return if (opts.outWidth > 0 && opts.outHeight > 0) {
            BitmapSize(opts.outWidth, opts.outHeight)
        } else null
    }

    private data class BitmapSize(val width: Int, val height: Int)

    private fun isValidSize(width: Int, height: Int): Boolean {
        return if (width >= height) {
            isValidLandscapeSize(width, height)
        } else {
            isValidPortraitSize(width, height)
        }
    }

    private fun isValidPortraitSize(width: Int, height: Int): Boolean {
        val isWidthValid = width <= 1.5f * screenSize.width()
        val isHeightValid = height <= 2 * screenSize.height()
        return isWidthValid && isHeightValid
    }

    private fun isValidLandscapeSize(width: Int, height: Int): Boolean {
        val isWidthValid = width <= screenSize.height()
        val isHeightValid = height <= 2 * screenSize.width()
        return isWidthValid && isHeightValid
    }

    private fun updateUI() {
        val pagerScale: Float
        val pagerTranslation: Float
        val pageView = adapter.findViewForPosition(binding.viewPager.currentItem)

        if (isTextMode) {
            pageView?.resetScale()

            updateFabUI(true, false)
            enableTextModeMenu(true)
            pagerScale = IMAGE_SCALE_TEXT_MODE
            pagerTranslation = -binding.viewPager.height * ((1 - IMAGE_SCALE_TEXT_MODE) / 2f)

            setupTextSelectionCallback(binding.textModePanelTextView)
            updateLoadingViewVisibility(false)
            updateTextModePanelVisibility(true)

        } else {
            updateLoadingViewVisibility(isRecognizing)
            updateFabUI(false, isRecognizing)
            updateTextModePanelVisibility(false)
            enableTextModeMenu(false)
            pagerScale = IMAGE_SCALE_NORMAL_MODE
            pagerTranslation = 1f
            updateGraphicOverlay(emptyList())
        }
        updateNavigationIcon()

        if (isRecognizing) {
            supportActionBar?.hide()
        } else {
            supportActionBar?.show()
        }

        binding.viewPager.pageLocked = (pageView?.isScaled() == true)
        listOf(binding.viewPager, binding.graphicOverlay).forEach {
            it.animate()
                    .scaleX(pagerScale)
                    .scaleY(pagerScale)
                    .translationY(pagerTranslation).duration = 150
        }
    }

    private fun updateLoadingViewVisibility(visible: Boolean) {
        if (visible) {
            loadingViewController.show()
        } else {
            loadingViewController.hide()
        }
    }

    private fun updateFabUI(isTextMode: Boolean, isLoading: Boolean) {
        when {
            isTextMode -> {
                binding.cancelFab.hide()
                binding.textModeFab.hide()
            }

            isLoading -> {
                binding.cancelFab.show()
                binding.textModeFab.hide()
            }

            else -> {
                binding.cancelFab.hide()
                binding.textModeFab.show()
            }
        }
    }

    private fun updateTextModePanelVisibility(visible: Boolean) {
        val visibility = if (visible) {
            BottomSheetBehavior.from(binding.textModePanelContent).state = BottomSheetBehavior.STATE_COLLAPSED
            View.VISIBLE
        } else {
            View.GONE
        }
        binding.textModePanel.visibility = visibility
    }

    private fun enableTextModeMenu(enable: Boolean) {
        shareMenu?.isVisible = !enable
        moveToMenu?.isVisible = !enable
        screenshotInfoMenu?.isVisible = !enable
        deleteMenu?.isVisible = !enable

        selectAllMenu?.isVisible = enable
    }

    private fun updateNavigationIcon() {
        binding.toolbar.navigationIcon = ContextCompat.getDrawable(this, if (isTextMode) {
            R.drawable.close_large
        } else {
            R.drawable.back
        })
    }

    @Suppress("ConstantConditionIf")
    private suspend fun getScreenshots(): List<ScreenshotModel> {
        return withContext(Dispatchers.Default) {
            if (SUPPORT_SLIDE) {
                when {
                    srcCollectionId != null -> {
                        val list = if (srcCollectionId == CollectionModel.CATEGORY_NONE) {
                            listOf(srcCollectionId!!, CollectionModel.UNCATEGORIZED)
                        } else {
                            listOf(srcCollectionId!!)
                        }
                        viewModel.getScreenshotList(list)
                    }
                    searchKeyword != null -> viewModel.searchScreenshotList(searchKeyword!!)
                    else -> viewModel.getScreenshotList()
                }
            } else {
                viewModel.getScreenshot(screenshotId)?.let { listOf(it) } ?: emptyList()
            }
        }
    }

    private fun toggleActionBar() {
        var isShowing = supportActionBar?.isShowing ?: false
        isShowing = !isShowing

        if (isShowing) {
            binding.toolbarBackground.visibility = View.VISIBLE
            supportActionBar?.show()
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
            binding.textModeFab.show()
            binding.cancelFab.hide()

        } else {
            binding.toolbarBackground.visibility = View.INVISIBLE
            supportActionBar?.hide()
            window?.decorView?.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN

            binding.textModeFab.hide()
            binding.cancelFab.hide()
        }
    }

    private fun processTextRecognitionResult(result: Text) {
        val pageView = adapter.findViewForPosition(binding.viewPager.currentItem) ?: return

        val graphicBlocks = graphicOverlayHelper.convertToGraphicBlocks(result, pageView)
//        val graphicBlocks = graphicOverlayHelper.convertWordsToGraphicBlocks(result,
//                listOf("word1", "word2"),
//                pageView)
        updateGraphicOverlay(graphicBlocks)

        switchToHintModePanelLayout()
        updatePanel("")
    }

    private fun updateGraphicOverlay(blocks: List<TextBlockGraphic>) {
        binding.graphicOverlay.setBlocks(blocks, if (isTextMode) {
            GraphicOverlay.MODE_OVERLAY_HIGHLIGHT
        } else {
            GraphicOverlay.MODE_HIGHLIGHT
        })

        graphicOverlayHelper.blocks = blocks

        val touchHelper = GraphicOverlayTouchHelper(this, blocks)
        touchHelper.callback = object : GraphicOverlayTouchHelper.Callback {
            override fun onBlockSelectStateChanged(block: TextBlockGraphic?) {
                val selectedText = graphicOverlayHelper.getSelectedText()
                updatePanel(selectedText)
            }
        }
        binding.graphicOverlay.setOnTouchListener { _, event ->
            touchHelper.onTouchEvent(event)
        }
    }

    private fun selectAllBlocks() {
        graphicOverlayHelper.selectAllBlocks()
        updatePanel(graphicOverlayHelper.getSelectedText())
    }

    private fun unselectAllBlocks() {
        graphicOverlayHelper.unselectAllBlocks()
    }

    private fun setupTextSelectionCallback(textView: TextView) {
        textView.customSelectionActionModeCallback = TextSelectionCallback(textView)
    }

    private fun updatePanel(panelText: String) {
        binding.textModePanelTextView.autoLinkMask = Linkify.ALL
        binding.textModePanelTextView.text = panelText

        val behavior = BottomSheetBehavior.from(binding.textModePanelContent)
        if (panelText.isEmpty()) {
            behavior.state = BottomSheetBehavior.STATE_COLLAPSED
        } else {
            switchToTextModePanelLayout()
            behavior.state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun switchToHintModePanelLayout() {
        binding.textModePanelHandler.visibility = View.GONE
        binding.textModePanelTextView.visibility = View.GONE
        binding.textModePanelHint.visibility = View.VISIBLE
    }

    private fun switchToTextModePanelLayout() {
        binding.textModePanelHandler.visibility = View.VISIBLE
        binding.textModePanelTextView.visibility = View.VISIBLE
        binding.textModePanelHint.visibility = View.GONE
    }

//    private fun showSystemUI() {
//        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
//                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
//                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
//    }
//
//    private fun hideSystemUI() {
//        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
//                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
//                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
//                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
//                View.SYSTEM_UI_FLAG_LOW_PROFILE or
//                View.SYSTEM_UI_FLAG_FULLSCREEN or
//                View.SYSTEM_UI_FLAG_IMMERSIVE
//    }


    private fun showOcrOnboarding() {
        binding.onboardingView.visibility = View.VISIBLE
        binding.onboardingView.setOnClickListener {
            (binding.onboardingView.parent as ViewGroup).removeView(binding.onboardingView)
        }

        binding.onboardingOverlay.overlayMode = GraphicOverlay.MODE_HIGHLIGHT
        binding.onboardingOverlay.overlayColor = ContextCompat.getColor(this, R.color.detail_onboarding_overlay)
        binding.onboardingOverlay.add(object : GraphicOverlay.Graphic(binding.onboardingOverlay) {
            private val spotlightPaint: Paint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }

            override fun draw(canvas: Canvas) {
                canvas.drawCircle((binding.textModeFab.left + binding.textModeFab.right) / 2f,
                        (binding.textModeFab.top + binding.textModeFab.bottom) / 2f,
                        (binding.textModeFab.right - binding.textModeFab.left).toFloat(),
                        spotlightPaint)
            }
        })

        prefs.setOcrOnboardingShown()
    }

    private class LoadingViewGroup(private val activity: DetailPageActivity) {
        fun show() {
            activity.apply {
                binding.loadingOverlay.visibility = View.VISIBLE
                binding.loadingProgress.visibility = View.VISIBLE
                binding.loadingText.visibility = View.VISIBLE
            }
        }

        fun hide() {
            activity.apply {
                binding.loadingOverlay.visibility = View.INVISIBLE
                binding.loadingProgress.visibility = View.INVISIBLE
                binding.loadingText.visibility = View.INVISIBLE
            }
        }
    }

    sealed class Result {
        open class Success(val value: Text) : Result()
        class WeiredImageSize(
                value: Text,
                @Suppress("unused") val msg: String
        ) : Success(value)
        open class Failed(@Suppress("unused") val msg: String) : Result()
        class Unavailable(msg: String) : Failed(msg)
    }

}
