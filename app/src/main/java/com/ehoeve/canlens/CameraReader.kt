package com.ehoeve.canlens

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text as SeenText
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions

private tailrec fun Context.lifecycleOwner(): LifecycleOwner? = this as? LifecycleOwner ?: (this as? ContextWrapper)?.baseContext?.lifecycleOwner()

// a decimal comma ("21,5") and the typographic minus and dash count too
private val NUMBER = Regex("""[-−–]?\d+(?:[.,]\d+)?""")

/**
 * The number in [text] nearest the middle of a [width] x [height] picture: the one the crosshair is on, as "-21.5".
 * Null when none has its centre within the middle 25 % of the width and 20 % of the height: the 96 x 48 dp box is about
 * a quarter of a phone's width and a tenth of the preview's height, and the preview crops the picture, so this is the
 * box with some room around it; a number elsewhere on the car's screen isn't taken for it.
 */
private fun middleNumber(text: SeenText, width: Int, height: Int): String? =
    text.textBlocks.flatMap { it.lines }.flatMap { it.elements }.mapNotNull { element ->
        val box = element.boundingBox ?: return@mapNotNull null
        // "78%" and "312 km" count, a word with a digit in it doesn't
        val number = NUMBER.find(element.text)?.value?.takeIf { it.length * 2 >= element.text.length } ?: return@mapNotNull null
        val dx = box.exactCenterX() - width / 2f
        val dy = box.exactCenterY() - height / 2f
        if (Math.abs(dx) > width * 0.125f || Math.abs(dy) > height * 0.1f) return@mapNotNull null
        number.replace('−', '-').replace('–', '-').replace(',', '.') to dx * dx + dy * dy
    }.minByOrNull { it.second }?.first

/**
 * Reads a number off the car's screen through the camera while a stream is recorded: the number under the crosshair,
 * twice a second. Each new value, once it has been read the same twice running, goes in as a reading ("range=312"
 * at that moment), as if typed in the marker box; Decode then looks for the field that carries it.
 */
@androidx.annotation.OptIn(ExperimentalGetImage::class)
@Composable
fun CameraReader(model: AppModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val owner = remember(context) { context.lifecycleOwner() }
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    var name by remember { mutableStateOf(model.readingName) }
    var seen by remember { mutableStateOf("") }
    var noting by remember { mutableStateOf(false) }
    var count by remember { mutableStateOf(0) }
    val clean = name.trim().replace("=", "")
    // the analyzer outlives recompositions: it reads what the user has set now
    val current by rememberUpdatedState(if (noting && clean.isNotEmpty()) clean else null)
    val preview = remember { PreviewView(context) }
    DisposableEffect(granted, owner) {
        if (!granted || owner == null) return@DisposableEffect onDispose { }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val main = ContextCompat.getMainExecutor(context)
        val cameras = ProcessCameraProvider.getInstance(context)
        var next = 0L
        var before: String? = null
        var noted: String? = null
        // the dialog can close before the camera is ready: then the camera isn't bound at all
        var disposed = false
        val analysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()
        analysis.setAnalyzer(main) { frame ->
            val image = frame.image
            val now = System.currentTimeMillis()
            if (image == null || now < next) { frame.close(); return@setAnalyzer }
            next = now + 500
            val turned = frame.imageInfo.rotationDegrees
            val (width, height) = if (turned % 180 == 0) frame.width to frame.height else frame.height to frame.width
            // a frame the reader can't take is closed here, or the camera sends no more
            val reading = try { recognizer.process(InputImage.fromMediaImage(image, turned)) } catch (e: Exception) {
                frame.close(); seen = e.message ?: "The text reader isn't ready yet"; return@setAnalyzer
            }
            reading
                .addOnSuccessListener { text ->
                    if (disposed) return@addOnSuccessListener
                    val number = middleNumber(text, width, height)
                    seen = number ?: "no number in the middle"
                    val into = current
                    if (number != null && number == before && number != noted && into != null) {
                        model.addMarker(model.beginMarker(), "$into=$number", remember = false)
                        noted = number
                        count++
                    }
                    before = number
                }
                .addOnFailureListener { seen = it.message ?: "The text reader isn't ready yet" }
                .addOnCompleteListener { frame.close() }
        }
        cameras.addListener({
            // both run on the main thread: closed before, nothing is bound; closed after, onDispose unbinds
            if (disposed) return@addListener
            runCatching {
                val camera = cameras.get()
                camera.unbindAll()
                camera.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, Preview.Builder().build().also { it.surfaceProvider = preview.surfaceProvider }, analysis)
            }.onFailure { seen = "Camera: ${it.message}" }
        }, main)
        onDispose {
            disposed = true
            analysis.clearAnalyzer()
            if (cameras.isDone) runCatching { cameras.get().unbindAll() }
            recognizer.close()
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.systemBarsPadding().imePadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    if (granted) AndroidView({ preview }, Modifier.fillMaxSize()) else Hint("The camera isn't allowed. Allow it for CANLens in the phone's settings.")
                    Box(Modifier.size(96.dp, 48.dp).border(2.dp, Color(0xFF4FC3F7), MaterialTheme.shapes.small))
                }
                Text(seen.ifEmpty { "Point the box at the number" }, style = MaterialTheme.typography.headlineSmall)
                OutlinedTextField(name, { name = it; model.readingName = it.trim() }, Modifier.fillMaxWidth(), singleLine = true, enabled = !noting, label = { Text("What the number is") }, placeholder = { Text("range") })
                Hint(if (noting) "$count readings of $clean noted. Keep the phone still; every change of the number is noted with its moment." else "Fix the phone so the box stays on the number, name it and start. Stop, then run Decode.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(enabled = granted && clean.isNotEmpty(), onClick = { noting = !noting }, modifier = Modifier.weight(1f)) { Text(if (noting) "Stop" else "Start noting") }
                    TextButton(onClick = onClose) { Text("Close") }
                }
            }
        }
    }
}
