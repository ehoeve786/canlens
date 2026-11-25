package com.ehoeve.canlens

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Talks the driver through a test in the car, hands-free: it says each step, waits a moment for it to be done and
 * drops a marker named after it, then runs the whole test a second time so Decode can check that a bit changed at
 * every repeat. A step is what to say and the marker note; notes end in a word Decode pairs up (on/off, open/closed).
 */
class GuidedTest(context: Context, private val scope: CoroutineScope, private val mark: (String) -> Unit) {
    /** What it is doing, e.g. "Doors · 3/24", or null when no test runs. */
    var running by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    private var ready = false
    private val speech = TextToSpeech(context) { ready = it == TextToSpeech.SUCCESS }

    fun start(name: String, steps: List<Pair<String, String>>) {
        stop()
        val all = steps + steps
        job = scope.launch {
            try {
                say("$name test. Do each step when I say it.")
                delay(1500)
                for ((index, step) in all.withIndex()) {
                    running = "$name · ${index + 1}/${all.size}"
                    say(step.first)
                    // the marker a moment after the prompt: Decode looks from 1 s before to 2 s after it
                    delay(1000)
                    mark(step.second)
                    delay(4000)
                }
                say("Done. Stop the recording, then run Decode again.")
            } finally { running = null }
        }
    }

    fun stop() { job?.cancel(); job = null; speech.stop() }

    /** Speaks [text] and returns when it has been said (at once when speech isn't available). */
    private suspend fun say(text: String) {
        if (!ready) return
        suspendCancellableCoroutine { done ->
            speech.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) = Unit
                override fun onDone(utteranceId: String?) { if (done.isActive) done.resume(Unit) }
                @Deprecated("Deprecated in Java") override fun onError(utteranceId: String?) { if (done.isActive) done.resume(Unit) }
            })
            speech.speak(text, TextToSpeech.QUEUE_FLUSH, null, "step")
            done.invokeOnCancellation { speech.stop() }
        }
    }

    companion object {
        private fun pair(thing: String, first: String, second: String, firstWord: String, secondWord: String) =
            listOf("$first $thing" to "$thing $firstWord", "$second $thing" to "$thing $secondWord")

        val SCRIPTS: Map<String, List<Pair<String, String>>> = linkedMapOf(
            "Doors" to listOf("driver door", "passenger door", "rear left door", "rear right door", "trunk", "frunk")
                .flatMap { pair(it, "Open the", "Close the", "open", "closed") },
            "Lights" to listOf("headlights", "high beams", "hazard lights", "left indicator", "right indicator")
                .flatMap { pair(it, "Turn on the", "Turn off the", "on", "off") },
            "Climate" to pair("fan speed", "Turn up the", "Turn down the", "up", "down") +
                listOf("climate", "A C", "recirculation", "front defrost", "rear defrost").flatMap { pair(it, "Turn on", "Turn off", "on", "off") },
            "Seats" to listOf("driver seat heater", "passenger seat heater", "steering wheel heater")
                .flatMap { pair(it, "Turn on the", "Turn off the", "on", "off") },
            "Windows" to listOf("driver window", "passenger window", "rear left window", "rear right window")
                .flatMap { pair(it, "Lower the", "Raise the", "down", "up") },
            "Wipers" to pair("wipers", "Turn on the", "Turn off the", "on", "off") + pair("washer", "Press the", "Release the", "pressed", "released"),
            "Passenger" to listOf("Passenger, sit in the front seat" to "passenger in", "Passenger, get out of the front seat" to "passenger out"),
        )
    }
}
