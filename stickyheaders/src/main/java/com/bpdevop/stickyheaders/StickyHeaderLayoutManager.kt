package com.bpdevop.stickyheaders

import android.content.Context
import android.graphics.PointF
import android.os.Parcel
import android.os.Parcelable
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.LinearSmoothScroller
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * StickyHeaderLayoutManager
 * Provides equivalent behavior to a simple LinearLayoutManager, but where section header items
 * are positioned in a "sticky" manner like the section headers in iOS's UITableView.
 * StickyHeaderLayoutManager MUST be used in conjunction with SectioningAdapter.
 *
 * @see SectioningAdapter
 */
class StickyHeaderLayoutManager : RecyclerView.LayoutManager() {

    enum class HeaderPosition {
        NONE, NATURAL, STICKY, TRAILING
    }

    /**
     * Callback interface for monitoring when header positions change between members of HeaderPosition enum values.
     * This can be useful if client code wants to change appearance for headers in HeaderPosition.STICKY vs normal positioning.
     *
     * @see HeaderPosition
     */
    fun interface HeaderPositionChangedCallback {
        /**
         * Called when a sections header positioning approach changes. The position can be HeaderPosition.NONE, HeaderPosition.NATURAL, HeaderPosition.STICKY or HeaderPosition.TRAILING
         *
         * @param sectionIndex the sections [0...n)
         * @param header       the header view
         * @param oldPosition  the previous positioning of the header (NONE, NATURAL, STICKY or TRAILING)
         * @param newPosition  the new positioning of the header (NATURAL, STICKY or TRAILING)
         */
        fun onHeaderPositionChanged(sectionIndex: Int, header: View, oldPosition: HeaderPosition, newPosition: HeaderPosition)
    }

    companion object {
        private val TAG = StickyHeaderLayoutManager::class.java.simpleName
    }

    private var adapter: SectioningAdapter? = null

    // holds all the visible section headers
    private val headerViews = mutableSetOf<View>()

    // holds the HeaderPosition for each header
    private val headerPositionsBySection = mutableMapOf<Int, HeaderPosition>()

    private var headerPositionChangedCallback: HeaderPositionChangedCallback? = null

    // adapter position of first (lowest-y-value) visible item.
    private var firstViewAdapterPosition: Int = 0

    // top of first (lowest-y-value) visible item.
    private var firstViewTop: Int = 0

    // adapter position (iff >= 0) of the item selected in scrollToPosition
    private var scrollTargetAdapterPosition: Int = -1

    private var pendingSavedState: SavedState? = null

    /**
     * Assign callback object to be notified when a header view position changes between states of the HeaderPosition enum
     *
     * @param headerPositionChangedCallback the callback
     * @see HeaderPosition
     */
    fun setHeaderPositionChangedCallback(headerPositionChangedCallback: HeaderPositionChangedCallback?) {
        this.headerPositionChangedCallback = headerPositionChangedCallback
    }

    override fun onAdapterChanged(oldAdapter: RecyclerView.Adapter<*>?, newAdapter: RecyclerView.Adapter<*>?) {
        super.onAdapterChanged(oldAdapter, newAdapter)
        adapter = newAdapter as? SectioningAdapter
        removeAllViews()
        headerViews.clear()
        headerPositionsBySection.clear()
    }

    override fun onAttachedToWindow(view: RecyclerView) {
        super.onAttachedToWindow(view)
        adapter = view.adapter as? SectioningAdapter
    }

    override fun onDetachedFromWindow(view: RecyclerView, recycler: RecyclerView.Recycler) {
        super.onDetachedFromWindow(view, recycler)

        // Update positions in case we need to save post-detach
        updateFirstAdapterPosition()
    }

    override fun onSaveInstanceState(): Parcelable {
        pendingSavedState?.let { return it }

        // Check if we're detached; if not, update
        if (adapter != null) {
            updateFirstAdapterPosition()
        }

        return SavedState().apply {
            firstViewAdapterPosition = this@StickyHeaderLayoutManager.firstViewAdapterPosition
            firstViewTop = this@StickyHeaderLayoutManager.firstViewTop
        }
    }

    override fun onRestoreInstanceState(state: Parcelable?) {
        when (state) {
            is SavedState -> {
                pendingSavedState = state
                requestLayout()
            }

            null -> return
            else -> Log.e(TAG, "onRestoreInstanceState: invalid saved state class, expected: ${SavedState::class.java.canonicalName} got: ${state.javaClass.canonicalName}")
        }
    }


