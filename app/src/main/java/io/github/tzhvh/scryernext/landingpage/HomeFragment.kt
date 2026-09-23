/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.landingpage

import android.app.Activity
import android.app.SearchManager
import android.content.*
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.ActionBar
import androidx.appcompat.app.AppCompatDialog
import androidx.appcompat.widget.AppCompatCheckBox
import androidx.appcompat.widget.SearchView
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.Navigation
import androidx.preference.PreferenceManager
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.combine
import io.github.tzhvh.scryernext.*
import io.github.tzhvh.scryernext.databinding.FragmentHomeBinding
import io.github.tzhvh.scryernext.databinding.ViewQuickAccessBinding
import io.github.tzhvh.scryernext.collectionview.ScreenshotItemHolder
import io.github.tzhvh.scryernext.detailpage.DetailPageActivity
import io.github.tzhvh.scryernext.detailpage.GraphicOverlay
import io.github.tzhvh.scryernext.extension.navigateSafely
import io.github.tzhvh.scryernext.filemonitor.ExternalScreenshotSync
import io.github.tzhvh.scryernext.filemonitor.ScreenshotFetcher
import io.github.tzhvh.scryernext.ingestion.IngestionConfig
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.ingestion.triggers.BannerMode
import io.github.tzhvh.scryernext.ingestion.triggers.bannerMode
import io.github.tzhvh.scryernext.onboarding.OnboardingPrefs
import io.github.tzhvh.scryernext.onboarding.SetupHubActivity
import io.github.tzhvh.scryernext.permission.MediaAccess
import io.github.tzhvh.scryernext.permission.PermissionHelper
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.persistence.SuggestCollectionHelper
import io.github.tzhvh.scryernext.preference.PreferenceWrapper
import io.github.tzhvh.scryernext.promote.PromoteRatingHelper
import io.github.tzhvh.scryernext.promote.PromoteShareHelper
import io.github.tzhvh.scryernext.setting.SettingsActivity
import io.github.tzhvh.scryernext.ui.BottomDialogFactory
import io.github.tzhvh.scryernext.viewmodel.ScreenshotViewModel
import java.util.*
import kotlin.coroutines.CoroutineContext

class HomeFragment : Fragment(), CoroutineScope {

    companion object {
        private const val LOG_TAG = "HomeFragment"

        const val COLLECTION_COLUMN_COUNT = 2
        const val QUICK_ACCESS_ITEM_COUNT = 5

        private const val PREF_SHOW_ENABLE_SERVICE_DIALOG = "show_enable_service_dialog"

        /** Banner SUCCESS dwell: how long the "Indexing complete ✓" state shows before the banner
         *  re-evaluates against the live backlog. Tuned long enough to read, short enough that the
         *  "done" claim (only true at the instant of completion) isn't held as a potential lie. */
        private const val SUCCESS_DWELL_MS = 4000L
    }

    private val fragmentJob = Job()

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + fragmentJob

    private var quickAccessContainer: ViewGroup? = null
    private var quickAccessBinding: ViewQuickAccessBinding? = null
    private var quickAccessAdapter: QuickAccessAdapter? = null

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!

    private var mainAdapter: MainAdapter? = null

    /**
     * Transient state for the search-spotlight onboarding (ADR 0008): tracks
     * whether the spotlight (and its status-bar tint) is on screen, so
     * `onDestroyView` can restore the tint even if the overlay is never
     * tapped — the old flow leaked the tint when the fragment's view was
     * recreated before dismissal.
     */
    private var searchSpotlightShowing: Boolean = false

    /**
     * Issue 18: in-memory snooze for the idle-backlog banner nudge (Mode A). Set on "Snooze", reset
     * only by process death. Deliberately NOT persisted — a persisted snooze is a poison-pill-shaped
     * footgun (a forgotten snooze hides a growing backlog forever). The system discovery
     * notification remains the persistent nudge; this flag only respects the attention budget within
     * one sitting. See [`PHASE3_DESIGN_DECISIONS.md`](file:///.scratch/ingestion/PHASE3_DESIGN_DECISIONS.md) §5.
     */
    private var bannerSnoozedForSession: Boolean = false

