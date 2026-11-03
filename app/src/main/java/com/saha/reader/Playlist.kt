package com.saha.reader

import android.net.Uri

/** The list of files the user can swipe through (the file's folder, or the recent list). */
object Playlist {
    data class Item(val uri: Uri, val name: String)

    private var items: List<Item> = emptyList()
    var label: String = ""
        private set
    /** True once the "x of y · swipe" hint was shown for this list. */
    var hintShown = false

    fun set(list: List<Item>, label: String) {
        items = list
        this.label = label
        hintShown = false
    }

    fun indexOf(uri: Uri): Int = items.indexOfFirst { it.uri == uri }
    fun size(): Int = items.size
    fun get(i: Int): Item? = items.getOrNull(i)
}
