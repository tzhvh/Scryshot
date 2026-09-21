package io.github.tzhvh.scryernext.search

import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.github.tzhvh.scryernext.R
import java.util.Calendar
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import io.github.tzhvh.scryernext.databinding.FragmentFullTextSearchBinding
import io.github.tzhvh.scryernext.ScryerApplication
import io.github.tzhvh.scryernext.collectionview.*
import io.github.tzhvh.scryernext.detailpage.DetailPageActivity
import io.github.tzhvh.scryernext.extension.getNavController
import io.github.tzhvh.scryernext.getSupportActionBar
import io.github.tzhvh.scryernext.persistence.CollectionModel
import io.github.tzhvh.scryernext.persistence.LoadingViewModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel
import io.github.tzhvh.scryernext.persistence.SuggestCollectionHelper
import io.github.tzhvh.scryernext.repository.SearchOutcome
import io.github.tzhvh.scryernext.ingestion.Progress
import io.github.tzhvh.scryernext.setSupportActionBar
import io.github.tzhvh.scryernext.ui.InnerSpaceDecoration
import io.github.tzhvh.scryernext.util.hideKeyboard
import io.github.tzhvh.scryernext.util.showKeyboard
import io.github.tzhvh.scryernext.viewmodel.ScreenshotViewModel

class FullTextSearchFragment : androidx.fragment.app.Fragment() {

    companion object {
        private const val SPAN_COUNT = 3
        private const val STATE_ADVANCED_OPEN = "advancedOpen"
    }

    private var _binding: FragmentFullTextSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var screenshotAdapter: SearchAdapter
    private var searchJob: Job? = null

    /** Phase 2.1 step 8: list mode swaps grid thumbnails for snippet + highlight cards. */
    private var isListMode = false
    private var latestContentByHash: Map<String, String> = emptyMap()
    private var latestQuery: String = ""
    private var collectionList = emptyList<CollectionModel>()
    private lateinit var viewModel: ScreenshotViewModel

    /**
     * Phase 2.1 step 1 (2.1-D6): the active sort policy, owned here and applied by the repository's
     * RankStage — the fragment renders the returned list as-is. Blended is the shipped default.
     */
    private var rankPolicy: RankPolicy = RankPolicy.Blended()

    /** Phase 2.1 (2.1-D13): the precision dial — per-request, default Balanced (the B4 ship pick). */
    private var precisionMode: PrecisionMode = PrecisionMode.Default

    /**
     * Phase 2.1 step 5: the filter-chip state (collection multi-select; date range joins in step 6,
     * gated on the backfill). Composed into the push-down expression on every search.
     */
    private var searchFilters = SearchFilters()

    /** The Advanced disclosure's per-session collapsed state (survives rotation, not process death). */
    private var isAdvancedOpen = false

    /**
     * Phase 2.1 step 9 — the persisted last-N searches (prefs-backed), shown on the empty-query
     * state. Submissions are recorded on focus loss / IME done, not per debounce keystroke.
     */
    private lateinit var recentSearches: RecentSearches

    private var actionModeMenu: Menu? = null
    private var isIndexing: Boolean = false
    private var enterTimeMillis: Long = 0

    private val selectActionModeCallback: ActionMode.Callback = object : ActionMode.Callback {
        override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean {
            val activity = activity ?: run {
                mode.finish()
                return false
            }

            when (item.itemId) {
                R.id.action_move -> {
                    val dialog = SortingPanelDialog(activity, selector.selected.toList())
                    dialog.setOnDismissListener {
                        mode.finish()
                    }
                    dialog.show()
                }

                R.id.action_delete -> {
                    showDeleteScreenshotDialog(activity, selector.selected.toList(),
                            object : OnDeleteScreenshotListener {
                                override fun onDeleteScreenshot() {
                                    mode.finish()
                                }
                            })
                }

                R.id.action_share -> {
                    showShareScreenshotDialog(activity, selector.selected.toList())
                }
            }

            return true
        }

        override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
            val activity = activity ?: return false
            activity.menuInflater.inflate(R.menu.menu_collection_view_select_action_mode, menu)
            actionModeMenu = menu

            (0 until menu.size()).map {
                menu.getItem(it)
            }.forEach { item ->
                item.icon = item.icon?.let { icon ->
                    DrawableCompat.wrap(icon).mutate().apply {
                        DrawableCompat.setTint(this, Color.WHITE)
                    }
                }
                if (selector.selected.isEmpty()) {
                    item.isVisible = false
                }
            }

            activity.window?.let {
                it.statusBarColor = ContextCompat.getColor(activity, R.color.primaryTeal)
            }

            return true
        }