    /**
     * Transient "just completed" flag for the banner SUCCESS state. Set when [renderBanner] observes
     * [Progress.Completed], cleared after a ~4s dwell by a lifecycle-scoped coroutine. The "done"
     * claim is only true at the instant the job finished — every second after, new files could land
     * — so SUCCESS is deliberately short; after the dwell the banner re-evaluates against the live
     * `backlog` (HIDDEN if empty, IDLE_BACKLOG with the new count if not). If the fragment pauses or
     * destroys mid-dwell, the coroutine is cancelled and this resets — next STARTED recomputes.
     */
    private var bannerJustCompleted: Boolean = false
    private var successDismissJob: kotlinx.coroutines.Job? = null

    private val viewModel: ScreenshotViewModel by lazy {
        ScreenshotViewModel.get(this)
    }

    private val pref: PreferenceWrapper? by lazy {
        context?.let {
            PreferenceWrapper(it)
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        mainAdapter = MainAdapter(this)
        quickAccessAdapter = QuickAccessAdapter(context)

        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        quickAccessBinding = ViewQuickAccessBinding.inflate(inflater)
        quickAccessContainer = quickAccessBinding?.root as ViewGroup
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        initQuickAccessList(view.context)
        initCollectionList(view.context)
        initIngestionBanner()
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        initActionBar()
    }

    override fun onResume() {
        super.onResume()
        // ADR 0008: the wizard + hub own setup; Home owns the per-resume side
        // effects that used to fire incidentally off the old flow's terminal
        // states. The backfill sync is the ONLY writer of foreign-screenshot
        // DB rows, so it must run on every foregrounded resume (gated on the
        // media grant — a denied grant degrades to a no-op fetch anyway).
        syncExternalScreenshotsIfPermitted()
        routeToSetupHubIfNeeded()
        promptEnableServiceIfNeeded()
        if (shouldShowSearchOnboarding()) {
            // Once ever, after setup — re-anchored from onPermissionFlowFinish.
            showSearchOnboarding()
        }
    }

    override fun onStart() {
        super.onStart()
        promptPromotionIfNeeded()
    }

    override fun onDestroyView() {
        // Restore the status-bar tint if the search spotlight is still up —
        // its dismiss-tap may never come (view recreation, navigation away).
        if (searchSpotlightShowing) {
            searchSpotlightShowing = false
            activity?.window?.statusBarColor = Color.TRANSPARENT
        }
        mainAdapter = null
        quickAccessAdapter = null
        quickAccessContainer = null
        quickAccessBinding = null
        _binding = null
        super.onDestroyView()
    }

    override fun onDestroy() {
        fragmentJob.cancel()
        super.onDestroy()
    }

    // ---------------------------------------------------------------------- Issue 18: in-app banner

    /**
     * Issue 18: the in-app ingestion banner — the landing-screen mirror of the discovery
     * notification. Mounts above the grid; one component, two states (Mode A nudge / Mode B status).
     * Mirrors the existing `repeatOnLifecycle(STARTED)` precedent (initQuickAccessList/initCollectionList);
     * reads the app-scope store + session via the static accessors (no cast/ViewModel — the
     * codebase convention). The visibility *decision* is the pure [bannerMode] helper; this is the
     * view-binding + lifecycle plumbing that can't run on the JVM.
     *
     * Observed sources (combined so a single re-emit re-renders atomically):
     * - `session.isSessionPending()` — the union of in-memory `Indexing` and WorkInfo state.
     * - `store.backlog` — the unindexed count (drives Mode A).
     * - `store.progress` — the live state (Mode B reads `Indexing.current/total`, else cosmetic
     *   continuity via `session.loadCosmetic()`).
     */
    private fun initIngestionBanner() {
        val session = ScryerApplication.getIngestionSession()
        val store = ScryerApplication.getIngestionProgressStore()

        binding.ingestionBannerActionIndex.setOnClickListener { session.startBulk() }
        binding.ingestionBannerActionSnooze.setOnClickListener {
            // In-memory only; reset by process death. Suppresses Mode A, never Mode B.
            bannerSnoozedForSession = true
            renderBanner(store.progress.value)
        }
        binding.ingestionBannerActionStop.setOnClickListener { session.abort() }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                combine(
                    session.isSessionPending(),
                    store.backlog,
                    store.progress
                ) { pending, backlog, progress -> Triple(pending, backlog, progress) }
                    .collect { (pending, backlog, progress) ->
                        // Arm the SUCCESS dwell when we observe a terminal Completed transition.
                        // The "done" claim is only true at the instant of completion, so SUCCESS is
                        // a transient overlay — the 4s coroutine clears bannerJustCompleted and the
                        // banner re-evaluates against the live backlog. Guarded so a re-emit of an
                        // already-Completed value (e.g. config change) doesn't re-arm a stale dwell.
                        if (progress is Progress.Completed && !bannerJustCompleted && successDismissJob == null) {
                            bannerJustCompleted = true
                            successDismissJob = viewLifecycleOwner.lifecycleScope.launch {
                                kotlinx.coroutines.delay(SUCCESS_DWELL_MS)
                                bannerJustCompleted = false
                                successDismissJob = null
                                renderBanner(store.progress.value)
                            }
                        }
                        renderBanner(pending, backlog, progress)
                    }
            }
        }
    }

    /**
     * Render the banner for the latest observed state. Overloads resolve to the same logic; the
     * `(pending, backlog, progress)` form is the combined-collect path, and the `(progress)` form
     * is the snooze-tap / success-dwell path (re-renders from the current values without
     * re-collecting).
     */
    private fun renderBanner(pending: Boolean, backlog: Int, progress: Progress) {
        val mode = bannerMode(
            pending = pending,
            backlog = backlog,
            threshold = IngestionConfig.BACKLOG_THRESHOLD,
            snoozed = bannerSnoozedForSession,
            justCompleted = bannerJustCompleted
        )
        when (mode) {
            BannerMode.HIDDEN -> {
                binding.ingestionBanner.visibility = View.GONE
            }
            BannerMode.IDLE_BACKLOG -> {
                binding.ingestionBanner.visibility = View.VISIBLE
                binding.ingestionBannerTitle.text =
                    getString(R.string.discovery_notification_text, backlog)
                binding.ingestionBannerProgress.visibility = View.GONE
                binding.ingestionBannerActionIndex.visibility = View.VISIBLE
                binding.ingestionBannerActionSnooze.visibility = View.VISIBLE
                binding.ingestionBannerActionStop.visibility = View.GONE
            }
            BannerMode.ACTIVE -> {
                binding.ingestionBanner.visibility = View.VISIBLE
                binding.ingestionBannerTitle.text = getString(R.string.ingestion_notification_title)
                // Mode B reads current/total from a live Indexing event, or falls back to the
                // persisted cosmetic numerics for cross-resurrection continuity (issue 14a).
                val indexing = progress as? Progress.Indexing
                if (indexing != null && indexing.total > 0) {
                    binding.ingestionBannerProgress.visibility = View.VISIBLE
                    binding.ingestionBannerProgress.text =
                        getString(R.string.ingestion_notification_progress, indexing.current, indexing.total)
                } else {
                    val cosmetic = ScryerApplication.getIngestionSession().loadCosmetic()
                    if (cosmetic != null && cosmetic.sessionStartTotal > 0) {
                        binding.ingestionBannerProgress.visibility = View.VISIBLE
                        binding.ingestionBannerProgress.text = getString(
                            R.string.ingestion_notification_progress,
                            cosmetic.doneCount,
                            cosmetic.sessionStartTotal
                        )
                    } else {
                        binding.ingestionBannerProgress.visibility = View.GONE
                    }
                }
                binding.ingestionBannerActionIndex.visibility = View.GONE
                binding.ingestionBannerActionSnooze.visibility = View.GONE
                binding.ingestionBannerActionStop.visibility = View.VISIBLE
            }
            BannerMode.SUCCESS -> {
                // Transient terminal state: no actions (nothing to index/stop), progress line
                // hidden. Auto-dismissed by the successDismissJob coroutine; if a new backlog
                // appeared during the dwell, the dwell finishes first then re-evaluates to
                // IDLE_BACKLOG with the fresh count (deliberate: confirmation then re-prompt).
                binding.ingestionBanner.visibility = View.VISIBLE
                binding.ingestionBannerTitle.text = getString(R.string.ingestion_banner_success)
                binding.ingestionBannerProgress.visibility = View.GONE
                binding.ingestionBannerActionIndex.visibility = View.GONE
                binding.ingestionBannerActionSnooze.visibility = View.GONE
                binding.ingestionBannerActionStop.visibility = View.GONE
            }
        }
    }

    /** Snooze-tap re-render: re-evaluate from the latest store values (no fresh collect needed). */
    private fun renderBanner(progress: Progress) {
        val store = ScryerApplication.getIngestionProgressStore()
        renderBanner(
            pending = store.progress.value is Progress.Indexing,
            backlog = store.backlog.value,
            progress = progress
        )
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        activity?.let { safeActivity ->
            inflater.inflate(R.menu.menu_main, menu)

            menu.findItem(R.id.action_settings).setOnMenuItemClickListener {
                startActivity(Intent(safeActivity, SettingsActivity::class.java))
                true
            }

            menu.findItem(R.id.action_svg_viewer).apply {
                setOnMenuItemClickListener {
                    startActivity(Intent(safeActivity, SvgViewerActivity::class.java))
                    true
                }
                isVisible = BuildConfig.DEBUG
            }

            menu.findItem(R.id.action_zvec_benchmark).apply {
                setOnMenuItemClickListener {
                    startActivity(Intent(safeActivity, ZvecBenchmarkActivity::class.java))
                    true
                }
                isVisible = BuildConfig.DEBUG
            }

            menu.findItem(R.id.action_zvec_inspector).apply {
                setOnMenuItemClickListener {
                    startActivity(Intent(safeActivity, ZvecInspectorActivity::class.java))
                    true
                }
                isVisible = BuildConfig.DEBUG
            }

            menu.findItem(R.id.action_ingestion_inspector).apply {
                setOnMenuItemClickListener {
                    startActivity(Intent(safeActivity, IngestionInspectorActivity::class.java))
                    true
                }
                isVisible = BuildConfig.DEBUG
            }

            createOptionsMenuSearchView(safeActivity)
        }
    }

    /**
     * ADR 0008 — the search spotlight, re-anchored: it used to fire only from
     * the old flow's `onPermissionFlowFinish`; it now fires on the first Home
     * resume after setup (the `search_onboarding_shown` flag keeps the
     * once-ever semantics). Two latent bugs fixed while re-anchoring: the
     * flag is consumed only when the spotlight actually renders, and the
     * status-bar tint is restored if the view dies before dismissal.
     */
    private fun showSearchOnboarding() {
        val b = _binding ?: return
        val context = context ?: return
        activity?.window?.let {
            it.statusBarColor = ContextCompat.getColor(context, R.color.detail_onboarding_overlay)
        }
        searchSpotlightShowing = true

        b.onboardingView.visibility = View.VISIBLE
        b.onboardingView.setOnClickListener {
            searchSpotlightShowing = false
            activity?.window?.let {
                it.statusBarColor = Color.TRANSPARENT
            }
            (b.onboardingView.parent as ViewGroup).removeView(b.onboardingView)
        }

        b.onboardingOverlay.overlayMode = GraphicOverlay.MODE_HIGHLIGHT
        b.onboardingOverlay.overlayColor = ContextCompat.getColor(context, R.color.detail_onboarding_overlay)
        b.onboardingOverlay.add(object : GraphicOverlay.Graphic(b.onboardingOverlay) {
            private val spotlightPaint: Paint = Paint().apply {
                xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            }

            override fun draw(canvas: Canvas) {
                val radius = b.toolbar.height + resources.getDimensionPixelSize(R.dimen.common_padding_12dp)

                canvas.drawCircle(0f,
                        0f,
                        radius.toFloat(),
                        spotlightPaint)
            }
        })

        // Consumed only when the spotlight is actually on screen — the old
        // flow set this unconditionally, even when nothing rendered.
        pref?.setSearchOnboardingShown()
    }

    private fun shouldShowSearchOnboarding(): Boolean {
        val context = context ?: return false
        // ADR 0008: the spotlight anchors to the first Home resume *after*
        // setup — the once-ever flag must not be consumed by a resume that
        // happens beneath the wizard on first run.
        if (!OnboardingPrefs.getInstance(context).isOnboardingComplete()) {
            return false
        }
        return !(pref?.isSearchOnboardingShown() ?: true)
    }

    private val dialogQueue = DialogQueue()

    /**
     * ADR 0008 — the external-screenshot backfill sync, re-homed from the old
     * flow's per-resume `FinishState`. Covers the same two cases its comment
     * documented: the fetch must run *after* the media grant (the wizard asks
     * before this can ever run), and returning users backfill screenshots
     * taken since the app was last foregrounded.
     *
     * The fragment's own scope (Dispatchers.Main + fragmentJob), not launchIO:
     * the fetch reads `context`/`viewModel` on the main thread and the job
     * cancels with the fragment; `checkNewScreenshots` switches to IO
     * internally via withContext.
     */
    private fun syncExternalScreenshotsIfPermitted() {
        val context = context ?: return
        val access = PermissionHelper.getMediaAccess(context)
        if (access == MediaAccess.DENIED) {
            // Pointless query — the fetcher would degrade to an empty list.
            return
        }
        launch {
            checkNewScreenshots(pruneUnreadable = access == MediaAccess.GRANTED)
        }
    }

    /**
     * ADR 0008 — the enable-service re-prompt, re-homed from `onWelcomeDone`
     * (whose only trigger died with the welcome screen). Flag-gated: once per
     * soft-disable (`prompt_service_enable` is set by ScryerService's
     * notification soft-stop and cleared when shown or when the service is
     * enabled) — and only after setup completes, so a partially-onboarded
     * upgrade never raises the dialog beneath the wizard.
     */
    private fun promptEnableServiceIfNeeded() {
        val context = context ?: return
        if (!OnboardingPrefs.getInstance(context).isOnboardingComplete()) {
            return
        }
        if (shouldPromptEnableService() && isDialogAllowed(PREF_SHOW_ENABLE_SERVICE_DIALOG)) {
            showEnableServiceDialog()
        }
    }

    private fun initActionBar() {
        setHasOptionsMenu(true)
        setSupportActionBar(activity, view!!.findViewById(R.id.toolbar))
        getSupportActionBar(activity).displayOptions = ActionBar.DISPLAY_SHOW_CUSTOM
        getSupportActionBar(activity).setCustomView(R.layout.view_home_toolbar)
    }

    /**
     * ADR 0008 §4 — revocation re-entry, covering both degraded states of the
     * required grant: DENIED and (since the review) API 34 PARTIAL, which the
     * wizard treats as success-adjacent but the hub must make visible. Each
     * condition routes once per recurrence, dismissible; a resolved condition
     * clears the dismissal so a later recurrence re-nudges.
     */
    private fun routeToSetupHubIfNeeded() {
        val context = context ?: return
        val prefs = OnboardingPrefs.getInstance(context)
        if (!prefs.isOnboardingComplete()) {
            // The wizard owns first run; MainActivity gates it.
            return
        }
        val signature = when (PermissionHelper.getMediaAccess(context)) {
            MediaAccess.DENIED -> OnboardingPrefs.NUDGE_MEDIA_DENIED
            MediaAccess.PARTIAL -> OnboardingPrefs.NUDGE_MEDIA_PARTIAL
            else -> null
        }
        if (signature == null) {
            prefs.clearHubNudge()
            return
        }
        if (!prefs.isHubNudgeDismissedFor(signature)) {
            SetupHubActivity.startNudged(requireContext(), signature)
        }
    }

    private fun createOptionsMenuSearchView(activity: Activity) {
        //val searchItem = menu.findItem(R.id.action_search)

        //val searchView = searchItem.actionView as SearchView
        val searchView = view!!.findViewById<SearchView>(R.id.search_view)//searchItem.actionView as SearchView
        searchView.setIconifiedByDefault(false)
        searchView.maxWidth = resources.displayMetrics.widthPixels
        searchView.findViewById<View>(androidx.appcompat.R.id.search_plate)?.setBackgroundColor(Color.TRANSPARENT)
        searchView.isFocusableInTouchMode = false
        searchView.isClickable = false

        val searchManager = activity.getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchView.setSearchableInfo(searchManager.getSearchableInfo(activity.componentName))

        searchView.setOnSearchClickListener {
            Toast.makeText(context, "WIP!", Toast.LENGTH_SHORT).show()
        }

        searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                //searchListAdapter.filter.filter(query)
                return false
            }

            override fun onQueryTextChange(newText: String?): Boolean {
                //searchListAdapter.filter.filter(newText)
                return false
            }
        })

        view!!.findViewById<View>(R.id.intercept_view).setOnClickListener {
            // ADR 0008: the old gate read `permissionFlow.isFinished()` (plus an
            // isInitialized guard for init ordering); a pref read needs neither.
            val context = context ?: return@setOnClickListener
            if (OnboardingPrefs.getInstance(context).isOnboardingComplete()) {
                Navigation.findNavController(view!!).navigateSafely(R.id.MainFragment,
                        R.id.action_navigate_to_full_text_search,
                        Bundle())
            }
        }
    }

    private fun initQuickAccessList(context: Context) {
        quickAccessAdapter?.clickListener = object : QuickAccessAdapter.ItemClickListener {
            override fun onItemClick(screenshotModel: ScreenshotModel, holder: ScreenshotItemHolder) {
                DetailPageActivity.showDetailPage(context, screenshotModel, holder.image)
            }

            override fun onMoreClick(holder: RecyclerView.ViewHolder) {
                Navigation.findNavController(holder.itemView).navigate(
                        R.id.action_navigate_to_collection,
                        Bundle()
                )
            }
        }

        quickAccessBinding?.listView?.apply {
            layoutManager = LinearLayoutManager(context, LinearLayoutManager.HORIZONTAL, false)
            adapter = quickAccessAdapter

            val spaceOuter = resources.getDimensionPixelSize(R.dimen.home_horizontal_padding)
            val spaceInner = resources.getDimensionPixelSize(R.dimen.quick_access_item_space)
            addItemDecoration(object : RecyclerView.ItemDecoration() {
                override fun getItemOffsets(
                        outRect: Rect,
                        view: View,
                        parent: RecyclerView,
                        state: RecyclerView.State
                ) {
                    val position = parent.getChildAdapterPosition(view)
                    if (position == 0) {
                        outRect.left = spaceOuter
                    }
                    if (position == quickAccessAdapter?.let { it.itemCount - 1 } ?: 0) {
                        outRect.right = spaceOuter
                    } else {
                        outRect.right = spaceInner
                    }
                }
            })
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getScreenshots().collect { screenshots ->
                    val finalList = screenshots.sortedByDescending { it.lastModified }
                            .subList(0, Math.min(screenshots.size, QUICK_ACCESS_ITEM_COUNT + 1))
                    updateQuickAccessListView(finalList)
                }
            }
        }
    }

    private fun initCollectionList(context: Context) {
        val manager = GridLayoutManager(context, COLLECTION_COLUMN_COUNT,
                RecyclerView.VERTICAL, false)
        manager.spanSizeLookup = MainAdapter.SpanSizeLookup(COLLECTION_COLUMN_COUNT)
        binding.mainList.layoutManager = manager

        quickAccessContainer?.let {
            mainAdapter?.quickAccessContainer = it
        }
        binding.mainList.adapter = mainAdapter

        val spaceOuter = resources.getDimensionPixelSize(R.dimen.home_horizontal_padding)
        binding.mainList.addItemDecoration(MainAdapter.ItemDecoration(context,
                COLLECTION_COLUMN_COUNT,
                spaceOuter,
                0))

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getCollections().collect { collections ->
                    collections.asSequence().filter {
                        !SuggestCollectionHelper.isSuggestCollection(it)
                    }.sortedBy {
                        it.createdDate
                    }.toList().let {
                        updateCollectionListView(it)
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getCollectionCovers().collect { coverMap ->
                    mainAdapter?.coverList = coverMap
                    mainAdapter?.notifyDataSetChanged()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getScreenshots().collect { screenshots ->
                    mainAdapter?.updateUnsortedCount(screenshots.filter {
                        it.collectionId == CollectionModel.UNCATEGORIZED
                    }.size)
                }
            }
        }
    }

    private fun updateQuickAccessListView(screenshots: List<ScreenshotModel>) {
        quickAccessBinding?.emptyViewGroup?.visibility = if (screenshots.isEmpty()) {
            View.VISIBLE
        } else {
            View.INVISIBLE
        }

        quickAccessAdapter?.list = screenshots
        quickAccessAdapter?.notifyDataSetChanged()
    }

    private fun updateCollectionListView(collections: List<CollectionModel>) {
        mainAdapter?.collectionList = collections
        mainAdapter?.notifyDataSetChanged()
    }

    private suspend fun checkNewScreenshots(pruneUnreadable: Boolean): List<ScreenshotModel> {
        val context = context ?: return emptyList()
        return withContext (Dispatchers.IO + NonCancellable) {
            ExternalScreenshotSync(context.applicationContext,
                    ScryerApplication.getScreenshotRepository(),
                    pruneUnreadable = pruneUnreadable).sync()
        }
    }

    private fun showEnableServiceDialog() {
        val context = context?: return
        val dialog = BottomDialogFactory.create(context, R.layout.dialog_bottom)

        dialog.findViewById<TextView>(R.id.title)?.text = getString(R.string.sheet_enable_title_enable,
                getString(R.string.app_full_name))
        dialog.findViewById<TextView>(R.id.subtitle)?.text = getString(R.string.sheet_enable_content_enable)

        dialog.findViewById<TextView>(R.id.positive_button)?.apply {
            setText(R.string.sheet_enable_action_enable)
            setOnClickListener {
                ScryerApplication.getSettingsRepository().serviceEnabled = true
                val intent = Intent(activity, ScryerService::class.java)
                intent.action = ScryerService.ACTION_ENABLE_SERVICE
                activity?.startService(intent)
                dialog.dismiss()
            }
        }

        val checkbox = dialog.findViewById<AppCompatCheckBox>(R.id.dont_ask_again_checkbox)
        dialog.findViewById<TextView>(R.id.negative_button)?.apply {
            setText(R.string.sheet_action_no)
            setOnClickListener {
                dialog.cancel()
            }
        }

        val isShown = dialogQueue.tryShow(dialog, DialogInterface.OnDismissListener {
            if (checkbox?.isChecked == true) {
                setDoNotShowDialogAgain(PREF_SHOW_ENABLE_SERVICE_DIALOG)
            }
        })
        if (isShown) {
            pref?.setShouldPromptEnableService(false)
        }
    }

    private fun log(tag: String, msg: String) {
        android.util.Log.d(tag, msg)
    }

    private fun setDoNotShowDialogAgain(prefKey: String) {
        context?.let {
            PreferenceManager.getDefaultSharedPreferences(it).edit()
                    .putBoolean(prefKey, false)
                    .apply()
        }
    }

    private fun isDialogAllowed(prefKey: String): Boolean {
        return context?.let {
            PreferenceManager.getDefaultSharedPreferences(it)
                    .getBoolean(prefKey, true)
        }?: false
    }

    private fun shouldPromptEnableService(): Boolean {
        return pref?.shouldPromptEnableService() ?: false
    }

    private fun promptPromotionIfNeeded() {
        val context = context ?: return
        val shareReason = PromoteShareHelper.getShareReason(context)
        if (shareReason >= 0) {
            promptShareDialog(context, shareReason)

        } else if (PromoteRatingHelper.shouldPromote(context)) {
            promptRatingDialog(context)
        }
    }

    private fun promptRatingDialog(context: Context) {
        val dialog = PromoteRatingHelper.getRatingDialog(context, {}, {})
 
        if (dialogQueue.tryShow(dialog, null)) {
            PromoteRatingHelper.onRatingPromoted(context)
        }
    }
 
    private fun promptShareDialog(context: Context, reason: Int) {
        PromoteShareHelper.getShareDialog(context, reason, {})?.let {
            if (dialogQueue.tryShow(it, null)) {
                PromoteShareHelper.onSharingPromoted(context)
            }
        }
    }

    private class DialogQueue {
        private var current: AppCompatDialog? = null
        private val queue = LinkedList<AppCompatDialog>()
        private val listeners = HashMap<AppCompatDialog, DialogInterface.OnDismissListener?>()

        fun show(dialog: AppCompatDialog, dismissListener: DialogInterface.OnDismissListener?) {
            listeners[dialog] = dismissListener
            queue.offer(dialog)
            schedule()
        }

        fun tryShow(dialog: AppCompatDialog, dismissListener: DialogInterface.OnDismissListener?): Boolean {
            current?: run {
                show(dialog, dismissListener)
                return true
            }
            return false
        }

        private fun schedule() {
            current?: run {
                current = queue.poll()?.let { dialog ->
                    dialog.setOnDismissListener {
                        val listener = listeners[dialog]
                        listener?.let { targetInterface ->
                            targetInterface.onDismiss(dialog)
                            listeners.remove(dialog)
                        }
                        current = null
                        schedule()
                    }
                    dialog.show()

                    dialog
                }
            }
        }
    }
}
