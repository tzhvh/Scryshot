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
    private var collectionList = emptyList<CollectionModel>()
    private lateinit var viewModel: ScreenshotViewModel

    /**
     * Phase 2.1 step 1 (2.1-D6): the active sort policy, owned here and applied by the repository's
     * RankStage — the fragment renders the returned list as-is. Blended is the shipped default.
     */
    private var rankPolicy: RankPolicy = RankPolicy.Blended()

    /**
     * Phase 2.1 step 5: the filter-chip state (collection multi-select; date range joins in step 6,
     * gated on the backfill). Composed into the push-down expression on every search.
     */
    private var searchFilters = SearchFilters()

    /** The Advanced disclosure's per-session collapsed state (survives rotation, not process death). */
    private var isAdvancedOpen = false

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
        isAdvancedOpen = savedInstanceState?.getBoolean(STATE_ADVANCED_OPEN, false) ?: false
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
                hideKeyboard(binding.searchEditText)
            }
        }
        binding.searchEditText.requestFocus()

        binding.clear.setOnClickListener { binding.searchEditText.setText("") }

        binding.sortToggle.setOnClickListener { cycleRankPolicy() }

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
                viewModel.searchScreenshots(query, rankPolicy, filter).collect { outcome ->
                    // 2.1-D10: a parse error is a recoverable state — the notice shows, the query
                    // stays in the field, and the next submission replaces the state.
                    val isError = outcome is SearchOutcome.QueryError
                    binding.errorView.visibility = if (isError) View.VISIBLE else View.GONE

                    val screenshots = (outcome as? SearchOutcome.Results)?.rows ?: emptyList()
                    binding.subtitleLayout.visibility = if (screenshots.isEmpty()) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    binding.emptyView.visibility = if (screenshots.isEmpty() && query.isNotEmpty() && !isError && !isIndexing) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                    if (query.isEmpty() || !isIndexing) {
                        screenshotAdapter.showLoadingView(null)
                    }

                    binding.subtitleTextView.text = getString(R.string.search_separator_results, screenshots.size)

                    screenshotAdapter.screenshotList = screenshots
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
     * Phase 2.1 step 5 — the Advanced disclosure (UX-2). The control renders only when it has
     * something to offer (collection chips exist); an empty corpus must not show a dead toggle.
     */
    private fun updateAdvancedRegion() {
        binding.advancedRegion.visibility = if (collectionList.isEmpty()) View.GONE else View.VISIBLE
        binding.advancedPanel.visibility = if (isAdvancedOpen && collectionList.isNotEmpty()) {
            View.VISIBLE
        } else {
            View.GONE
        }
        val active = if (searchFilters.isEmpty) 0 else
            searchFilters.collectionIds.size + (if (searchFilters.fromMillis != null || searchFilters.toMillis != null) 1 else 0)
        binding.advancedToggle.text = if (active > 0) {
            getString(R.string.search_advanced_label_count, active)
        } else {
            getString(R.string.search_advanced_label)
        }
        updateDateRangeControls()
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
        if (screenshotAdapter.screenshotList.isEmpty()
                && binding.searchEditText.text?.isNotEmpty() == true) {
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
                viewModel.searchScreenshots("", rankPolicy, filter).collect { outcome ->
                    screenshotAdapter.screenshotList =
                        (outcome as? SearchOutcome.Results)?.rows ?: emptyList()
                    screenshotAdapter.notifyDataSetChanged()
                }
            }
        }
    }
}
