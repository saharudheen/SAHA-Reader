package com.saha.reader

import android.net.Uri

/** The list of files the user can swipe through (a folder, or the recent list). */
object Playlist {
    data class Item(val uri: Uri, val name: String)

    private var items: List<Item> = emptyList()

    fun set(list: List<Item>) {
        items = list
    }

    fun indexOf(uri: Uri): Int = items.indexOfFirst { it.uri == uri }
    fun size(): Int = items.size
    fun get(i: Int): Item? = items.getOrNull(i)
}
