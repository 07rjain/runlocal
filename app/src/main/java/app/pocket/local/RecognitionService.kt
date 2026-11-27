package app.pocket.local

import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.os.SystemClock
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Runs OCR and transcription on the recognition process's serial worker. */
internal class RecognitionService : Service() {
    private class Job(val client: Messenger, val token: String) {
        val cancelled = AtomicBoolean()
        val finishRecording = AtomicBoolean()
    }

    private val main = Handler(Looper.getMainLooper())
    private var job: Job? = null
    private val inbox = Messenger(Handler(Looper.getMainLooper()) { message ->
        when (message.what) {
            START -> start(message)
            FINISH_RECORDING -> job?.takeIf { it.token == message.data.getString(TOKEN) }?.finishRecording?.set(true)
            CANCEL -> if (job?.token == message.data.getString(TOKEN)) cancel()
        }
        true
    })

    override fun onBind(intent: Intent): IBinder = inbox.binder
    override fun onUnbind(intent: Intent): Boolean {
        cancel()
        return false
    }
    override fun onDestroy() {
        cancel()
        super.onDestroy()
    }

    private fun cancel() {
        job?.cancelled?.set(true)
        job = null
    }

    private fun start(message: Message) {
        val client = message.replyTo ?: return
        val token = message.data.getString(TOKEN) ?: return
        cancel()
        val request = Job(client, token)
        job = request
        val key = message.data.getString(MODEL).orEmpty()
        val record = message.data.getBoolean(RECORD)
        val source = message.data.getString(SOURCE)?.let(Uri::parse)
        recognitionWorker.execute {
            val result = runCatching {
                if (request.cancelled.get()) return@execute
                val mode = checkNotNull(RecognitionMode.fromModel(key)) { "Choose an OCR or transcription model." }
                val store = SpeechModelStore(applicationContext)
                val spec = if (key == RecognitionMode.MIRAGE_KEY) null else store.find(key.removePrefix(mode.prefix))
                require(spec == null || RecognitionMode.fromTask(RecipeAdapter.forBackend(spec.backend)?.task) == mode) {
                    "This model does not support the selected input."
                }
                val engine = RecognitionEngine(applicationContext)
                var started = SystemClock.elapsedRealtime()
                val text = if (record) {
                    require(mode == RecognitionMode.TRANSCRIPTION)
                    val model = checkNotNull(spec)
                    val file = File.createTempFile("chat-recording-", ".wav", cacheDir)
                    try {
                        recordChatWav(file, request.finishRecording::get, request.cancelled::get) {
                            main.post { if (job === request) emit(request, RECORDING) }
                        }
                        main.post { if (job === request) emit(request, READING) }
                        started = SystemClock.elapsedRealtime()
                        engine.transcribeRecording(file, model, request.cancelled)
                    } finally { file.delete() }
                } else {
                    val uri = checkNotNull(source) { "Choose a file to continue." }
                    if (mode == RecognitionMode.OCR) engine.readImage(uri, request.cancelled, spec)
                    else engine.transcribe(uri, checkNotNull(spec), request.cancelled)
                }
                if (!request.cancelled.get()) ModelTimings(this).record(spec?.id ?: "mirage", SystemClock.elapsedRealtime() - started)
                text
            }
            main.post {
                if (job !== request || request.cancelled.get()) return@post
                job = null
                result.fold(
                    {
                        if (it.length <= MAX_RESULT_CHARS) emit(request, RESULT, it)
                        else emit(request, ERROR, "The result is too long. Try a smaller image or shorter audio file.")
                    },
                    { emit(request, ERROR, it.message ?: "Could not recognize text. Try again.") }
                )
            }
        }
    }

    private fun emit(request: Job, event: Int, text: String = "") {
        try {
            request.client.send(Message.obtain(null, event).apply {
                data = Bundle().apply { putString(TEXT, text.take(MAX_RESULT_CHARS)) }
            })
        } catch (_: RemoteException) {
            request.cancelled.set(true)
        }
    }

    companion object {
        const val START = 1
        const val FINISH_RECORDING = 2
        const val READING = 3
        const val RESULT = 4
        const val ERROR = 5
        const val CANCEL = 6
        const val RECORDING = 7
        const val TOKEN = "token"
        const val MODEL = "model"
        const val SOURCE = "source"
        const val RECORD = "record"
        const val TEXT = "text"
        private const val MAX_RESULT_CHARS = 100_000
    }
}
