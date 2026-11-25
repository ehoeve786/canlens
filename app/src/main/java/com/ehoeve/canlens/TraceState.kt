package com.ehoeve.canlens

import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.ehoeve.canlens.core.FrameStore

/** A moment the user noted, in store time. */
class Marker(val time: Int, val note: String)

/** A marker whose note is still being typed: the times were taken when the button was tapped. */
class PendingMarker(val time: Int, val fileMicros: Long?)

/** Where in time the user is looking, and what the frames table shows. Times are store time (100 µs units). */
class TraceState(private val store: FrameStore, private val preferences: SharedPreferences, private val live: () -> Boolean) {
    var cursor by mutableIntStateOf(0)
    /** The A/B marks of the comparison window; null when unset. */
    var markA by mutableStateOf<Int?>(null)
    var markB by mutableStateOf<Int?>(null)
    /** While a live stream runs: the cursor stays on the newest frame until the user moves it. */
    var followLive by mutableStateOf(false)
    val following: Boolean get() = followLive && live()
    val cursorNow: Int get() = if (following) store.lastTime else cursor
    val markers = mutableStateListOf<Marker>()

    /** The frames table's filter, as typed, and whether it lists every frame or one row per message. */
    var filter by mutableStateOf("")
    var fixed by mutableStateOf(false)
    /** Filters used before, newest first; kept between runs of the app. */
    val recentFilters = mutableStateListOf<String>().apply { addAll(preferences.getString("filters", "").orEmpty().split('\n').filter { it.isNotEmpty() }) }

    /** Puts the current filter at the front of [recentFilters]. */
    fun keepFilter() {
        val text = filter.trim()
        if (text.isEmpty()) return
        recentFilters.remove(text)
        recentFilters.add(0, text)
        while (recentFilters.size > MAX_RECENT) recentFilters.removeAt(recentFilters.lastIndex)
        saveFilters()
    }

    fun forgetFilter(text: String) { recentFilters.remove(text); saveFilters() }

    private fun saveFilters() = preferences.edit().putString("filters", recentFilters.joinToString("\n")).apply()

    /** Other data is coming: everything tied to the old data goes; the filter stays. */
    fun reset(cursor: Int, follow: Boolean) {
        this.cursor = cursor; markA = null; markB = null; followLive = follow
        markers.clear()
    }

    private companion object { const val MAX_RECENT = 12 }
}
