package com.bpdevop.stickyheader.data

class Section {
    var adapterPosition = 0 // adapterPosition of first item (the header) of this sections
    var numberOfItems = 0 // number of items (not including header or footer)
    var length = 0 // total number of items in sections including header and footer
    var hasHeader = false // if true, sections has a header
    var hasFooter = false // if true, sections has a footer

}