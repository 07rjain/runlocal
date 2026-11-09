package app.pocket.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*

class SpeechClient(private val context: Context, private val status: (String, String) -> Unit) {
    private var registered = false
    private var remote: Messenger? = null
    private var pending: Message? = null
    private var serial = 0
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        if (message.what == SpeechService.EVENT && message.arg1 == serial && registered) {
            status(message.data.getString("state").orEmpty(), message.data.getString("message").orEmpty())
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (!registered) return
            remote = Messenger(binder)
            pending?.let { send(it) }; pending = null
        }
        override fun onServiceDisconnected(name: ComponentName) {
            if (!registered) return
            remote = null; pending = null
            status("error", "The speech process stopped. Android may have reclaimed memory. Try a short test again.")
            close()
        }
        override fun onBindingDied(name: ComponentName) { onServiceDisconnected(name) }
        override fun onNullBinding(name: ComponentName) { onServiceDisconnected(name) }
    }
    fun speak(spec: SpeechModelSpec, voice: String, text: String) {
        val message = Message.obtain(null, SpeechService.SPEAK).apply {
            arg1 = ++serial; replyTo = replies
            data = Bundle().apply { putString("model", spec.id); putString("voice", voice); putString("text", text) }
        }
        status("generating", "Starting ${spec.title}…")
        if (remote != null) { send(message); return }
        pending = message
        if (!registered) {
            try {
                registered = context.bindService(Intent(context, SpeechService::class.java), connection, Context.BIND_AUTO_CREATE)
                if (!registered) { pending = null; status("error", "Android could not start the speech service.") }
            } catch (error: Exception) { pending = null; status("error", "Could not start speech: ${error.message}") }
        }
    }
    private fun send(message: Message) {
        try { remote?.send(message) }
        catch (error: RemoteException) {
            remote = null // Clear the dead binder before close() tries to send STOP.
            status("error", "The speech service disconnected: ${error.message}"); close()
        }
    }
    fun stop() {
        ++serial; pending = null
        remote?.let { send(Message.obtain(null, SpeechService.STOP)) }
    }
    fun close() {
        stop()
        if (registered) { registered = false; context.unbindService(connection) }
        remote = null
    }
}
