package com.bpdevop.stickyheaders

import android.os.Handler
import android.os.Looper
import android.util.SparseBooleanArray
import android.view.View
import android.view.ViewGroup
import androidx.core.util.forEach
import androidx.recyclerview.widget.RecyclerView

/**
 * SectioningAdapter
 * Represents a list of sections, each containing a list of items and optionally a header and or footer item.
 * SectioningAdapter may be used with a normal RecyclerView.LinearLayoutManager but is meant for use with
 * StickyHeaderLayoutManager to allow for sticky positioning of header items.
 * <p/>
 * When invalidating the adapter's contents NEVER use RecyclerView.Adapter.notify* methods. These methods
 * aren't aware of the section information and internal state of SectioningAdapter. As such, please
 * use the SectioningAdapter.notify* methods.
 * <p/>
 * SectioningAdapter manages four types of items: TYPE_HEADER, TYPE_ITEM, TYPE_FOOTER and TYPE_GHOST_HEADER.
 * Headers are the optional first item in a section. A section then has some number of items in it,
 * and an optional footer. The ghost header is a special item used for layout mechanics. It can
 * be ignored by SectioningAdapter subclasses - but it is made externally accessible just in case.
 */
abstract class SectioningAdapter : RecyclerView.Adapter<SectioningAdapter.ViewHolder>() {
    private class Section {
        var adapterPosition: Int = 0    // Position of first item (the header) of this section
        var numberOfItems: Int = 0      // Number of items (not including header or footer)
        var length: Int = 0             // Total number of items in section including header and footer
        var hasHeader: Boolean = false  // True if section has a header
        var hasFooter: Boolean = false  // True if section has a footer
    }

    private class SectionSelectionState {
        var section: Boolean = false
        var items: SparseBooleanArray = SparseBooleanArray()
        var footer: Boolean = false
    }

    private var sections: ArrayList<Section> = arrayListOf()
    private var collapsedSections: HashMap<Int, Boolean> = hashMapOf()
    private var selectionStateBySection: HashMap<Int, SectionSelectionState> = hashMapOf()
    private var sectionIndicesByAdapterPosition: IntArray = intArrayOf()
    private var totalNumberOfItems: Int = 0
    private var mainThreadHandler: Handler? = null

    open class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        var section: Int = 0
            internal set
        var numberOfItemsInSection: Int = 0
            internal set

        fun getItemViewBaseType(): Int {
            return unmaskBaseViewType(itemViewType)
        }

        fun getItemViewUserType(): Int {
            return unmaskUserViewType(itemViewType)
        }

