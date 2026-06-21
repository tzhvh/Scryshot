package org.mozilla.scryer.search

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
import android.view.*
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.view.ActionMode
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.mozilla.scryer.R
import org.mozilla.scryer.databinding.FragmentFullTextSearchBinding
import org.mozilla.scryer.ScryerApplication
import org.mozilla.scryer.collectionview.*
import org.mozilla.scryer.detailpage.DetailPageActivity
import org.mozilla.scryer.extension.getNavController
import org.mozilla.scryer.getSupportActionBar
import org.mozilla.scryer.persistence.CollectionModel
import org.mozilla.scryer.persistence.LoadingViewModel
import org.mozilla.scryer.persistence.ScreenshotModel
import org.mozilla.scryer.persistence.SuggestCollectionHelper
import org.mozilla.scryer.scan.ContentScanner
import org.mozilla.scryer.setSupportActionBar
import org.mozilla.scryer.ui.InnerSpaceDecoration
import org.mozilla.scryer.util.hideKeyboard
import org.mozilla.scryer.util.showKeyboard
import org.mozilla.scryer.viewmodel.ScreenshotViewModel

class FullTextSearchFragment : androidx.fragment.app.Fragment() {

    companion object {
        private const val SPAN_COUNT = 3
    }

    private var _binding: FragmentFullTextSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var screenshotAdapter: SearchAdapter
    private lateinit var liveData: LiveData<List<ScreenshotModel>>
    private lateinit var collectionList: List<CollectionModel>
    private lateinit var viewModel: ScreenshotViewModel

    private var actionModeMenu: Menu? = null
    private var isIndexing: Boolean = false
    private var isIndexError: Boolean = false
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
        binding.searchEditText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {
                liveData.removeObservers(this@FullTextSearchFragment)
            }

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                screenshotAdapter.showLoadingView(LoadingViewModel(getText(R.string.search_transition_searching)))
                liveData = viewModel.searchScreenshots(s.toString())
            }

            override fun afterTextChanged(s: Editable?) {
                liveData.observe(this@FullTextSearchFragment, Observer { screenshots ->
                    binding.subtitleLayout.visibility = if (screenshots.isEmpty()) {
                        View.GONE
                    } else {
                        View.VISIBLE
                    }
                    binding.emptyView.visibility = if (screenshots.isEmpty() && s?.isNotEmpty() == true && !isIndexing && !isIndexError) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }
                    binding.errorView.visibility = if (isIndexError && screenshots.isEmpty()) {
                        View.VISIBLE
                    } else {
                        View.GONE
                    }

                    if (s?.isEmpty() == true || !isIndexing) {
                        screenshotAdapter.showLoadingView(null)
                    }

                    binding.subtitleTextView.text = getString(R.string.search_separator_results, screenshots.size)

                    screenshots.sortedByDescending { it.lastModified }.let { sorted ->
                        screenshotAdapter.screenshotList = sorted
                        screenshotAdapter.notifyDataSetChanged()
                    }
                })

                binding.clear.visibility = if (binding.searchEditText.text?.isNotEmpty() == true) {
                    View.VISIBLE
                } else {
                    View.INVISIBLE
                }
            }
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

        binding.connectButton.setOnClickListener {
            startActivity(Intent(Settings.ACTION_WIRELESS_SETTINGS))
        }

        binding.screenshotListView.setOnTouchListener { _, _ ->
            hideKeyboard(binding.searchEditText)
            false
        }
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

        ScryerApplication.getContentScanner().getProgressState().observe(
                this@FullTextSearchFragment,
                org.mozilla.scryer.Observer {
                    if (it is ContentScanner.ProgressState.Unavailable) {
                        onIndexError()
                    } else if (it is ContentScanner.ProgressState.Progress) {
                        if (it.current != it.total) {
                            onIndexProgress(it.current, it.total)
                        } else {
                            onIndexEnd()
                        }
                    }
                })

        setHasOptionsMenu(true)
        setupActionBar()
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
        binding.errorView.visibility = View.GONE
        isIndexing = true
        isIndexError = false
    }

    private fun onIndexEnd() {
        screenshotAdapter.showLoadingView(null)
        if (screenshotAdapter.screenshotList.isEmpty()
                && binding.searchEditText.text?.isNotEmpty() == true) {
            binding.emptyView.visibility = View.VISIBLE
        }
        binding.errorView.visibility = View.GONE
        isIndexing = false
        isIndexError = false
    }

    private fun onIndexError() {
        screenshotAdapter.showLoadingView(null)
        binding.errorView.visibility = View.VISIBLE
        isIndexing = false
        isIndexError = true
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
        liveData = viewModel.searchScreenshots("")

        viewModel.getCollections().observe(this.viewLifecycleOwner, Observer { collections ->
            collections?.asSequence()?.filter {
                !SuggestCollectionHelper.isSuggestCollection(it)
            }?.toList()?.let {
                collectionList = it
            }
        })
    }
}