        override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean {
            return true
        }

        override fun onDestroyActionMode(mode: ActionMode) {
            screenshotAdapter.exitSelectionMode()
            val activity = activity ?: return

            activity.findViewById<View>(androidx.appcompat.R.id.action_mode_bar)?.visibility = View.INVISIBLE
            activity.window?.let {
                it.statusBarColor = ContextCompat.getColor(activity, R.color.statusBarColor)
            }
        }
    }

    private var selector = object : ListSelector<ScreenshotModel>() {
        private var actionMode: ActionMode? = null

        override fun onSelectChanged() {
            if (selected.isEmpty()) {
                screenshotAdapter.exitSelectionMode()
                return
            }

            actionMode?.title = if (selected.size == screenshotAdapter.itemCount) {
                getString(R.string.collection_header_select_all)
            } else {
                "${selected.size}"
            }

            _binding?.selectAllCheckbox?.isChecked = screenshotAdapter.screenshotList.all {
                isSelected(it)
            }
            _binding?.selectAllCheckbox?.invalidate()

            actionModeMenu?.let { menu ->
                (0 until menu.size()).map {
                    menu.getItem(it)
                }.forEach { item ->
                    if (selected.isNotEmpty()) {
                        item.isVisible = true
                    }
                }
            }
        }

        override fun onEnterSelectMode() {
            val activity = (activity as? AppCompatActivity) ?: return
            actionMode = activity.startSupportActionMode(selectActionModeCallback)
            _binding?.selectAllCheckbox?.visibility = View.VISIBLE
            actionMode?.title = getString(R.string.collection_header_select_none)
            _binding?.selectAllCheckbox?.isChecked = false
        }

        override fun onExitSelectMode() {
            actionMode?.finish()
            _binding?.selectAllCheckbox?.visibility = View.GONE
        }
    }

    override fun onCreateView(
            inflater: LayoutInflater,
            container: ViewGroup?,
            savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFullTextSearchBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        recentSearches = RecentSearches(RecentSearchesPrefs(requireContext()))
        isAdvancedOpen = savedInstanceState?.getBoolean(STATE_ADVANCED_OPEN, false) ?: false
        // The empty-query state is the entry state too (UX-1): show recent searches immediately,
        // not only after the user types and deletes text (review P1 fix).
        if (binding.searchEditText.text?.isEmpty() == true) {
            binding.recentSearchesView.visibility = View.VISIBLE
            binding.screenshotListView.visibility = View.GONE
            rebuildRecentSearches()
        }
        binding.advancedToggle.setOnClickListener {
            isAdvancedOpen = !isAdvancedOpen
            updateAdvancedRegion()
        }
        binding.dateFromButton.setOnClickListener { showDatePicker(isFrom = true) }
        binding.dateToButton.setOnClickListener { showDatePicker(isFrom = false) }
        binding.dateClearButton.setOnClickListener {
            searchFilters = searchFilters.withDateRange(null, null)
            onDateRangeChanged()
        }
        binding.precisionExact.setOnClickListener { selectPrecision(PrecisionMode.Exact) }
        binding.precisionBalanced.setOnClickListener { selectPrecision(PrecisionMode.Balanced) }
        binding.precisionFuzzy.setOnClickListener { selectPrecision(PrecisionMode.Fuzzy) }
        binding.emptyRelaxButton.setOnClickListener {
            // The zero-results one-tap relax: drop every active filter and re-run.
            searchFilters = SearchFilters()
            rebuildCollectionChips()
            onDateRangeChanged()
        }
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                screenshotAdapter.showLoadingView(LoadingViewModel(getText(R.string.search_transition_searching)))
                startSearchJob(s.toString())

                binding.clear.visibility = if (binding.searchEditText.text?.isNotEmpty() == true) {
                    View.VISIBLE
                } else {
                    View.INVISIBLE
                }
            }

            override fun afterTextChanged(s: Editable?) {}
        })
        binding.searchEditText.onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
            // Avoid showing keyboard again when returning to the previous page by back key.
            if (hasFocus) {
                showKeyboard(binding.searchEditText)
            } else {
                commitRecentSearch()
                hideKeyboard(binding.searchEditText)
            }
        }
        binding.searchEditText.setOnEditorActionListener { _, actionId, _ ->
            // The IME done action is an explicit submission — record it.
            if (actionId == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) {
                commitRecentSearch()
            }
            false
        }
        binding.searchEditText.requestFocus()

        binding.clear.setOnClickListener { binding.searchEditText.setText("") }

        binding.sortToggle.setOnClickListener { cycleRankPolicy() }
        binding.viewModeToggle.setOnClickListener {
            isListMode = !isListMode
            binding.viewModeToggle.text =
                getString(if (isListMode) R.string.search_mode_grid else R.string.search_mode_list)
            applyViewModeToAdapter()
            screenshotAdapter.notifyDataSetChanged()
        }

        binding.selectAllCheckbox.setOnClickListener { _ ->
            val isChecked = binding.selectAllCheckbox.isChecked
            binding.selectAllCheckbox.invalidate()
            screenshotAdapter.screenshotList.forEach {
                if (isChecked != selector.isSelected(it)) {
                    selector.toggleSelection(it)
                }
            }
            screenshotAdapter.notifyDataSetChanged()
        }

        binding.screenshotListView.setOnTouchListener { _, _ ->
            hideKeyboard(binding.searchEditText)
            false
        }
    }

    /**
     * One search = one debounced job: query the repository under the active [rankPolicy] and render
     * the list as returned (Phase 2.1 step 1 — the fragment no longer re-sorts; the RankStage in
     * [io.github.tzhvh.scryernext.repository.ZvecScreenshotRepository] owns the order). Shared by
     * the text watcher and the sort toggle (a policy change re-runs the search).
     */
    private fun startSearchJob(query: String) {
        searchJob?.cancel()
        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val filter = searchFilters.takeIf { !it.isEmpty }?.toFilterExpression()
                viewModel.searchScreenshots(query, rankPolicy, filter, precisionMode).collect { outcome ->
                    // 2.1-D10: a parse error is a recoverable state — the notice shows, the query
                    // stays in the field, and the next submission replaces the state.
                    val isError = outcome is SearchOutcome.QueryError
                    binding.errorView.visibility = if (isError) View.VISIBLE else View.GONE

                    val screenshots = (outcome as? SearchOutcome.Results)?.rows ?: emptyList()

                    // The empty-query state: recent searches replace the grid (UX-1).
                    val isEmptyQuery = query.isEmpty()
                    binding.recentSearchesView.visibility = if (isEmptyQuery && !isError) View.VISIBLE else View.GONE
                    binding.screenshotListView.visibility = if (isEmptyQuery) View.GONE else View.VISIBLE
                    if (isEmptyQuery) rebuildRecentSearches()

                    binding.subtitleLayout.visibility = if (screenshots.isEmpty()) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }

                    // The zero-results machine (2.1 step 9): no-filters vs filters-active copy,
                    // with a one-tap relax in the filters case. Parse errors show errorView
                    // instead; an indexing run keeps the empty view hidden (the overlay talks).
                    val filtersActive = !searchFilters.isEmpty
                    binding.emptyView.visibility = if (screenshots.isEmpty() && query.isNotEmpty() && !isError && !isIndexing) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    if (screenshots.isEmpty() && query.isNotEmpty() && !isError && !isIndexing) {
                        binding.emptyTitle.text = getString(R.string.search_zero_title, query)
                        if (filtersActive) {
                            binding.emptyContent.text = getString(R.string.search_zero_content_filters)
                            binding.emptyRelaxButton.visibility = View.VISIBLE
                        } else {
                            binding.emptyContent.text = getString(R.string.search_zero_content_no_filter)
                            binding.emptyRelaxButton.visibility = View.GONE
                        }
                    }

                    if (query.isEmpty() || !isIndexing) {
                        screenshotAdapter.showLoadingView(null)
                    }

                    binding.subtitleTextView.text = getString(R.string.search_separator_results, screenshots.size)

                    screenshotAdapter.screenshotList = screenshots
                    latestContentByHash = (outcome as? SearchOutcome.Results)?.contentByHash ?: emptyMap()
                    latestQuery = query
                    applyViewModeToAdapter()
                    screenshotAdapter.notifyDataSetChanged()
                }
            }
        }
    }

    /** Blended → Relevance → Recency → Blended, then re-runs the current query under the new policy. */
    private fun cycleRankPolicy() {
        rankPolicy = when (rankPolicy) {
            is RankPolicy.Blended -> RankPolicy.Relevance
            RankPolicy.Relevance -> RankPolicy.Recency
            RankPolicy.Recency -> RankPolicy.Blended()
        }
        updateSortToggle()
        startSearchJob(binding.searchEditText.text.toString())
    }

    private fun updateSortToggle() {
        val label = when (rankPolicy) {
            is RankPolicy.Blended -> R.string.search_sort_blended
            RankPolicy.Relevance -> R.string.search_sort_relevance
            RankPolicy.Recency -> R.string.search_sort_recent
        }
        binding.sortToggle.text = getString(R.string.search_sort_label, getString(label))
    }

    /**
     * Phase 2.1 (2.1-D13) — the precision dial: three positions with a one-line explainer that
     * previews the effect, never the mechanism. Changing position re-runs the search instantly
     * (per-request dial — no schema change, no re-ingest).
     */
    private fun updatePrecisionDial() {
        val positions = listOf(
            Triple(binding.precisionExact, PrecisionMode.Exact, R.string.search_precision_exact_hint),
            Triple(binding.precisionBalanced, PrecisionMode.Balanced, R.string.search_precision_balanced_hint),
            Triple(binding.precisionFuzzy, PrecisionMode.Fuzzy, R.string.search_precision_fuzzy_hint),
        )
        for ((view, mode, hint) in positions) {
            val selected = mode == precisionMode
            view.isSelected = selected
            if (selected) binding.precisionHint.setText(hint)
        }
    }

    private fun selectPrecision(mode: PrecisionMode) {
        if (mode == precisionMode) return
        precisionMode = mode
        updatePrecisionDial()
        startSearchJob(binding.searchEditText.text.toString())
    }

    /** Pushes the current view mode + snippet payload into the adapter (step 8). */
    private fun applyViewModeToAdapter() {
        screenshotAdapter.listMode = isListMode
        screenshotAdapter.contentByHash = latestContentByHash
        screenshotAdapter.snippetTerms =
            io.github.tzhvh.scryernext.repository.parseFtsQueryParts(latestQuery).positives
    }

    /**
     * Phase 2.1 step 5 — the Advanced disclosure (UX-2). Always rendered since the precision
     * dial joined the panel (2.1-D13); the chip row inside still gates on collections existing.
     */
    private fun updateAdvancedRegion() {
        // Since the precision dial joined the panel (2.1-D13) the Advanced control always has
        // something to offer; the chip row inside still renders only when collections exist.
        binding.advancedRegion.visibility = View.VISIBLE
        binding.advancedPanel.visibility = if (isAdvancedOpen) View.VISIBLE else View.GONE
        binding.collectionChipRow.visibility = if (collectionList.isEmpty()) View.GONE else View.VISIBLE
        val active = if (searchFilters.isEmpty) 0 else
            searchFilters.collectionIds.size + (if (searchFilters.fromMillis != null || searchFilters.toMillis != null) 1 else 0)
        binding.advancedToggle.text = if (active > 0) {
            getString(R.string.search_advanced_label_count, active)
        } else {
            getString(R.string.search_advanced_label)
        }
        updateDateRangeControls()
        updatePrecisionDial()
    }

    /**
     * Phase 2.1 step 6 — the date-range push-down affordance. The row renders only after the
     * one-shot `last_modified` backfill has completed (the R8 gate: a filter silently hides null
     * rows, so a visible-but-empty date control is dead UI). "From" anchors the picked day's
     * midnight; "To" is inclusive through the day's last millisecond.
     */
    private fun updateDateRangeControls() {
        val backfillDone = ScryerApplication.isLastModifiedBackfillDone()
        binding.dateRangeRow.visibility = if (backfillDone) View.VISIBLE else View.GONE
        if (!backfillDone) return
        binding.dateFromButton.text = searchFilters.fromMillis
            ?.let { formatDate(it) } ?: getString(R.string.search_date_any)
        binding.dateToButton.text = searchFilters.toMillis
            ?.let { formatDate(it) } ?: getString(R.string.search_date_any)
        binding.dateClearButton.visibility =
            if (searchFilters.fromMillis != null || searchFilters.toMillis != null) {
                View.VISIBLE
            } else {
                View.GONE
            }
    }

    private fun showDatePicker(isFrom: Boolean) {
        val calendar = Calendar.getInstance()
        val current = if (isFrom) searchFilters.fromMillis else searchFilters.toMillis
        if (current != null) calendar.timeInMillis = current
        context?.let {
            DatePickerDialog(
                it,
                { _, year, month, day ->
                    calendar.set(year, month, day)
                    if (isFrom) {
                        calendar.set(Calendar.HOUR_OF_DAY, 0)
                        calendar.set(Calendar.MINUTE, 0)
                        calendar.set(Calendar.SECOND, 0)
                        calendar.set(Calendar.MILLISECOND, 0)
                        searchFilters = searchFilters.withDateRange(calendar.timeInMillis, searchFilters.toMillis)
                    } else {
                        calendar.set(Calendar.HOUR_OF_DAY, 23)
                        calendar.set(Calendar.MINUTE, 59)
                        calendar.set(Calendar.SECOND, 59)
                        calendar.set(Calendar.MILLISECOND, 999)
                        searchFilters = searchFilters.withDateRange(searchFilters.fromMillis, calendar.timeInMillis)
                    }
                    onDateRangeChanged()
                },
                calendar.get(Calendar.YEAR),
                calendar.get(Calendar.MONTH),
                calendar.get(Calendar.DAY_OF_MONTH),
            ).show()
        }
    }

    private fun onDateRangeChanged() {
        updateAdvancedRegion()
        updateDateRangeControls()
        startSearchJob(binding.searchEditText.text.toString())
    }

    /**
     * Phase 2.1 step 9 — record the current query as a recent search. Fired on focus loss and
     * IME done (explicit submissions), never per debounced keystroke — the prefix spam ("re",
     * "rece"…) of record-on-search would make the list useless.
     */
    private fun commitRecentSearch() {
        val query = binding.searchEditText.text?.toString() ?: return
        if (query.isBlank()) return
        recentSearches.record(query)
    }

    /** Rebuilds the empty-query view's recent-search rows + Clear affordance. */
    private fun rebuildRecentSearches() {
        val list = binding.recentSearchesList
        list.removeAllViews()
        val inflator = LayoutInflater.from(context ?: return)
        val entries = recentSearches.entries()
        if (entries.isEmpty()) return

        val title = inflator.inflate(R.layout.view_recent_search, list, false) as TextView
        title.setText(R.string.search_recent_title)
        title.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey60))
        list.addView(title)

        for (entry in entries) {
            val row = inflator.inflate(R.layout.view_recent_search, list, false) as TextView
            row.text = entry
            row.setTextColor(ContextCompat.getColor(requireContext(), R.color.grey90))
            row.setOnClickListener {
                binding.searchEditText.setText(entry)
                binding.searchEditText.setSelection(entry.length)
                startSearchJob(entry)
            }
            list.addView(row)
        }

        val clear = inflator.inflate(R.layout.view_recent_search, list, false) as TextView
        clear.setText(R.string.search_recent_clear)
        clear.setTextColor(ContextCompat.getColor(requireContext(), R.color.primaryTeal))
        clear.setOnClickListener {
            recentSearches.clear()
            rebuildRecentSearches()
        }
        list.addView(clear)
    }

    private fun formatDate(millis: Long): String =
        java.text.DateFormat.getDateInstance(java.text.DateFormat.SHORT).format(java.util.Date(millis))

    /** Rebuilds the collection chip row from [collectionList]; preserves active selections. */
    private fun rebuildCollectionChips() {
        val row = binding.collectionChipRow
        row.removeAllViews()
        val context = context ?: return
        val inflator = LayoutInflater.from(context)
        for (collection in collectionList) {
            val chip = inflator.inflate(R.layout.view_filter_chip, row, false) as TextView
            chip.text = collection.name
            chip.isSelected = searchFilters.collectionIds.contains(collection.id)
            chip.setOnClickListener {
                val selected = !chip.isSelected
                chip.isSelected = selected
                searchFilters = searchFilters.withCollection(collection.id, selected)
                updateAdvancedRegion()
                startSearchJob(binding.searchEditText.text.toString())
            }
            row.addView(chip)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_ADVANCED_OPEN, isAdvancedOpen)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        val activity = activity ?: return

        screenshotAdapter = SearchAdapter(context, selector) { item, view, position ->
            val context = context ?: return@SearchAdapter
            DetailPageActivity.showDetailPage(context, item, view, searchKeyword = binding.searchEditText.text.toString())
        }
        enterTimeMillis = System.currentTimeMillis()

        // Issue 17: source-swapped from ContentScanner.getProgressState() LiveData onto the
        // app-scope StateFlow<Progress>. The "results shifting" passive status is preserved
        // (onIndexProgress/onIndexEnd); the Unavailable arm is gone (ML Kit weights are bundled,
        // so the condition cannot arise — taxonomy classifies it Transient by design).
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                ScryerApplication.getIngestionProgressStore().progress.collect { progress ->
                    val indexing = progress as? Progress.Indexing
                    if (indexing != null && indexing.current != indexing.total) {
                        onIndexProgress(indexing.current, indexing.total)
                    } else {
                        onIndexEnd()
                    }
                }
            }
        }

        setHasOptionsMenu(true)
        setupActionBar()
        updateSortToggle()
        initScreenshotList(activity)
    }

    private fun onIndexProgress(current: Int, total: Int) {
        binding.searchEditText.text?.let {
            if (it.isNotEmpty()) {
                screenshotAdapter.showLoadingView(LoadingViewModel(
                        getString(R.string.search_transition_progress, total - current),
                        getString(R.string.search_transition_content_searchable)))

                binding.emptyView.visibility = View.GONE
            }
        }
        isIndexing = true
    }

    private fun onIndexEnd() {
        screenshotAdapter.showLoadingView(null)
        // Don't stack the zero-results view on a live parse-error state (review P3 fix).
        if (screenshotAdapter.screenshotList.isEmpty()
                && binding.searchEditText.text?.isNotEmpty() == true
                && binding.errorView.visibility != View.VISIBLE) {
            binding.emptyView.visibility = View.VISIBLE
        }
        isIndexing = false
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            android.R.id.home -> {
                hideKeyboard(binding.searchEditText)
                getNavController()?.navigateUp()
            }

            else -> return super.onOptionsItemSelected(item)
        }
        return super.onOptionsItemSelected(item)
    }

    private fun setupActionBar() {
        view?.let {
            setSupportActionBar(activity, it.findViewById(R.id.toolbar))
        }
        getSupportActionBar(activity).apply {
            setDisplayHomeAsUpEnabled(true)
            setDisplayShowTitleEnabled(false)
        }
    }

    private fun initScreenshotList(context: Context) {
        val manager = GridLayoutManager(context, SPAN_COUNT,
                RecyclerView.VERTICAL, false)
        manager.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int): Int {
                return when (screenshotAdapter.getItemViewType(position)) {
                    SearchAdapter.VIEW_TYPE_ITEM -> 1
                    SearchAdapter.VIEW_TYPE_LOADING -> SPAN_COUNT
                    SearchAdapter.VIEW_TYPE_LIST_ITEM -> SPAN_COUNT
                    else -> -1
                }
            }
        }
        binding.screenshotListView.itemAnimator = null
        binding.screenshotListView.layoutManager = manager
        binding.screenshotListView.adapter = screenshotAdapter

        val itemSpace = context.resources.getDimensionPixelSize(R.dimen.collection_item_space)
        binding.screenshotListView.addItemDecoration(InnerSpaceDecoration(itemSpace) {
            SPAN_COUNT
        })

        viewModel = ScreenshotViewModel.get(this)

        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.getCollections().collect { collections ->
                    collectionList = collections.asSequence().filter {
                        !SuggestCollectionHelper.isSuggestCollection(it)
                    }.toList()
                    rebuildCollectionChips()
                    updateAdvancedRegion()
                }
            }
        }

        searchJob = viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                val filter = searchFilters.takeIf { !it.isEmpty }?.toFilterExpression()
                viewModel.searchScreenshots("", rankPolicy, filter, precisionMode).collect { outcome ->
                    screenshotAdapter.screenshotList =
                        (outcome as? SearchOutcome.Results)?.rows ?: emptyList()
                    screenshotAdapter.notifyDataSetChanged()
                }
            }
        }
    }
}
