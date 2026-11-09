package app.pocket.local

import android.app.ActivityManager
import android.app.Service
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.*
import java.io.File
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

/** Foreground UI use only: unbinding or leaving the Activity cancels generation and playback. */
class SpeechService : Service() {
    private data class Request(val id: Int, val reply: Messenger, val cancel: SpeechCancellation,
                               val output: File)
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var current: Request? = null
    private var player: MediaPlayer? = null
    private var focus: AudioFocusRequest? = null
    private var closed = false
    private val audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
    private val messenger = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            SPEAK -> speak(message)
            STOP -> stop("Speech stopped.")
        }
        true
    })

    override fun onCreate() {
        super.onCreate()
        cacheDir.listFiles().orEmpty().filter { it.name.startsWith("speech-") && it.extension == "wav" }.forEach { it.delete() }
    }
    override fun onBind(intent: Intent): IBinder = messenger.binder
    override fun onUnbind(intent: Intent): Boolean { stop("Speech stopped."); return false }

    private fun emit(request: Request, state: String, text: String) {
        try {
            request.reply.send(Message.obtain(null, EVENT).apply {
                arg1 = request.id
                data = Bundle().apply { putString("state", state); putString("message", text) }
            })
        } catch (_: RemoteException) {
            // The owning Activity has gone away. Never continue speaking for a dead client.
            request.cancel.cancel()
            if (current === request) { current = null; releasePlayer(); request.output.delete() }
        }
    }
    private fun releasePlayer() {
        player?.release(); player = null
        focus?.let { getSystemService(AudioManager::class.java).abandonAudioFocusRequest(it) }; focus = null
    }
    private fun stop(message: String) {
        val old = current
        current = null
        old?.cancel?.cancel()
        releasePlayer()
        if (old != null) emit(old, "stopped", message)
        // Generation removes its own partial file after observing cancellation.
        old?.output?.takeIf { it.isFile }?.delete()
    }
    private fun speak(message: Message) {
        val reply = message.replyTo ?: return
        stop("Replaced by a new reading.")
        val request = Request(message.arg1, reply, SpeechCancellation(), File(cacheDir, "speech-${UUID.randomUUID()}.wav"))
        current = request
        val text = message.data.getString("text").orEmpty()
        val voice = message.data.getString("voice").orEmpty()
        val result = runCatching {
            val spec = SpeechModelCatalog.find(message.data.getString("model").orEmpty())
            require(text.isNotBlank() && text.length <= MAX_TEXT) { "Read up to $MAX_TEXT characters at a time." }
            require(spec.voices.any { it.id == voice }) { "Choose a voice provided by this speech model." }
            require(SpeechModelStore(this).installed(spec)) { "Download this speech model in Models first." }
            spec
        }
        val spec = result.getOrElse { emit(request, "error", it.message ?: "Invalid speech request."); current = null; return }
        emit(request, "generating", "Preparing ${spec.title} · ${voice.replaceFirstChar { it.uppercase() }}…")
        worker.execute {
            try {
                if (request.cancel.isCancelled()) return@execute
                val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                require(!memory.lowMemory && memory.availMem > spec.memoryBytes) {
                    "Not enough free RAM for this speech model. Close other models or apps, then try again."
                }
                val metrics = SpeechBackends.create(this, spec.backend)
                    .synthesize(spec, voice, text, request.output, request.cancel)
                if (request.cancel.isCancelled()) { request.output.delete(); return@execute }
                main.post {
                    if (closed || current !== request || request.cancel.isCancelled()) request.output.delete()
                    else play(request, metrics)
                }
            } catch (error: Exception) {
                request.output.delete()
                main.post {
                    if (!closed && current === request) {
                        current = null
                        emit(request, if (request.cancel.isCancelled()) "stopped" else "error",
                            if (request.cancel.isCancelled()) "Speech stopped." else "Speech could not run: ${error.message ?: error.javaClass.simpleName}")
                    }
                }
            }
        }
    }
    private fun play(request: Request, metrics: SpeechSynthesisResult) {
        try {
            val media = MediaPlayer()
            player = media
            media.setAudioAttributes(audioAttributes)
            media.setDataSource(request.output.absolutePath)
            media.setOnPreparedListener {
                if (current !== request || request.cancel.isCancelled()) {
                    if (player === media) releasePlayer()
                    request.output.delete(); return@setOnPreparedListener
                }
                val manager = getSystemService(AudioManager::class.java)
                val audioFocus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                    .setAudioAttributes(audioAttributes).setOnAudioFocusChangeListener { change ->
                        if (change < 0 && current === request) stop("Speech stopped after audio focus changed.")
                    }.build()
                focus = audioFocus
                if (manager.requestAudioFocus(audioFocus) != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
                    stop("Audio is busy. Try reading again."); return@setOnPreparedListener
                }
                media.start()
                emit(request, "playing", "Playing locally · ${timing(metrics)}")
            }
            media.setOnCompletionListener {
                if (current === request) {
                    current = null; releasePlayer(); request.output.delete()
                    emit(request, "complete", "Finished locally · ${timing(metrics)}")
                }
            }
            media.setOnErrorListener { _, what, extra ->
                if (current === request) {
                    current = null; releasePlayer(); request.output.delete()
                    emit(request, "error", "Android could not play the generated audio ($what/$extra).")
                }
                true
            }
            media.prepareAsync()
        } catch (error: Exception) {
            current = null; releasePlayer(); request.output.delete()
            emit(request, "error", "Could not play speech: ${error.message}")
        }
    }
    private fun timing(result: SpeechSynthesisResult): String = String.format(Locale.US,
        "%.1f s audio · %.1f s generation · %.1f s load", result.frames.toDouble() / result.sampleRate,
        result.generationMs / 1000.0, result.loadMs / 1000.0)

    override fun onDestroy() {
        closed = true; stop("Speech stopped."); worker.shutdownNow(); super.onDestroy()
    }
    companion object {
        const val SPEAK = 1
        const val STOP = 2
        const val EVENT = 3
        const val MAX_TEXT = 2000
    }
}