    override fun onLayoutChildren(recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        if (adapter == null || adapter!!.itemCount == 0) {
            handleEmptyAdapter(recycler)
            return
        }

        initializeState(state)
        var top = firstViewTop

        // RESET
        resetViews(recycler)

        // Setup para el loop
        var mutableAdapterPosition = firstViewAdapterPosition
        val parentBottom = height - paddingBottom
        var totalVendedHeight = 0
        var height: Int

        // Loop principal
        while (mutableAdapterPosition < state.itemCount) {
            val v = recycler.getViewForPosition(mutableAdapterPosition)
            addView(v)
            measureChildWithMargins(v, 0, 0)

            when (getViewBaseType(v)) {
                SectioningAdapter.TYPE_HEADER -> {
                    val (newPosition, headerHeight) = handleHeaderType(v, mutableAdapterPosition, recycler, top, state)
                    mutableAdapterPosition = newPosition // Actualizar la posición para el próximo elemento
                    height = headerHeight // Actualizar la altura para este tipo de vista
                }

                SectioningAdapter.TYPE_GHOST_HEADER -> {
                    height = handleGhostHeaderType(v, mutableAdapterPosition, recycler, top)
                }

                else -> height = handleDefaultType(v, top)
            }

            top += height
            totalVendedHeight += height
            // if the item we just laid out falls off the bottom of the view, we're done
            if (v.bottom >= parentBottom) {
                break
            }

            mutableAdapterPosition++
        }

        correctScrollIfNeeded(totalVendedHeight, recycler, state)
    }

    private fun handleEmptyAdapter(recycler: RecyclerView.Recycler) {
        removeAndRecycleAllViews(recycler)
    }

    private fun initializeState(state: RecyclerView.State) {
        if (scrollTargetAdapterPosition >= 0) {
            firstViewAdapterPosition = scrollTargetAdapterPosition
            firstViewTop = 0
            scrollTargetAdapterPosition = RecyclerView.NO_POSITION
        } else if (pendingSavedState != null && pendingSavedState!!.isValid()) {
            firstViewAdapterPosition = pendingSavedState!!.firstViewAdapterPosition
            firstViewTop = pendingSavedState!!.firstViewTop
            pendingSavedState = null
        } else {
            updateFirstAdapterPosition()
        }

        if (firstViewAdapterPosition >= state.itemCount) {
            firstViewAdapterPosition = state.itemCount - 1
        }
    }

    private fun resetViews(recycler: RecyclerView.Recycler) {
        headerViews.clear()
        headerPositionsBySection.clear()
        detachAndScrapAttachedViews(recycler)
    }

    private fun handleHeaderType(v: View, mutableAdapterPosition: Int, recycler: RecyclerView.Recycler, top: Int, state: RecyclerView.State): Pair<Int, Int> {
        headerViews.add(v)
        val height = getDecoratedMeasuredHeight(v)
        layoutDecorated(v, paddingLeft, top, width - paddingRight, top + height)

        val newPosition = mutableAdapterPosition + 1
        if (newPosition < state.itemCount) {
            // Manejar el ghost header
            val ghostHeader = recycler.getViewForPosition(newPosition)
            addView(ghostHeader)
            layoutDecorated(ghostHeader, paddingLeft, top, width - paddingRight, top + height)
        }

        return Pair(newPosition, height)
    }

    private fun handleGhostHeaderType(v: View, mutableAdapterPosition: Int, recycler: RecyclerView.Recycler, top: Int): Int {
        // we need to back up and get the header for this ghostHeader
        val headerView = recycler.getViewForPosition(mutableAdapterPosition - 1)
        headerViews.add(headerView)
        addView(headerView)
        measureChildWithMargins(headerView, 0, 0)

        val height = getDecoratedMeasuredHeight(headerView)
        layoutDecorated(headerView, paddingLeft, top, width - paddingRight, top + height)
        layoutDecorated(v, paddingLeft, top, width - paddingRight, top + height)

        return height
    }

    private fun handleDefaultType(v: View, top: Int): Int {
        val height = getDecoratedMeasuredHeight(v)
        layoutDecorated(v, paddingLeft, top, width - paddingRight, top + height)
        return height
    }

