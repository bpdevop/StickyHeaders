package com.bpdevop.stickyheader.data

/**
 * Visitor interface for walking adapter selection state.
 */
interface SelectionVisitor {
    fun onVisitSelectedSection(sectionIndex: Int)
    fun onVisitSelectedSectionItem(sectionIndex: Int, itemIndex: Int)
    fun onVisitSelectedFooter(sectionIndex: Int)
}