/* -*- Mode: Java; c-basic-offset: 4; tab-width: 4; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package io.github.tzhvh.scryernext.search

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableString
import android.text.Spanned
import android.text.style.StyleSpan
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import io.github.tzhvh.scryernext.R
import io.github.tzhvh.scryernext.collectionview.ListSelector
import io.github.tzhvh.scryernext.extension.getValidPosition
import io.github.tzhvh.scryernext.collectionview.ScreenshotAdapter
import io.github.tzhvh.scryernext.persistence.LoadingViewModel
import io.github.tzhvh.scryernext.persistence.ScreenshotModel

class SearchAdapter(
        context: Context?,
        selector: ListSelector<ScreenshotModel>? = null,
        onItemClickListener: ((item: ScreenshotModel, view: View?, position: Int) -> Unit)? = null
) : ScreenshotAdapter(context, selector, onItemClickListener) {

    companion object {
        const val VIEW_TYPE_ITEM = 0
        const val VIEW_TYPE_LOADING = 1
        const val VIEW_TYPE_LIST_ITEM = 2
    }

    private var loadingViewModel: LoadingViewModel? = null

    /**
     * Phase 2.1 step 8 — the list mode: thumbnail + display name + a 2–3 line OCR snippet with
     * the matched terms highlighted. Grid mode is unchanged; users who never toggle pay no cost
     * (the accepted-residual-risk note in ZVEC_PHASE2.1.md). [snippetTerms] are the query's
     * positive tokens (post-pre-shape); [contentByHash] is the text the store already projects —
     * no new zvec I/O. Both are set (or cleared) together with [listMode] by the fragment.
     */
    var listMode: Boolean = false
    var snippetTerms: List<String> = emptyList()
    var contentByHash: Map<String, String> = emptyMap()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {

        if (viewType == VIEW_TYPE_LIST_ITEM) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_search_list, parent, false)
            val holder = SearchListItemHolder(view)
            holder.image = view.findViewById(R.id.list_image)
            holder.title = view.findViewById(R.id.list_title)
            holder.snippet = view.findViewById(R.id.list_snippet)
            holder.itemView.setOnClickListener { _ ->
                holder.getValidPosition { position: Int ->
                    if (selector?.isSelectMode == true) {
                        val screenshot = screenshotList[position]
                        selector.toggleSelection(screenshot)
                    } else {
                        onItemClickListener?.invoke(screenshotList[position], holder.image, position)
                    }
                }
            }
            holder.itemView.setOnLongClickListener { _ ->
                if (selector?.isSelectMode == true) return@setOnLongClickListener true
                enterSelectionMode()
                holder.getValidPosition { position ->
                    selector?.toggleSelection(screenshotList[position])
                }
                true
            }
            return holder
        }

        if (loadingViewModel != null && viewType == VIEW_TYPE_LOADING) {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_loading, parent, false)

            val holder = LoadingViewHolder(view)
            holder.primaryTextView = view.findViewById(R.id.primaryTextView)
            holder.secondaryTextView = view.findViewById(R.id.secondaryTextView)

            return holder
        }

        return super.onCreateViewHolder(parent, viewType)
    }

    override fun getItemViewType(position: Int): Int {
        if (loadingViewModel == null) {
            return if (listMode) VIEW_TYPE_LIST_ITEM else VIEW_TYPE_ITEM
        }

        if (position == itemCount - 1) {
            return VIEW_TYPE_LOADING
        }

        return VIEW_TYPE_ITEM
    }

    override fun getItemCount(): Int {
        return if (loadingViewModel == null) {
            screenshotList.size
        } else {
            screenshotList.size + 1
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (loadingViewModel != null && getItemViewType(position) == VIEW_TYPE_LOADING) {
            if (holder !is LoadingViewHolder) return

            holder.primaryTextView?.text = loadingViewModel?.primaryText
            holder.secondaryTextView?.text = loadingViewModel?.secondaryText

            return
        }

        if (holder is SearchListItemHolder) {
            val screenshot = screenshotList[position]
            holder.title?.text = screenshot.displayName
            holder.image?.let {
                Glide.with(holder.itemView.context)
                    .load(android.net.Uri.parse(screenshot.uri))
                    .into(it)
            }
            val content = contentByHash[screenshot.contentHash] ?: ""
            val snippet = SnippetBuilder.build(content, snippetTerms)
            val spannable = SpannableString(snippet.text)
            for (range in snippet.ranges) {
                spannable.setSpan(
                    StyleSpan(Typeface.BOLD), range.first, range.last + 1,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
            holder.snippet?.text = spannable
            return
        }

        super.onBindViewHolder(holder, position)
    }

    fun showLoadingView(loadingViewModel: LoadingViewModel?) {
        this.loadingViewModel = loadingViewModel
        notifyItemInserted(itemCount - 1)
    }
}

class LoadingViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    var primaryTextView: TextView? = null
    var secondaryTextView: TextView? = null
}

class SearchListItemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
    var image: ImageView? = null
    var title: TextView? = null
    var snippet: TextView? = null
}