    private fun correctScrollIfNeeded(totalVendedHeight: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State) {
        // determine if scrolling is necessary to fill viewport
        val innerHeight = height - (paddingTop + paddingBottom)
        if (totalVendedHeight < innerHeight) {
            scrollVerticallyBy(totalVendedHeight - innerHeight, recycler, state)
        } else
            updateHeaderPositions(recycler)
    }


    /**
     * Get the header item for a given section, creating it if it's not already in the view hierarchy
     *
     * @param recycler     the recycler
     * @param sectionIndex the index of the section for in question
     * @return the header, or null if the adapter specifies no header for the section
     */
    private fun createSectionHeaderIfNeeded(recycler: RecyclerView.Recycler, sectionIndex: Int): View? {
        require(adapter?.doesSectionHaveHeader(sectionIndex) == true) {
            "createSectionHeaderIfNeeded should not be called for a section which does not have a header"
        }

        // first, see if we've already got a header for this section
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            if (getViewBaseType(view) == SectioningAdapter.TYPE_HEADER && getViewSectionIndex(view) == sectionIndex) {
                return view
            }
        }

        // looks like we need to create one
        val headerAdapterPosition = adapter?.getAdapterPositionForSectionHeader(sectionIndex) ?: return null
        val headerView = recycler.getViewForPosition(headerAdapterPosition)
        headerViews.add(headerView)
        addView(headerView)
        measureChildWithMargins(headerView, 0, 0)

