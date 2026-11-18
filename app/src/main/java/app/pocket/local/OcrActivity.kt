package app.pocket.local

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

/** Setup and extraction share the OCR process. No photos or recognized text are sent to the network. */
class OcrActivity : Activity() {
    private enum class Operation { DOWNLOAD, READ, REMOVE }

    private lateinit var content: LinearLayout
    private var status = ""
    private var transcript = ""
    private var operation: Operation? = null
    private var cancelled: AtomicBoolean? = null
    private var statusView: TextView? = null
    private val chatId get() = intent.getStringExtra(CHAT_ID)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        show()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()

    private fun message(value: String, size: Float = 15f, bold: Boolean = false): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xff222f27.toInt())
            setPadding(0, dp(5), 0, dp(8))
            if (bold) setTypeface(typeface, Typeface.BOLD)
            content.addView(this)
        }
    }

    private fun action(title: String, run: () -> Unit) {
        content.addView(Button(this).apply {
            text = title
            isAllCaps = false
            minHeight = dp(48)
            setOnClickListener { run() }
        })
    }

    private fun show() {
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
        }
        setContentView(ScrollView(this).apply { addView(content) })
        message(MirageModel.title, 26f, bold = true)
        message("Read printed Latin-script text from a photo. The image is processed on this phone.")
        statusView = message(status, 14f).apply { accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE }
        val running = operation
        if (running != null) {
            content.addView(ProgressBar(this), LinearLayout.LayoutParams(dp(28), dp(28)))
            if (running != Operation.REMOVE) action(if (running == Operation.DOWNLOAD) "Pause download" else "Cancel reading") {
                cancelled?.set(true)
                updateStatus(if (running == Operation.DOWNLOAD) "Pausing…" else "Cancelling…")
            }
            return
        }
        if (!MirageModel.supportedAbi) {
            message("Mirage requires an ARM64 Android build.")
            action("Done") { finish() }
            return
        }

        val installed = MirageModel.installed(this)
        if (transcript.isNotEmpty()) {
            message("Extracted text", 17f, bold = true)
            message(transcript).setTextIsSelectable(true)
            if (chatId != null) action("Insert into chat draft") {
                setResult(RESULT_OK, Intent().putExtra(CHAT_ID, chatId).putExtra(TRANSCRIPT, transcript))
                finish()
            }
            action("Copy text") {
                getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Extracted text", transcript))
                Toast.makeText(this, "Text copied.", Toast.LENGTH_SHORT).show()
            }
        }
        if (installed) {
            action(if (transcript.isEmpty()) "Choose an image" else "Choose another image") { chooseImage() }
            if (chatId == null) action("Remove model") { confirmRemove() }
        } else {
            message("21.6 MB download · Apache-2.0 · experimental")
            action(if (MirageModel.hasPartial(this)) "Resume download" else "Download Mirage") { download() }
        }
        if (chatId == null) action("Model and license") {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(MirageModel.modelUrl)))
            } catch (_: ActivityNotFoundException) {
                Toast.makeText(this, "No browser is available to open the model page.", Toast.LENGTH_SHORT).show()
            }
        }
        action("Done") { finish() }
    }

    private fun updateStatus(message: String) {
        status = message
        statusView?.text = message
    }

    private fun download() {
        work(Operation.DOWNLOAD, "Downloading Mirage…", { cancel ->
            var lastUpdate = 0L
            MirageModel.install(applicationContext, cancel::get) { bytes, verifying ->
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= 300L) {
                    lastUpdate = now
                    val percent = (bytes * 100 / MirageModel.size).toInt()
                    runOnUiThread {
                        if (cancelled === cancel && !cancel.get() && !isFinishing && !isDestroyed) {
                            updateStatus(if (verifying) "Verifying model… $percent%" else "Downloading Mirage… $percent%")
                        }
                    }
                }
            }
        }) { status = "Mirage installed. Choose an image to read it offline." }
    }

    @Suppress("DEPRECATION")
    private fun chooseImage() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "image/*"
            addCategory(Intent.CATEGORY_OPENABLE)
        }, IMAGE_REQUEST)
    }

    @Deprecated("Platform activity result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IMAGE_REQUEST || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        transcript = ""
        work(Operation.READ, "Reading image on this phone…", { cancel -> readImage(uri, cancel) }) { result ->
            transcript = result
            status = if (result.isBlank()) "No readable text found. Try a sharper photo or crop closer to the text."
                else "Check the result before using it. Small or stylized text can be missed."
        }
    }

    private fun readImage(uri: Uri, cancel: AtomicBoolean): String {
        val context = applicationContext
        check(MirageModel.installed(context)) { "Install Mirage before reading an image." }
        val memory = ActivityManager.MemoryInfo()
        getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
        check(!memory.lowMemory && memory.availMem > 256L * 1024 * 1024) { "Close other apps to free memory before reading this image." }

        val encoded = contentResolver.openInputStream(uri)?.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            while (true) {
                if (cancel.get()) throw CancellationException()
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= 24 * 1024 * 1024) { "Choose an image under 24 MB." }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        } ?: error("The selected image could not be opened.")

        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(encoded))) { decoder, info, _ ->
            val largest = maxOf(info.size.width, info.size.height)
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            if (largest > MirageNative.maxEdge) {
                val scale = MirageNative.maxEdge.toDouble() / largest
                decoder.setTargetSize((info.size.width * scale).roundToInt().coerceAtLeast(1),
                    (info.size.height * scale).roundToInt().coerceAtLeast(1))
            }
        }
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val rgb = ByteArray(width * height * 3)
            val row = IntArray(width)
            var offset = 0
            for (y in 0 until height) {
                if (cancel.get()) throw CancellationException()
                bitmap.getPixels(row, 0, width, 0, y, width, 1)
                for (pixel in row) {
                    val alpha = pixel ushr 24
                    for (shift in RGB_SHIFTS) {
                        val channel = (pixel ushr shift) and 255
                        // Composite transparency onto white so transparent pixels do not become black text.
                        rgb[offset++] = (255 - (255 - channel) * alpha / 255).toByte()
                    }
                }
            }
            if (cancel.get()) throw CancellationException()
            MirageNative.read(MirageModel.file(context), rgb, width, height)
        } finally {
            bitmap.recycle()
        }
    }

    private fun confirmRemove() {
        AlertDialog.Builder(this)
            .setTitle("Remove Mirage?")
            .setMessage("RunLocal will remove its downloaded copy of the model. Your photos and chat drafts are kept.")
            .setNegativeButton("Keep", null)
            .setPositiveButton("Remove") { _, _ ->
                work(Operation.REMOVE, "Removing Mirage…", { MirageModel.remove(applicationContext) }) {
                    transcript = ""
                    status = "Mirage removed."
                }
            }.show()
    }

    private fun <T> work(kind: Operation, initialStatus: String, task: (AtomicBoolean) -> T, success: (T) -> Unit) {
        if (operation != null) return
        val cancel = AtomicBoolean()
        cancelled = cancel
        operation = kind
        status = initialStatus
        show()
        worker.execute {
            val result = runCatching { task(cancel) }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                cancelled = null
                operation = null
                if (cancel.get() || result.exceptionOrNull() is CancellationException) {
                    status = if (kind == Operation.DOWNLOAD) "Download paused." else "Reading cancelled."
                } else {
                    result.onSuccess(success).onFailure {
                        status = it.message ?: "Mirage could not finish. Try again."
                    }
                }
                show()
            }
        }
    }

    override fun onStop() {
        if (operation != Operation.REMOVE) cancelled?.set(true)
        super.onStop()
    }

    companion object {
        const val REQUEST = 320
        const val CHAT_ID = "ocr_chat_id"
        const val TRANSCRIPT = "ocr_transcript"
        private const val IMAGE_REQUEST = 321
        private val RGB_SHIFTS = intArrayOf(16, 8, 0)
        private val worker = Executors.newSingleThreadExecutor()
    }
}
