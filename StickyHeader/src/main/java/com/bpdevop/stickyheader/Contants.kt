package com.bpdevop.stickyheader

object Contants {
    const val NO_POSITION = -1
    const val TYPE_HEADER = 0
    const val TYPE_GHOST_HEADER = 1
    const val TYPE_ITEM = 2
    const val TYPE_FOOTER = 3

    fun unmaskBaseViewType(itemViewTypeMask: Int): Int = itemViewTypeMask and 0xFF // base view type (HEADER/ITEM/FOOTER/GHOST_HEADER) is lower 8 bits

    fun unmaskUserViewType(itemViewTypeMask: Int): Int = itemViewTypeMask shr 8 and 0xFF // use type is in 0x0000FF00 segment
}