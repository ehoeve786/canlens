package com.ehoeve.canpilot

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** A plotted signal. Plots stay up while moving between messages, so signals of different messages can be compared. */
data class PlotKey(val bus: Int, val id: Int, val signal: String)

class PlotState {
    val keys = mutableStateListOf<PlotKey>()
    /** The time range all plots show; replaced when other data is loaded. */
    var view by mutableStateOf(ChartView(0f, 1f))
}