        open fun isHeader(): Boolean = false
        open fun isGhostHeader(): Boolean = false
        open fun isFooter(): Boolean = false
    }

    open class ItemViewHolder(itemView: View) : ViewHolder(itemView) {
        var positionInSection: Int = 0
            internal set
    }

    open class HeaderViewHolder(itemView: View) : ViewHolder(itemView) {
        override fun isHeader(): Boolean = true
    }

    class GhostHeaderViewHolder(itemView: View) : ViewHolder(itemView) {
        override fun isGhostHeader(): Boolean = true
    }

    class FooterViewHolder(itemView: View) : ViewHolder(itemView) {
        override fun isFooter(): Boolean = true
    }

    /**
     * @return Number of sections
     */
    open fun getNumberOfSections(): Int = 0

    /**
     * @param sectionIndex index of the section in question
     * @return the number of items in the specified section
     */
    open fun getNumberOfItemsInSection(sectionIndex: Int): Int = 0

    /**
     * @param sectionIndex index of the section in question
     * @return true if this section has a header
     */
    open fun doesSectionHaveHeader(sectionIndex: Int): Boolean = false

    /**
     * For scenarios with multiple types of headers, override this to return an integer in range [0,255] specifying a custom type for this header.
     * The value you return here will be passes to onCreateHeaderViewHolder and onBindHeaderViewHolder as the 'userType'
     *
     * @param sectionIndex the header's section
     * @return the custom type for this header in range [0,255]
     */
    open fun getSectionHeaderUserType(sectionIndex: Int): Int = 0

    /**
     * @param sectionIndex index of the section in question
     * @return true if this section has a footer
     */
    open fun doesSectionHaveFooter(sectionIndex: Int): Boolean = false

    /**
     * For scenarios with multiple types of footers, override this to return an integer in range [0, 255] specifying a custom type for this footer.
     * The value you return here will be passes to onCreateFooterViewHolder and onBindFooterViewHolder as the 'userType'
     *
     * @param sectionIndex the footer's section
     * @return the custom type for this footer in range [0,255]
     */
    open fun getSectionFooterUserType(sectionIndex: Int): Int = 0

    /**
     * For scenarios with multiple types of items, override this to return an integer in range [0,255] specifying a custom type for the item at this position
     * The value you return here will be passes to onCreateItemViewHolder and onBindItemViewHolder as the 'userType'
     *
     * @param sectionIndex the items's section
     * @param itemIndex    the position of the item in the section
     * @return the custom type for this item in range [0,255]
     */
    open fun getSectionItemUserType(sectionIndex: Int, itemIndex: Int): Int = 0

    /**
     * Called when a ViewHolder is needed for a section item view
     *
     * @param parent       The ViewGroup into which the new View will be added after it is bound to an adapter position.
     * @param itemUserType If getSectionItemUserType is overridden to vend custom types, this will be the specified type
     * @return A new ItemViewHolder holding an item view
     */
    open fun onCreateItemViewHolder(parent: ViewGroup, itemUserType: Int): ItemViewHolder? = null

    /**
     * Called when a ViewHolder is needed for a section header view
     *
     * @param parent         The ViewGroup into which the new View will be added after it is bound to an adapter position.
     * @param headerUserType If getSectionHeaderUserType is overridden to vend custom types, this will be the specified type
     * @return A new HeaderViewHolder holding a header view
     */
    open fun onCreateHeaderViewHolder(parent: ViewGroup, headerUserType: Int): HeaderViewHolder? = null

    /**
     * Called when a ViewHolder is needed for a section footer view
     *
     * @param parent         The ViewGroup into which the new View will be added after it is bound to an adapter position.
     * @param footerUserType If getSectionHeaderUserType is overridden to vend custom types, this will be the specified type
     * @return A new FooterViewHolder holding a footer view
     */
    open fun onCreateFooterViewHolder(parent: ViewGroup, footerUserType: Int): FooterViewHolder? = null

    /**
     * Called when a ViewHolder is needed for a section ghost header view
     *
     * @param parent The ViewGroup into which the new View will be added after it is bound to an adapter position.
     * @return A new GhostHeaderViewHolder holding a ghost header view
     */
    open fun onCreateGhostHeaderViewHolder(parent: ViewGroup): GhostHeaderViewHolder {
        val ghostView = View(parent.context)
        return GhostHeaderViewHolder(ghostView)
    }

    /**
     * Called to display item data at particular position
     *
     * @param viewHolder   the view holder to update
     * @param sectionIndex the index of the section containing the item
     * @param itemIndex    the index of the item in the section where 0 is the first item
     * @param itemUserType if getSectionItemUserType is overridden to provide custom item types, this will be the type for this item
     */
    open fun onBindItemViewHolder(viewHolder: ItemViewHolder, sectionIndex: Int, itemIndex: Int, itemUserType: Int) {}

    /**
     * Called to display header data for a particular section
     *
     * @param viewHolder     the view holder to update
     * @param sectionIndex   the index of the section containing the header to update
     * @param headerUserType if getSectionHeaderUserType is overridden to provide custom header types, this will be the type for this header
     */
    open fun onBindHeaderViewHolder(viewHolder: HeaderViewHolder, sectionIndex: Int, headerUserType: Int) {}

    /**
     * Called to update the ghost header for a particular section. Note, most implementations will not need to ever touch the ghost header.
     *
     * @param viewHolder   the view holder to update
     * @param sectionIndex the index of the section containing the ghost header to update
     */
    open fun onBindGhostHeaderViewHolder(viewHolder: GhostHeaderViewHolder, sectionIndex: Int) {}

    /**
     * Called to display footer data for a particular section
     *
     * @param viewHolder     the view holder to update
     * @param sectionIndex   the index of the section containing the footer to update
     * @param footerUserType if getSectionFooterUserType is overridden to provide custom footer types, this will be the type for this footer
     */
    open fun onBindFooterViewHolder(viewHolder: FooterViewHolder, sectionIndex: Int, footerUserType: Int) {}

    /**
     * Given a "global" adapter adapterPosition, determine which sections contains that item
     *
     * @param adapterPosition an adapter adapterPosition from 0 to getItemCount()-1
     * @return the index of the sections containing that item
     */
    fun getSectionForAdapterPosition(adapterPosition: Int): Int {
        if (sections.isEmpty()) {
            buildSectionIndex()
        }

        require(adapterPosition in 0 until itemCount) {
            "adapterPosition $adapterPosition is not in range of items represented by adapter"
        }

        return sectionIndicesByAdapterPosition[adapterPosition]
    }

    /**
     * Given a sectionIndex and an adapter position get the local position of an item relative to the sectionIndex,
     * where the first item has position 0
     *
     * @param sectionIndex    the sectionIndex index
     * @param adapterPosition the adapter adapterPosition
     * @return the position relative to the sectionIndex of an item in that sectionIndex
     * <p/>
     * Note, if the adapterPosition corresponds to a sectionIndex header, this will return -1
     */
    private fun getPositionOfItemInSection(sectionIndex: Int, adapterPosition: Int): Int {
        if (sections.isEmpty()) {
            buildSectionIndex()
        }

        require(sectionIndex >= 0) {
            "sectionIndex $sectionIndex < 0"
        }

        require(sectionIndex < sections.size) {
            "sectionIndex $sectionIndex >= sections.size (${sections.size})"
        }

        val section = sections[sectionIndex]
        var localPosition = adapterPosition - section.adapterPosition
        require(localPosition <= section.length) {
            "adapterPosition: $adapterPosition is beyond sectionIndex: $sectionIndex length: ${section.length}"
        }

        if (section.hasHeader) {
            // adjust for header and ghostHeader
            localPosition -= 2
        }

        return localPosition
    }


    /**
     * Given a sectionIndex index, and an offset into the sectionIndex where 0 is the header, 1 is the ghostHeader, 2 is the first item in the sectionIndex, return the corresponding "global" adapter position
     *
     * @param sectionIndex      a sectionIndex index
     * @param offsetIntoSection offset into sectionIndex where 0 is the header, 1 is the first item, etc
     * @return the "global" adapter adapterPosition
     */
    private fun getAdapterPosition(sectionIndex: Int, offsetIntoSection: Int): Int {
        if (sections.isEmpty()) {
            buildSectionIndex()
        }

        require(sectionIndex >= 0) {
            "sectionIndex $sectionIndex < 0"
        }

        require(sectionIndex < sections.size) {
            "sectionIndex $sectionIndex >= sections.size (${sections.size})"
        }

        val section = sections[sectionIndex]
        return offsetIntoSection + section.adapterPosition
    }


    /**
     * Return the adapter position corresponding to the header of the provided section
     *
     * @param sectionIndex the index of the section
     * @return adapter position of that section's header, or NO_POSITION if section has no header
     */
    fun getAdapterPositionForSectionHeader(sectionIndex: Int): Int {
        return if (doesSectionHaveHeader(sectionIndex)) {
            getAdapterPosition(sectionIndex, 0)
        } else {
            NO_POSITION
        }
    }

    /**
     * Return the adapter position corresponding to the ghost header of the provided section
     *
     * @param sectionIndex the index of the section
     * @return adapter position of that section's ghost header, or NO_POSITION if section has no ghost header
     */
    fun getAdapterPositionForSectionGhostHeader(sectionIndex: Int): Int {
        return if (doesSectionHaveHeader(sectionIndex)) {
            getAdapterPosition(sectionIndex, 1) // ghost header follows the header
        } else {
            NO_POSITION
        }
    }

    /**
     * Return the adapter position corresponding to a specific item in the section
     *
     * @param sectionIndex      the index of the section
     * @param offsetIntoSection the offset of the item in the section where 0 would be the first item in the section
     * @return adapter position of the item in the section
     */
    fun getAdapterPositionForSectionItem(sectionIndex: Int, offsetIntoSection: Int): Int {
        return if (doesSectionHaveHeader(sectionIndex)) {
            getAdapterPosition(sectionIndex, offsetIntoSection + 2) // header is at position 0, ghostHeader at position 1
        } else {
            getAdapterPosition(sectionIndex, offsetIntoSection)
        }
    }

    /**
     * Return the adapter position corresponding to the footer of the provided section
     *
     * @param sectionIndex the index of the section
     * @return adapter position of that section's footer, or NO_POSITION if section does not have footer
     */
    fun getAdapterPositionForSectionFooter(sectionIndex: Int): Int {
        return if (doesSectionHaveFooter(sectionIndex)) {
            val section = sections[sectionIndex]
            section.adapterPosition + section.length - 1
        } else {
            RecyclerView.NO_POSITION
        }
    }

    /**
     * Mark that a section is collapsed or not. By default sections are not collapsed and draw
     * all their child items. By "collapsing" a section, the child items are hidden.
     *
     * @param sectionIndex index of section
     * @param collapsed    if true, section is collapsed, false, it's open
     */
    fun setSectionIsCollapsed(sectionIndex: Int, collapsed: Boolean) {
        val notify = isSectionCollapsed(sectionIndex) != collapsed

        collapsedSections[sectionIndex] = collapsed

        if (notify) {
            if (sections.isEmpty()) {
                buildSectionIndex()
            }

            val section = sections[sectionIndex]
            val number = section.numberOfItems

            if (collapsed) {
                notifySectionItemRangeRemoved(sectionIndex, 0, number, false)
            } else {
                notifySectionItemRangeInserted(sectionIndex, 0, number, false)
            }
        }
    }

    /**
     * @param sectionIndex index of section
     * @return true if that section is collapsed
     */
    private fun isSectionCollapsed(sectionIndex: Int): Boolean {
        return collapsedSections.getOrElse(sectionIndex) { false }
    }

    private fun getSectionSelectionState(sectionIndex: Int): SectionSelectionState = selectionStateBySection.getOrPut(sectionIndex) { SectionSelectionState() }

    /**
     * Clear selection state
     *
     * @param notify if true, notifies data change for recyclerview, if false, silent
     */
    fun clearSelection(notify: Boolean) {
        val selectionState = if (notify) HashMap(selectionStateBySection) else null
        selectionStateBySection = HashMap()

        if (notify) {
            // walk the selection state and update the items which were selected
            selectionState?.forEach { (sectionIndex, state) ->
                if (state.section) {
                    notifySectionDataSetChanged(sectionIndex)
                } else {
                    state.items.forEach { itemIndex, isSelected ->
                        if (isSelected) {
                            notifySectionItemChanged(sectionIndex, itemIndex)
                        }
                    }
                    if (state.footer) {
                        notifySectionFooterChanged(sectionIndex)
                    }
                }
            }
        }
    }

    fun isSelectionEmpty(): Boolean {
        selectionStateBySection.forEach { (_, state) ->
            if (state.section || state.footer) {
                return false
            }

            for (i in 0 until state.items.size()) if (state.items.valueAt(i)) return false
        }
        return true
    }

    fun getSelectedItemCount(): Int {
        var count = 0
        selectionStateBySection.forEach { (sectionIndex, state) ->
            count += countSelectedItemsInSection(sectionIndex, state)
        }
        return count
    }

    private fun countSelectedItemsInSection(sectionIndex: Int, state: SectionSelectionState): Int {
        var sectionCount = 0
        if (state.section) {
            sectionCount += getNumberOfItemsInSection(sectionIndex)
            if (doesSectionHaveFooter(sectionIndex)) sectionCount++
        } else {
            for (i in 0 until state.items.size()) if (state.items.valueAt(i)) sectionCount++
            if (state.footer) sectionCount++
        }
        return sectionCount
    }

    /**
     * Visitor interface for walking adapter selection state.
     */
    interface SelectionVisitor {
        fun onVisitSelectedSection(sectionIndex: Int)
        fun onVisitSelectedSectionItem(sectionIndex: Int, itemIndex: Int)
        fun onVisitSelectedFooter(sectionIndex: Int)
    }

    /**
     * Walks the selection state of the adapter, in reverse order from end to front. This is to ensure that any additions or deletions
     * which are made based on selection are safe to perform.
     *
     * @param visitor visitor which is invoked to process selection state
     */
    fun traverseSelection(visitor: SelectionVisitor) {
        val sectionIndices = selectionStateBySection.keys.sortedDescending()

        sectionIndices.forEach { sectionIndex ->
            val state = selectionStateBySection[sectionIndex] ?: return@forEach

            if (state.section) {
                visitor.onVisitSelectedSection(sectionIndex)
            } else {
                if (state.footer) {
                    visitor.onVisitSelectedFooter(sectionIndex)
                }

                for (i in state.items.size() - 1 downTo 0) {
                    if (state.items.valueAt(i)) {
                        visitor.onVisitSelectedSectionItem(sectionIndex, state.items.keyAt(i))
                    }
                }
            }
        }
    }

    /**
     * Set whether an entire section is selected. this affects ALL items (and footer) in section.
     *
     * @param sectionIndex index of the section
     * @param selected     selection state
     */
    private fun setSectionSelected(sectionIndex: Int, selected: Boolean) {
        val state = getSectionSelectionState(sectionIndex)

        if (state.section != selected) {
            state.section = selected

            // update all items and footers
            state.items.clear()
            for (i in 0 until getNumberOfItemsInSection(sectionIndex)) state.items.put(i, selected)

            if (doesSectionHaveFooter(sectionIndex)) state.footer = selected

            notifySectionDataSetChanged(sectionIndex)
        }
    }

    /**
     * Toggle selection state of an entire section
     *
     * @param sectionIndex index of section
     */
    fun toggleSectionSelected(sectionIndex: Int) {
        val isCurrentlySelected = isSectionSelected(sectionIndex)
        setSectionSelected(sectionIndex, !isCurrentlySelected)
    }

    /**
     * Check if section is selected
     *
     * @param sectionIndex index of section
     * @return true if section is selected
     */
    private fun isSectionSelected(sectionIndex: Int): Boolean = getSectionSelectionState(sectionIndex).section


    /**
     * Select a specific item in a section. Note, if the section is selected, this is a no-op.
     *
     * @param sectionIndex index of section
     * @param itemIndex    index of item, relative to section
     * @param selected     selection state
     */
    private fun setSectionItemSelected(sectionIndex: Int, itemIndex: Int, selected: Boolean) {
        val state = getSectionSelectionState(sectionIndex)

        if (state.section) return

        if (selected != state.items[itemIndex]) {
            state.items.put(itemIndex, selected)
            notifySectionItemChanged(sectionIndex, itemIndex)
        }
    }

    /**
     * Toggle selection state of a specific item in a section
     *
     * @param sectionIndex index of section
     * @param itemIndex    index of item in section
     */
    fun toggleSectionItemSelected(sectionIndex: Int, itemIndex: Int) {
        val currentlySelected = isSectionItemSelected(sectionIndex, itemIndex)
        setSectionItemSelected(sectionIndex, itemIndex, !currentlySelected)
    }

    /**
     * Check whether a specific item in a section is selected, or if the entire section is selected
     *
     * @param sectionIndex index of section
     * @param itemIndex    index of item in section
     * @return true if the item is selected
     */
    private fun isSectionItemSelected(sectionIndex: Int, itemIndex: Int): Boolean {
        val state = getSectionSelectionState(sectionIndex)
        return state.section || state.items[itemIndex]
    }

    /**
     * Select the footer of a section
     *
     * @param sectionIndex index of section
     * @param selected     selection state
     */
    private fun setSectionFooterSelected(sectionIndex: Int, selected: Boolean) {
        val state = getSectionSelectionState(sectionIndex)

        if (state.section) return

        if (state.footer != selected) {
            state.footer = selected
            notifySectionFooterChanged(sectionIndex)
        }
    }


    /**
     * Toggle selection of footer in a section
     *
     * @param sectionIndex index of section
     */
    fun toggleSectionFooterSelection(sectionIndex: Int) {
        val currentlySelected = isSectionFooterSelected(sectionIndex)
        setSectionFooterSelected(sectionIndex, !currentlySelected)
    }

    /**
     * Check whether footer of a section is selected, or if the entire section is selected
     *
     * @param sectionIndex section index
     * @return true if the footer is selected
     */
    private fun isSectionFooterSelected(sectionIndex: Int): Boolean {
        val state = getSectionSelectionState(sectionIndex)
        return state.section || state.footer
    }


    /**
     * Notify that all data in the list is invalid and the entire list should be reloaded.
     * NOTE: This will clear selection state, and collapsed section state.
     * Equivalent to RecyclerView.Adapter.notifyDataSetChanged.
     * Never directly call notifyDataSetChanged.
     */
    fun notifyAllSectionsDataSetChanged() {
        buildSectionIndex()
        notifyDataSetChanged()
        collapsedSections.clear()
        selectionStateBySection.clear()
    }

    /**
     * Notify that all the items in a particular section are invalid and that section should be reloaded
     * Never directly call notifyDataSetChanged.
     * This will clear item selection state for the affected section.
     *
     * @param sectionIndex index of the section to reload.
     */
    private fun notifySectionDataSetChanged(sectionIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]
            notifyItemRangeChanged(section.adapterPosition, section.length)
        }

        // Limpiar el estado de selección de los elementos de la sección
        getSectionSelectionState(sectionIndex).items.clear()
    }


    /**
     * Notify that a range of items in a section has been inserted
     *
     * @param sectionIndex index of the section
     * @param fromPosition index to start adding
     * @param number       amount of items inserted
     */
    fun notifySectionItemRangeInserted(sectionIndex: Int, fromPosition: Int, number: Int) {
        notifySectionItemRangeInserted(sectionIndex, fromPosition, number, true)
    }

    private fun notifySectionItemRangeInserted(sectionIndex: Int, fromPosition: Int, number: Int, updateSelectionState: Boolean) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]

            if (fromPosition > section.numberOfItems) {
                throw IndexOutOfBoundsException("itemIndex adapterPosition: $fromPosition exceeds sectionIndex numberOfItems: ${section.numberOfItems}")
            }

            var offset = fromPosition
            if (section.hasHeader) offset += 2

            notifyItemRangeInserted(section.adapterPosition + offset, number)
        }

        if (updateSelectionState) {
            // update selection state by inserting unselected spaces
            updateSectionItemRangeSelectionState(sectionIndex, fromPosition, number)
        }
    }


    /**
     * Notify that a range of items in a section has been removed
     *
     * @param sectionIndex index of the section
     * @param fromPosition index to start removing from
     * @param itemCount       amount of items removed
     */
    fun notifySectionItemRangeRemoved(sectionIndex: Int, fromPosition: Int, itemCount: Int) {
        notifySectionItemRangeRemoved(sectionIndex, fromPosition, itemCount, true)
    }

    fun notifySectionItemRangeRemoved(sectionIndex: Int, fromPosition: Int, number: Int, updateSelectionState: Boolean) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            val section = sections[sectionIndex]

            // 0 is a valid position to remove from
            if (fromPosition > section.numberOfItems) {
                throw IndexOutOfBoundsException("itemIndex adapterPosition: $fromPosition exceeds sectionIndex numberOfItems: ${section.numberOfItems}")
            }

            // Verify we don't run off the end of the section
            if (fromPosition + number > section.numberOfItems) {
                throw IndexOutOfBoundsException("itemIndex adapterPosition: ${fromPosition + number} exceeds sectionIndex numberOfItems: ${section.numberOfItems}")
            }

            var offset = fromPosition
            if (section.hasHeader) {
                offset += 2
            }

            notifyItemRangeRemoved(section.adapterPosition + offset, number)
            buildSectionIndex()
        }

        if (updateSelectionState) {
            updateSectionItemRangeSelectionState(sectionIndex, fromPosition, -number)
        }
    }


    /**
     * Notify that a particular itemIndex in a section has been invalidated and must be reloaded
     * Never directly call notifyItemChanged
     *
     * @param sectionIndex the index of the section containing the itemIndex
     * @param itemIndex    the index of the item relative to the section (where 0 is the first item in the section)
     */

    private fun notifySectionItemChanged(sectionIndex: Int, itemIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]
            if (itemIndex >= section.numberOfItems)
                throw IndexOutOfBoundsException("itemIndex adapterPosition: $itemIndex exceeds sectionIndex numberOfItems: ${section.numberOfItems}")

            var adjustedItemIndex = itemIndex
            if (section.hasHeader) adjustedItemIndex += 2

            notifyItemChanged(section.adapterPosition + adjustedItemIndex)
        }
    }


    /**
     * Notify that an item has been added to a section
     * Never directly call notifyItemInserted
     *
     * @param sectionIndex index of the section
     * @param itemIndex    index of the item where 0 is the first position in the section
     */
    fun notifySectionItemInserted(sectionIndex: Int, itemIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]

            val offset = if (section.hasHeader) itemIndex + 2 else itemIndex
            notifyItemInserted(section.adapterPosition + offset)
        }

        updateSectionItemRangeSelectionState(sectionIndex, itemIndex, 1)
    }


    /**
     * Notify that an item has been removed from a section
     * Never directly call notifyItemRemoved
     *
     * @param sectionIndex index of the section
     * @param itemIndex    index of the item in the section where 0 is the first position in the section
     */
    fun notifySectionItemRemoved(sectionIndex: Int, itemIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]

            val offset = if (section.hasHeader) itemIndex + 2 else itemIndex
            notifyItemRemoved(section.adapterPosition + offset)
        }

        updateSectionItemRangeSelectionState(sectionIndex, itemIndex, -1)
    }


    /**
     * Notify that a new section has been added
     *
     * @param sectionIndex position of the new section
     */
    fun notifySectionInserted(sectionIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]
            notifyItemRangeInserted(section.adapterPosition, section.length)
        }

        updateCollapseAndSelectionStateForSectionChange(sectionIndex, 1)
    }

    /**
     * Notify that a section has been removed
     *
     * @param sectionIndex position of the removed section
     */
    fun notifySectionRemoved(sectionIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            val section = sections[sectionIndex]
            buildSectionIndex()
            notifyItemRangeRemoved(section.adapterPosition, section.length)
        }

        updateCollapseAndSelectionStateForSectionChange(sectionIndex, -1)
    }

    /**
     * Notify that a section has had a footer added to it
     *
     * @param sectionIndex position of the section
     */
    fun notifySectionFooterInserted(sectionIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]
            require(section.hasFooter) {
                "notifySectionFooterInserted: adapter implementation reports that section $sectionIndex does not have a footer"
            }
            notifyItemInserted(section.adapterPosition + section.length - 1)
        }
    }


    /**
     * Notify that a section has had a footer removed from it
     *
     * @param sectionIndex position of the section
     */
    fun notifySectionFooterRemoved(sectionIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]

            require(!section.hasFooter) {
                "notifySectionFooterRemoved: adapter implementation reports that section $sectionIndex has a footer"
            }

            notifyItemRemoved(section.adapterPosition + section.length)
        }
    }


    /**
     * Notify that a section's footer's content has changed
     *
     * @param sectionIndex position of the section
     */
    private fun notifySectionFooterChanged(sectionIndex: Int) {
        if (sections.isEmpty()) {
            buildSectionIndex()
            notifyAllSectionsDataSetChanged()
        } else {
            buildSectionIndex()
            val section = sections[sectionIndex]

            require(section.hasFooter) {
                "notifySectionFooterChanged: adapter implementation reports that section $sectionIndex does not have a footer"
            }

            notifyItemChanged(section.adapterPosition + section.length - 1)
        }
    }


    /**
     * Post an action to be run later.
     * RecyclerView doesn't like being mutated during a scroll. We can't detect when a
     * scroll is actually happening, unfortunately, so the best we can do is post actions
     * from notify* methods to be run at a later date.
     *
     * @param action action to run
     */
    private fun post(action: Runnable) {
        if (mainThreadHandler == null) {
            mainThreadHandler = Handler(Looper.getMainLooper())
        }
        mainThreadHandler?.post(action)
    }

    private fun buildSectionIndex() {
        sections.clear()
        var totalItemsCount = 0

        for (sectionIndex in 0 until getNumberOfSections()) {
            val section = Section().apply {
                adapterPosition = totalItemsCount
                hasHeader = doesSectionHaveHeader(sectionIndex)
                hasFooter = doesSectionHaveFooter(sectionIndex)

                numberOfItems = getNumberOfItemsInSection(sectionIndex)
                length = if (isSectionCollapsed(sectionIndex)) 0 else numberOfItems

                if (hasHeader) length += 2 // Espacio para el encabezado y el ghostHeader

                if (hasFooter) length++ // Espacio para el footer
            }

            sections.add(section)
            totalItemsCount += section.length
        }

        totalNumberOfItems = totalItemsCount
        sectionIndicesByAdapterPosition = IntArray(totalNumberOfItems)
        var currentPosition = 0
        sections.forEachIndexed { index, section ->
            repeat(section.length) {
                sectionIndicesByAdapterPosition[currentPosition++] = index
            }
        }
    }

    private fun updateSectionItemRangeSelectionState(sectionIndex: Int, fromPosition: Int, delta: Int) {
        val sectionSelectionState = getSectionSelectionState(sectionIndex)
        val itemState = sectionSelectionState.items.clone()
        sectionSelectionState.items.clear()

        for (i in 0 until itemState.size()) {
            val pos = itemState.keyAt(i)

            if (delta < 0 && pos >= fromPosition && pos < fromPosition - delta) {
                continue
            }

            val newPos = if (pos >= fromPosition) pos + delta else pos

            if (itemState[pos]) {
                sectionSelectionState.items.put(newPos, true)
            }
        }
    }

    private fun updateCollapseAndSelectionStateForSectionChange(sectionIndex: Int, delta: Int) {
        // Actualizar el estado de colapso
        val newCollapsedSections = collapsedSections.filterKeys { key ->
            !(delta < 0 && key == sectionIndex)
        }.mapKeys { (key, _) ->
            if (key >= sectionIndex) key + delta else key
        }
        collapsedSections.clear()
        collapsedSections.putAll(newCollapsedSections)

        // Actualizar el estado de selección
        val newSelectionState = selectionStateBySection.filterKeys { key ->
            !(delta < 0 && key == sectionIndex)
        }.mapKeys { (key, _) ->
            if (key >= sectionIndex) key + delta else key
        }
        selectionStateBySection.clear()
        selectionStateBySection.putAll(newSelectionState)
    }


    override fun getItemCount(): Int {
        if (sections.isEmpty()) {
            buildSectionIndex()
        }
        return totalNumberOfItems
    }

    override fun getItemViewType(adapterPosition: Int): Int {
        if (sections.isEmpty()) {
            buildSectionIndex()
        }

        require(adapterPosition >= 0) {
            "adapterPosition ($adapterPosition) cannot be < 0"
        }
        require(adapterPosition < itemCount) {
            "adapterPosition ($adapterPosition) cannot be >= getItemCount() (${itemCount})"
        }

        val sectionIndex = getSectionForAdapterPosition(adapterPosition)
        val section = sections[sectionIndex]
        var localPosition = adapterPosition - section.adapterPosition

        val baseType = getItemViewBaseType(section, localPosition)
        var userType = 0

        when (baseType) {
            TYPE_HEADER -> {
                userType = getSectionHeaderUserType(sectionIndex)
                require(userType in 0..0xFF) {
                    "Custom header view type ($userType) must be in range [0,255]"
                }
            }

            TYPE_ITEM -> {
                // adjust local position to accommodate header & ghost header
                if (section.hasHeader) localPosition -= 2
                userType = getSectionItemUserType(sectionIndex, localPosition)
                require(userType in 0..0xFF) {
                    "Custom item view type ($userType) must be in range [0,255]"
                }
            }

            TYPE_FOOTER -> {
                userType = getSectionFooterUserType(sectionIndex)
                require(userType in 0..0xFF) {
                    "Custom footer view type ($userType) must be in range [0,255]"
                }
            }
        }

        return ((userType and 0xFF) shl 8) or (baseType and 0xFF)
    }


    /**
     * @param adapterPosition the adapterPosition of the item in question
     * @return the base type (TYPE_HEADER, TYPE_GHOST_HEADER, TYPE_ITEM, TYPE_FOOTER) of the item at a given adapter position
     */
    open fun getItemViewBaseType(adapterPosition: Int): Int {
        return unmaskBaseViewType(getItemViewType(adapterPosition))
    }

    /**
     * @param adapterPosition the adapterPosition of the item in question
     * @return the custom user type of the item at the adapterPosition
     */
    open fun getItemViewUserType(adapterPosition: Int): Int {
        return unmaskUserViewType(getItemViewType(adapterPosition))
    }

    private fun getItemViewBaseType(section: Section, localPosition: Int): Int {
        return when {
            section.hasHeader && section.hasFooter -> {
                when (localPosition) {
                    0 -> TYPE_HEADER
                    1 -> TYPE_GHOST_HEADER
                    section.length - 1 -> TYPE_FOOTER
                    else -> TYPE_ITEM
                }
            }

            section.hasHeader -> {
                when (localPosition) {
                    0 -> TYPE_HEADER
                    1 -> TYPE_GHOST_HEADER
                    else -> TYPE_ITEM
                }
            }

            section.hasFooter -> {
                when (localPosition) {
                    section.length - 1 -> TYPE_FOOTER
                    else -> TYPE_ITEM
                }
            }

            else -> TYPE_ITEM
        }
    }


    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val baseViewType = unmaskBaseViewType(viewType)
        val userViewType = unmaskUserViewType(viewType)

        return when (baseViewType) {
            TYPE_ITEM -> onCreateItemViewHolder(parent, userViewType) ?: throw IllegalArgumentException("onCreateItemViewHolder must not return null")
            TYPE_HEADER -> onCreateHeaderViewHolder(parent, userViewType) ?: throw IllegalArgumentException("onCreateHeaderViewHolder must not return null")
            TYPE_FOOTER -> onCreateFooterViewHolder(parent, userViewType) ?: throw IllegalArgumentException("onCreateFooterViewHolder must not return null")
            TYPE_GHOST_HEADER -> onCreateGhostHeaderViewHolder(parent)
            else -> throw IllegalArgumentException("Unrecognized view type: $viewType")
        }
    }

    override fun onBindViewHolder(holder: ViewHolder, adapterPosition: Int) {
        val section = getSectionForAdapterPosition(adapterPosition)

        // vincular las secciones a este ViewHolder
        holder.section = section
        holder.numberOfItemsInSection = getNumberOfItemsInSection(section)

        // etiquetar la vista del ViewHolder para seguimiento en el LayoutManager
        tagViewHolderItemView(holder)

        val baseType = unmaskBaseViewType(holder.itemViewType)
        val userType = unmaskUserViewType(holder.itemViewType)
        when (baseType) {
            TYPE_HEADER -> onBindHeaderViewHolder(holder as HeaderViewHolder, section, userType)
            TYPE_ITEM -> {
                val ivh = holder as ItemViewHolder
                val positionInSection = getPositionOfItemInSection(section, adapterPosition)
                ivh.positionInSection = positionInSection
                onBindItemViewHolder(ivh, section, positionInSection, userType)
            }

            TYPE_FOOTER -> onBindFooterViewHolder(holder as FooterViewHolder, section, userType)
            TYPE_GHOST_HEADER -> onBindGhostHeaderViewHolder(holder as GhostHeaderViewHolder, section)
            else -> throw IllegalArgumentException("unrecognized viewType: $baseType does not correspond to TYPE_ITEM, TYPE_HEADER, TYPE_GHOST_HEADER, or TYPE_FOOTER")
        }
    }


    /**
     * Tag the itemView of the view holder with information needed for the layout to do its sticky positioning.
     * Specifically, it tags R.id.sectioning_adapter_tag_key_view_type to the item type, R.id.sectioning_adapter_tag_key_view_section
     * to the item's section, and R.id.sectioning_adapter_tag_key_view_adapter_position which is the adapter position of the view
     *
     * @param holder          the view holder containing the itemView to tag
     */
    private fun tagViewHolderItemView(holder: ViewHolder) {
        holder.itemView.setTag(R.id.sectioning_adapter_tag_key_view_viewholder, holder)
    }

    companion object {
        const val NO_POSITION = -1
        const val TYPE_HEADER = 0
        const val TYPE_GHOST_HEADER = 1
        const val TYPE_ITEM = 2
        const val TYPE_FOOTER = 3
        fun unmaskBaseViewType(itemViewTypeMask: Int): Int = itemViewTypeMask and 0xFF
        fun unmaskUserViewType(itemViewTypeMask: Int): Int = (itemViewTypeMask shr 8) and 0xFF
    }
}