package com.ehoeve.canlens

import com.ehoeve.canlens.core.Signal
import com.ehoeve.canlens.core.DbcMessage
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A plotted signal. Plots stay up while moving between messages, so signals of different messages can be compared. */
data class PlotKey(val bus: Int, val id: Int, val signal: String)

class PlotState {
    val keys = mutableStateListOf<PlotKey>()
    /** Signals plotted before they are in a DBC (Decode's suggestions), with the message they decode. */
    val unsaved = mutableStateMapOf<PlotKey, Pair<DbcMessage, Signal>>()
    /** The time range all plots show; replaced when other data is loaded. */
    var view by mutableStateOf(ChartView(0f, 1f))
    /** All measurements on one chart instead of one each; with [sharedScale] on one value axis, else each filling the height. */
    var overlay by mutableStateOf(false)
    var sharedScale by mutableStateOf(false)
}
