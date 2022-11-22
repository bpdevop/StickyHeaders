package com.bpdevop.stickyheader

class Section {
    var adapterPosition // adapterPosition of first item (the header) of this sections
            = 0
    var numberOfItems // number of items (not including header or footer)
            = 0
    var length // total number of items in sections including header and footer
            = 0
    var hasHeader // if true, sections has a header
            = false
    var hasFooter // if true, sections has a footer
            = false
}