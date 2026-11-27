package app.pocket.local

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException

/** One connection per request fences off callbacks from cancelled captures. */
internal class RecognitionClient(
    private val context: Context,
    private val onRecording: () -> Unit,
    private val onReading: () -> Unit,
    private val onComplete: (Result<String>) -> Unit
) {
    private var connection: Connection? = null

    fun start(token: String, model: String, source: Uri?, record: Boolean) {
        close()
        val next = Connection(token, model, source, record)
        connection = next
        try {
            next.bound = context.bindService(Intent(context, RecognitionService::class.java), next, Context.BIND_AUTO_CREATE)
            if (!next.bound) finish(next, Result.failure(IllegalStateException("Android could not start text recognition.")))
        } catch (error: Exception) {
            finish(next, Result.failure(error))
        }
    }

    fun finishRecording() = connection?.sendStop()

    fun close() {
        val current = connection ?: return
        connection = null
        current.cancel()
        if (current.bound) context.unbindService(current)
    }

    private fun finish(request: Connection, result: Result<String>) {
        if (connection !== request) return
        close()
        onComplete(result)
    }

    private inner class Connection(val token: String, val model: String, val source: Uri?, val record: Boolean) : ServiceConnection {
        var bound = false
        private var remote: Messenger? = null
        private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
            if (connection === this) when (message.what) {
                RecognitionService.RECORDING -> onRecording()
                RecognitionService.READING -> onReading()
                RecognitionService.RESULT -> finish(this, Result.success(message.data.getString(RecognitionService.TEXT).orEmpty()))
                RecognitionService.ERROR -> finish(this, Result.failure(
                    IllegalStateException(message.data.getString(RecognitionService.TEXT).orEmpty())
                ))
            }
            true
        })

        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (connection !== this) return
            remote = Messenger(binder)
            send(Message.obtain(null, RecognitionService.START).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString(RecognitionService.TOKEN, token)
                    putString(RecognitionService.MODEL, model)
                    putString(RecognitionService.SOURCE, source?.toString())
                    putBoolean(RecognitionService.RECORD, record)
                }
            })
        }

        override fun onServiceDisconnected(name: ComponentName) {
            finish(this, Result.failure(IllegalStateException("Text recognition stopped. Try again.")))
        }
        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) = onServiceDisconnected(name)

        private fun command(what: Int) = Message.obtain(null, what).apply {
            data = Bundle().apply { putString(RecognitionService.TOKEN, token) }
        }

        fun sendStop() = send(command(RecognitionService.FINISH_RECORDING))
        fun cancel() = send(command(RecognitionService.CANCEL))

        private fun send(message: Message) {
            try {
                remote?.send(message)
            } catch (_: RemoteException) {
                finish(this, Result.failure(IllegalStateException("Text recognition stopped. Try again.")))
            }
        }
    }
}