        return headerView
    }

    override fun scrollVerticallyBy(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        if (childCount == 0) {
            return 0
        }

        // content moving downwards, so we're panning to top of list
        val scrolled: Int = if (dy < 0) {
            scrollUp(dy, recycler)
        } else {
            scrollDown(dy, recycler, state)
        }

        updateAfterScroll(recycler)

        return scrolled
    }

    private fun scrollUp(dy: Int, recycler: RecyclerView.Recycler): Int {
        var scrolled = 0
        var topView: View? = getTopmostChildView() ?: return 0

        while (abs(scrolled) < abs(dy) && topView != null) {
            scrolled -= performScrollUpStep(dy, scrolled, topView)

            // vend next view above topView
            if (firstViewAdapterPosition > 0 && scrolled > dy) {
                topView = vendNextViewAbove(topView, recycler)
            } else {
                break
            }
        }

        return scrolled
    }

    private fun performScrollUpStep(dy: Int, scrolled: Int, topView: View): Int {
        // get the topmost view
        val hangingTop = max(-getDecoratedTop(topView), 0)
        val scrollBy = min(scrolled - dy, hangingTop)
        offsetChildrenVertical(scrollBy)
        return scrollBy
    }

    private fun vendNextViewAbove(topView: View, recycler: RecyclerView.Recycler): View? {
        firstViewAdapterPosition--

        // we're skipping headers. they should already be vended, but if we're vending a ghostHeader
        // here an actual header will be vended if needed for measurement
        var itemViewType = adapter!!.getItemViewBaseType(firstViewAdapterPosition)
        var isHeader = itemViewType == SectioningAdapter.TYPE_HEADER

        // skip the header, move to next item above
        if (isHeader) {
            firstViewAdapterPosition--
            if (firstViewAdapterPosition < 0) {
                return null
            }
            itemViewType = adapter!!.getItemViewBaseType(firstViewAdapterPosition)
            isHeader = itemViewType == SectioningAdapter.TYPE_HEADER

            // If it's still a header, we don't need to do anything right now
            if (isHeader) return null
        }

        val v = recycler.getViewForPosition(firstViewAdapterPosition)
        addView(v, 0)

        val bottom = getDecoratedTop(topView)
        val top: Int
        val isGhostHeader = itemViewType == SectioningAdapter.TYPE_GHOST_HEADER
        top = if (isGhostHeader) {
            val header = createSectionHeaderIfNeeded(recycler, adapter!!.getSectionForAdapterPosition(firstViewAdapterPosition))
            bottom - getDecoratedMeasuredHeight(header!!) // header is already measured
        } else {
            measureChildWithMargins(v, 0, 0)
            bottom - getDecoratedMeasuredHeight(v)
        }

        layoutDecorated(v, paddingLeft, top, width - paddingRight, bottom)
        return v
    }

    private fun scrollDown(dy: Int, recycler: RecyclerView.Recycler, state: RecyclerView.State): Int {
        // content moving up, we're headed to bottom of list
        var scrolled = 0
        val parentHeight = height
        var bottomView: View? = getBottommostChildView() ?: return 0

        while (scrolled < dy && bottomView != null) {
            val scrollBy = calculateScrollBy(dy, scrolled, bottomView, parentHeight)
            scrolled -= scrollBy
            offsetChildrenVertical(scrollBy)

            bottomView = vendNextViewBelow(bottomView, recycler, state, scrolled, dy)
        }

        return scrolled
    }

    private fun calculateScrollBy(dy: Int, scrolled: Int, bottomView: View, parentHeight: Int): Int {
        val hangingBottom = max(getDecoratedBottom(bottomView) - parentHeight, 0)
        return -min(dy - scrolled, hangingBottom)
    }

    private fun vendNextViewBelow(bottomView: View, recycler: RecyclerView.Recycler, state: RecyclerView.State, scrolled: Int, dy: Int): View? {
        val adapterPosition = getViewAdapterPosition(bottomView)
        val nextAdapterPosition = adapterPosition + 1

        if (scrolled < dy && nextAdapterPosition < state.itemCount) {
            return handleViewTypeForScrollDown(recycler, bottomView, nextAdapterPosition)
        }
        return null
    }

    private fun handleViewTypeForScrollDown(recycler: RecyclerView.Recycler, bottomView: View, adapterPosition: Int): View {
        return when (adapter!!.getItemViewBaseType(adapterPosition)) {
            SectioningAdapter.TYPE_HEADER -> vendHeaderForScrollDown(recycler, bottomView, adapterPosition)
            SectioningAdapter.TYPE_GHOST_HEADER -> vendGhostHeaderForScrollDown(recycler, bottomView, adapterPosition)
            else -> vendRegularViewForScrollDown(recycler, bottomView, adapterPosition)
        }
    }

    private fun vendHeaderForScrollDown(recycler: RecyclerView.Recycler, bottomView: View, adapterPosition: Int): View {
        val left = paddingLeft
        val right = width - paddingRight

        // get the header and measure it so we can followup immediately by vending the ghost header
        val headerView = createSectionHeaderIfNeeded(recycler, adapter!!.getSectionForAdapterPosition(adapterPosition))
        val headerHeight = getDecoratedMeasuredHeight(headerView!!)
        layoutDecorated(headerView, left, 0, right, headerHeight)

        // but we need to vend the followup ghost header too
        val nextAdapterPosition = adapterPosition + 1
        val ghostHeader = recycler.getViewForPosition(nextAdapterPosition)
        addView(ghostHeader)
        val top = getDecoratedBottom(bottomView)
        layoutDecorated(ghostHeader, left, top, right, top + headerHeight)

        return ghostHeader
    }

    private fun vendGhostHeaderForScrollDown(recycler: RecyclerView.Recycler, bottomView: View, adapterPosition: Int): View {
        val left = paddingLeft
        val right = width - paddingRight

        // get the header and measure it so we can followup immediately by vending the ghost header
        val headerView = createSectionHeaderIfNeeded(recycler, adapter!!.getSectionForAdapterPosition(adapterPosition))
        val headerHeight = getDecoratedMeasuredHeight(headerView!!)
        layoutDecorated(headerView, left, 0, right, headerHeight)

        // but we need to vend the followup ghost header too
        val ghostHeader = recycler.getViewForPosition(adapterPosition)
        addView(ghostHeader)
        val top = getDecoratedBottom(bottomView)
        layoutDecorated(ghostHeader, left, top, right, top + height)

        return ghostHeader
    }

    private fun vendRegularViewForScrollDown(recycler: RecyclerView.Recycler, bottomView: View, adapterPosition: Int): View {
        val left = paddingLeft
        val right = width - paddingRight

        val v = recycler.getViewForPosition(adapterPosition)
        addView(v)

        measureChildWithMargins(v, 0, 0)

        val top = getDecoratedBottom(bottomView)
        val height = getDecoratedMeasuredHeight(v)
        layoutDecorated(v, left, top, right, top + height)

        return v
    }

    private fun updateAfterScroll(recycler: RecyclerView.Recycler) {
        getTopmostChildView()?.let { topmostView ->
            firstViewTop = getDecoratedTop(topmostView)
        }

        updateHeaderPositions(recycler)
        recycleViewsOutOfBounds(recycler)
    }

    override fun generateDefaultLayoutParams(): RecyclerView.LayoutParams {
        return RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun canScrollVertically(): Boolean {
        return true
    }

    override fun scrollToPosition(position: Int) {
        require(position in 0 until itemCount) {
            "adapter position out of range"
        }

        scrollTargetAdapterPosition = position
        pendingSavedState = null
        requestLayout()
    }


    /**
     * @param fullyVisibleOnly if true, the search will be limited to the first item not hanging off top of screen or partially obscured by a header
     * @return the viewholder for the first visible item (not header or footer)
     */
    fun getFirstVisibleItemViewHolder(fullyVisibleOnly: Boolean): SectioningAdapter.ItemViewHolder? {
        return getFirstVisibleViewHolderOfType(SectioningAdapter.TYPE_ITEM, fullyVisibleOnly) as? SectioningAdapter.ItemViewHolder
    }

    /**
     * @param fullyVisibleOnly if true, the search will be limited to the first header not hanging off top of screen
     * @return the viewholder for the first visible header (not item or footer)
     */
    fun getFirstVisibleHeaderViewHolder(fullyVisibleOnly: Boolean): SectioningAdapter.HeaderViewHolder? {
        return getFirstVisibleViewHolderOfType(SectioningAdapter.TYPE_HEADER, fullyVisibleOnly) as? SectioningAdapter.HeaderViewHolder
    }

    /**
     * @param fullyVisibleOnly if true, the search will be limited to the first footer not hanging off top of screen or partially obscured by a header
     * @return the viewholder for the first visible footer (not header or item)
     */
    fun getFirstVisibleFooterViewHolder(fullyVisibleOnly: Boolean): SectioningAdapter.FooterViewHolder? {
        return getFirstVisibleViewHolderOfType(SectioningAdapter.TYPE_FOOTER, fullyVisibleOnly) as? SectioningAdapter.FooterViewHolder
    }

    private fun getFirstVisibleViewHolderOfType(baseType: Int, fullyVisibleOnly: Boolean): SectioningAdapter.ViewHolder? {
        if (childCount == 0) {
            return null
        }

        // we need to discard items which are obscured by a header, so find
        // how tall the first header is, and we'll filter that the decoratedTop of
        // our items is below this value
        var firstHeaderBottom = 0
        if (baseType != SectioningAdapter.TYPE_HEADER) {
            getFirstVisibleHeaderViewHolder(false)?.let {
                firstHeaderBottom = getDecoratedBottom(it.itemView)
            }
        }

        // note: We can't use child view order because we muck with moving things to front
        var topmostView: View? = null
        var top = Int.MAX_VALUE

        (0 until childCount)
            .asSequence()
            .mapNotNull { getChildAt(it) }
            .filterNot { getViewAdapterPosition(it) == RecyclerView.NO_POSITION }
            .filter { getViewBaseType(it) == baseType }
            .filter {
                val t = getDecoratedTop(it)
                val b = getDecoratedBottom(it)
                if (fullyVisibleOnly) t >= firstHeaderBottom else b > firstHeaderBottom + 1
            }
            .forEach {
                val t = getDecoratedTop(it)
                if (t < top) {
                    top = t
                    topmostView = it
                }
            }

        return topmostView?.let { getViewViewHolder(it) }
    }

    /**
     * @param fullyVisibleOnly if true, the search will be limited to the last item not hanging off bottom of screen
     * @return the viewholder for the last visible item (not header or footer)
     */
    fun getLastVisibleItemViewHolder(fullyVisibleOnly: Boolean): SectioningAdapter.ItemViewHolder? {
        return getLastVisibleViewHolderOfType(SectioningAdapter.TYPE_ITEM, fullyVisibleOnly) as? SectioningAdapter.ItemViewHolder
    }

    /**
     * @param fullyVisibleOnly if true, the search will be limited to the last header not hanging off bottom of screen
     * @return the viewholder for the last visible header (not item or footer)
     */
    fun getLastVisibleHeaderViewHolder(fullyVisibleOnly: Boolean): SectioningAdapter.HeaderViewHolder? {
        return getLastVisibleViewHolderOfType(SectioningAdapter.TYPE_HEADER, fullyVisibleOnly) as? SectioningAdapter.HeaderViewHolder
    }

    /**
     * @param fullyVisibleOnly if true, the search will be limited to the last footer not hanging off bottom of screen
     * @return the viewholder for the last visible footer (not header or item)
     */
    fun getLastVisibleFooterViewHolder(fullyVisibleOnly: Boolean): SectioningAdapter.FooterViewHolder? {
        return getLastVisibleViewHolderOfType(SectioningAdapter.TYPE_FOOTER, fullyVisibleOnly) as? SectioningAdapter.FooterViewHolder
    }

    private fun getLastVisibleViewHolderOfType(baseType: Int, fullyVisibleOnly: Boolean): SectioningAdapter.ViewHolder? {
        if (childCount == 0) {
            return null
        }

        val height = height
        var bottommostView: View? = null
        var bottom = Int.MIN_VALUE

        (0 until childCount)
            .asSequence()
            .mapNotNull { getChildAt(it) }
            .filter { getViewAdapterPosition(it) != RecyclerView.NO_POSITION }
            .filter { getViewBaseType(it) == baseType }
            .filter {
                val t = getDecoratedTop(it)
                val b = getDecoratedBottom(it)
                if (fullyVisibleOnly) b <= height else t < height
            }
            .forEach {
                val b = getDecoratedBottom(it)
                if (b > bottom) {
                    bottom = b
                    bottommostView = it
                }
            }

        return bottommostView?.let { getViewViewHolder(it) }
    }


    override fun smoothScrollToPosition(recyclerView: RecyclerView, state: RecyclerView.State, position: Int) {
        require(position in 0 until itemCount) {
            "adapter position out of range"
        }

        pendingSavedState = null

        val firstVisibleChild = recyclerView.getChildAt(0)
        val itemHeight = getEstimatedItemHeightForSmoothScroll(recyclerView)
        val currentPosition = recyclerView.getChildAdapterPosition(firstVisibleChild)
        var distanceInPixels = abs((currentPosition - position) * itemHeight)
        if (distanceInPixels == 0) {
            distanceInPixels = abs(firstVisibleChild.y.toInt())
        }

        val context = recyclerView.context
        val scroller = SmoothScroller(context, distanceInPixels).apply {
            targetPosition = position
        }
        startSmoothScroll(scroller)
    }

    private fun getEstimatedItemHeightForSmoothScroll(recyclerView: RecyclerView): Int {
        return (0 until recyclerView.childCount)
            .map { recyclerView.getChildAt(it) }
            .maxOfOrNull { getDecoratedMeasuredHeight(it) } ?: 0
    }


    private fun computeScrollVectorForPosition(targetPosition: Int): Int {
        updateFirstAdapterPosition()
        return when {
            targetPosition > firstViewAdapterPosition -> 1
            targetPosition < firstViewAdapterPosition -> -1
            else -> 0
        }
    }

    private fun recycleViewsOutOfBounds(recycler: RecyclerView.Recycler) {
        val height = height
        val remainingSections = mutableSetOf<Int>()
        val viewsToRecycle = mutableSetOf<View>()

        recycleNonHeaderViews(height, remainingSections, viewsToRecycle)
        recycleOrphanedHeaders(height, remainingSections, viewsToRecycle)

        viewsToRecycle.forEach { removeAndRecycleView(it, recycler) }

        // determine the adapter adapterPosition of first visible item
        updateFirstAdapterPosition()
    }

    private fun recycleNonHeaderViews(height: Int, remainingSections: MutableSet<Int>, viewsToRecycle: MutableSet<View>) {
        for (i in 0 until childCount) {
            val view = getChildAt(i) ?: continue
            // skip views which have been recycled but are still in place because of animation
            if (isViewRecycled(view) || getViewBaseType(view) == SectioningAdapter.TYPE_HEADER) continue

            if (getDecoratedBottom(view) < 0 || getDecoratedTop(view) > height) {
                viewsToRecycle.add(view)
            } else {
                // this view is visible, therefore the section lives
                remainingSections.add(getViewSectionIndex(view))
            }
        }
    }

    private fun recycleOrphanedHeaders(height: Int, remainingSections: Set<Int>, viewsToRecycle: MutableSet<View>) {
        for (i in 0 until childCount) {
            val view = getChildAt(i) ?: continue
            if (isViewRecycled(view)) continue

            val sectionIndex = getViewSectionIndex(view)
            if (getViewBaseType(view) == SectioningAdapter.TYPE_HEADER && !remainingSections.contains(sectionIndex)) {
                val translationY = view.translationY
                if ((getDecoratedBottom(view) + translationY) < 0 || (getDecoratedTop(view) + translationY) > height) {
                    viewsToRecycle.add(view)
                    headerViews.remove(view)
                    headerPositionsBySection.remove(sectionIndex)
                }
            }
        }
    }

    private fun getTopmostChildView(): View? {
        if (childCount == 0) return null

        // note: We can't use child view order because we muck with moving things to front
        var topmostView: View? = null
        var top = Int.MAX_VALUE

        for (i in 0 until childCount) {
            val v = getChildAt(i) ?: continue

            // ignore views which are being deleted or ignore headers
            if (getViewAdapterPosition(v) == RecyclerView.NO_POSITION || getViewBaseType(v) == SectioningAdapter.TYPE_HEADER) continue

            val t = getDecoratedTop(v)
            if (t < top) {
                top = t
                topmostView = v
            }
        }

        return topmostView
    }

    fun getBottommostChildView(): View? {
        if (childCount == 0) return null

        var bottommostView: View? = null
        var bottom = Int.MIN_VALUE

        for (i in 0 until childCount) {
            val v = getChildAt(i) ?: continue

            // ignore views which are being deleted or ignore headers
            if (getViewAdapterPosition(v) == RecyclerView.NO_POSITION || getViewBaseType(v) == SectioningAdapter.TYPE_HEADER) continue

            val b = getDecoratedBottom(v)
            if (b > bottom) {
                bottom = b
                bottommostView = v
            }
        }

        return bottommostView
    }


    /**
     * Updates firstViewAdapterPosition to the adapter position  of the highest item in the list - e.g., the
     * adapter position of the item with lowest y value in the list
     *
     * @return the y value of the topmost view in the layout, or paddingTop if empty
     */
    private fun updateFirstAdapterPosition(): Int {
        return if (childCount == 0) {
            firstViewAdapterPosition = 0
            paddingTop.also { firstViewTop = it }
        } else {
            getTopmostChildView()?.let { topmostView ->
                firstViewAdapterPosition = getViewAdapterPosition(topmostView)
                minOf(topmostView.top, paddingTop).also { firstViewTop = it }
            } ?: firstViewTop
        }
    }


    private fun updateHeaderPositions(recycler: RecyclerView.Recycler) {

        // first, for each section represented by the current list of items,
        // ensure that the header for that section is extant
        val visitedSections = mutableSetOf<Int>()
        for (i in 0 until childCount) {
            val view = getChildAt(i)
            val sectionIndex = getViewSectionIndex(view)

            if (visitedSections.add(sectionIndex) && adapter?.doesSectionHaveHeader(sectionIndex) == true) {
                createSectionHeaderIfNeeded(recycler, sectionIndex)
            }
        }

        // header is always positioned at top
        val left = paddingLeft
        val right = width - paddingRight

        headerViews.forEach { headerView ->
            val sectionIndex = getViewSectionIndex(headerView)

            // find first and last non-header views in this section
            val (ghostHeader, firstViewInNextSection) = findSectionBoundaries(sectionIndex)

            val height = getDecoratedMeasuredHeight(headerView)
            var top = paddingTop

            // initial position mark
            var headerPosition = HeaderPosition.STICKY

            top = adjustTopForGhostHeader(ghostHeader, top).also { newPosition ->
                if (newPosition != top) headerPosition = HeaderPosition.NATURAL
            }

            top = adjustTopForNextSection(firstViewInNextSection, top, height).also { newPosition ->
                if (newPosition != top) headerPosition = HeaderPosition.TRAILING
            }

            // now bring header to front of stack for overlap, and position it
            headerView.bringToFront()
            layoutDecorated(headerView, left, top, right, top + height)

            // notify adapter of positioning for this header
            recordHeaderPositionAndNotify(sectionIndex, headerView, headerPosition)
        }
    }

    private fun findSectionBoundaries(sectionIndex: Int): Pair<View?, View?> {
        var ghostHeader: View? = null
        var firstViewInNextSection: View? = null

        for (i in 0 until childCount) {
            val view = getChildAt(i)

            // the view has been recycled
            if (isViewRecycled(view)) continue

            val type = getViewBaseType(view)
            if (type == SectioningAdapter.TYPE_HEADER) continue

            val viewSectionIndex = getViewSectionIndex(view)
            when {
                viewSectionIndex == sectionIndex && type == SectioningAdapter.TYPE_GHOST_HEADER -> ghostHeader = view
                viewSectionIndex == sectionIndex + 1 && firstViewInNextSection == null -> firstViewInNextSection = view
            }
        }

        return Pair(ghostHeader, firstViewInNextSection)
    }

    private fun adjustTopForGhostHeader(ghostHeader: View?, currentTop: Int): Int {
        ghostHeader?.let {
            val ghostHeaderTop = getDecoratedTop(it)
            if (ghostHeaderTop >= currentTop) {
                return ghostHeaderTop
            }
        }
        return currentTop
    }

    private fun adjustTopForNextSection(nextSectionView: View?, currentTop: Int, headerHeight: Int): Int {
        nextSectionView?.let {
            val nextViewTop = getDecoratedTop(it)
            if (nextViewTop - headerHeight < currentTop) {
                return nextViewTop - headerHeight
            }
        }
        return currentTop
    }

    private fun recordHeaderPositionAndNotify(sectionIndex: Int, headerView: View, newHeaderPosition: HeaderPosition) {
        val currentHeaderPosition = headerPositionsBySection[sectionIndex]
        if (currentHeaderPosition != newHeaderPosition) {
            headerPositionsBySection[sectionIndex] = newHeaderPosition
            headerPositionChangedCallback?.onHeaderPositionChanged(
                sectionIndex,
                headerView,
                currentHeaderPosition ?: HeaderPosition.NONE,
                newHeaderPosition
            )
        }
    }

    private fun isViewRecycled(view: View?): Boolean = getViewAdapterPosition(view) == RecyclerView.NO_POSITION

    private fun getViewBaseType(view: View?): Int {
        val adapterPosition = getViewAdapterPosition(view)
        return adapter?.getItemViewBaseType(adapterPosition) ?: SectioningAdapter.TYPE_ITEM
    }

    private fun getViewSectionIndex(view: View?): Int {
        val adapterPosition = getViewAdapterPosition(view)
        return adapter?.getSectionForAdapterPosition(adapterPosition) ?: -1
    }

    private fun getViewViewHolder(view: View?): SectioningAdapter.ViewHolder? = view?.getTag(R.id.sectioning_adapter_tag_key_view_viewholder) as? SectioningAdapter.ViewHolder

    fun getViewAdapterPosition(view: View?): Int = getViewViewHolder(view)?.absoluteAdapterPosition ?: RecyclerView.NO_POSITION

    private inner class SmoothScroller(context: Context, private val distanceInPixels: Int) : LinearSmoothScroller(context) {
        private val targetSeekScrollDistancePx = 10000
        private val defaultDuration = 1000f

        private val duration: Float = if (distanceInPixels < targetSeekScrollDistancePx) {
            abs(distanceInPixels) * calculateSpeedPerPixel(context.resources.displayMetrics)
        } else {
            defaultDuration
        }

        override fun computeScrollVectorForPosition(targetPosition: Int): PointF {
            return PointF(0f, this@StickyHeaderLayoutManager.computeScrollVectorForPosition(targetPosition).toFloat())
        }

        override fun calculateTimeForScrolling(dx: Int): Int {
            val proportion = dx.toFloat() / distanceInPixels
            return (duration * proportion).toInt()
        }
    }

    private class SavedState : Parcelable {
        var firstViewAdapterPosition = RecyclerView.NO_POSITION
        var firstViewTop = 0

        constructor()

        private constructor(parcel: Parcel) {
            firstViewAdapterPosition = parcel.readInt()
            firstViewTop = parcel.readInt()
        }

        fun isValid(): Boolean = firstViewAdapterPosition >= 0

        override fun toString(): String {
            return "<${this::class.java.canonicalName} firstViewAdapterPosition: $firstViewAdapterPosition firstViewTop: $firstViewTop>"
        }

        override fun describeContents(): Int = 0

        override fun writeToParcel(dest: Parcel, flags: Int) {
            dest.writeInt(firstViewAdapterPosition)
            dest.writeInt(firstViewTop)
        }

        companion object CREATOR : Parcelable.Creator<SavedState> {
            override fun createFromParcel(parcel: Parcel): SavedState = SavedState(parcel)
            override fun newArray(size: Int): Array<SavedState?> = arrayOfNulls(size)
        }
    }
}