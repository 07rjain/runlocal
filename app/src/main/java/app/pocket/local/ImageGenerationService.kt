package app.pocket.local

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** Foreground, separate-process inference: cancel/crash cannot kill the UI process. */
class ImageGenerationService : Service(), NativeProgressListener {
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var store: ImageStore
    private lateinit var job: JSONObject
    private var started = false
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        store = ImageStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("generation", "Local image generation", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == CANCEL) {
            if (!store.running()) { stopSelf(); return START_NOT_STICKY }
            synchronized(this) {
            val state = store.job().put("state", "cancelled").put("message", "Generation cancelled. Your models are kept.")
            state.optString("output").takeIf { it.isNotBlank() }?.let { File(it).delete() }
            store.writeJob(state); signal(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
            // This service is declared in its own process; terminate native work and release RAM.
            android.os.Process.killProcess(android.os.Process.myPid())
            }
            return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        val notification = notification("Loading model components…")
        if (Build.VERSION.SDK_INT >= 35) startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING)
        else startForeground(42, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        job = store.job()
        if (job.optString("state") != "queued") { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocket:image").apply { acquire(60 * 60 * 1000L) }
        job.put("state", "running").put("message", "Loading model components on your phone…")
        store.writeJob(job); signal()
        worker.execute {
            val result = runCatching {
                val output = File(job.getString("output"))
                require(output.parentFile?.canonicalFile == store.outputs.canonicalFile) { "Invalid output path." }
                val family = job.getString("family")
                val input = if (family == "sd15") job.optString("input") else ""
                val prepared = if (input.isNotEmpty()) prepareInput(input, job.getInt("width"), job.getInt("height")) else ""
                try {
                    NativeDiffusion.generate(arrayOf(family, job.optString("CHECKPOINT"), job.optString("DIFFUSION"),
                        job.optString("ENCODER"), job.optString("VAE"), job.optString("TOKENIZER"), job.optString("LORA"),
                        job.getString("prompt"), job.optString("negative"), prepared, output.absolutePath),
                        job.getInt("width"), job.getInt("height"), job.getInt("steps"), job.getLong("seed"),
                        job.getDouble("cfg").toFloat(), job.getDouble("loraScale").toFloat(), job.getDouble("strength").toFloat(), this)
                    check(output.isFile && output.length() > 0) { "The engine did not produce an image." }
                } finally { if (prepared.isNotEmpty()) File(prepared).delete() }
            }
            synchronized(this) {
                result.onSuccess { job.put("state", "complete").put("message", "Generated locally. Ready to save.") }
                    .onFailure { error ->
                        job.put("state", "error").put("message", error.message ?: "The native runtime could not run these weights.")
                        File(job.optString("output")).delete()
                    }
                store.writeJob(job)
                if (job.optString("state") == "complete") {
                    runCatching { File(job.getString("output") + ".json").writeText(job.toString(2)) }
                }
            }
            if (wakeLock?.isHeld == true) wakeLock?.release()
            stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId); signal()
        }
        return START_NOT_STICKY
    }
    @Synchronized
    override fun onProgress(step: Int, total: Int) {
        if (step == job.optInt("step", -1)) return
        job.put("step", step).put("total", total).put("message", "Generating on device · step $step of $total")
        store.writeJob(job)
        getSystemService(NotificationManager::class.java).notify(42, notification(job.getString("message")))
        signal()
    }
    private fun prepareInput(path: String, width: Int, height: Int): String {
        val source = File(path)
        require(source.canonicalFile.parentFile == File(filesDir, "image-inputs").canonicalFile) { "Invalid input image path." }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "The input image could not be decoded." }
        val options = BitmapFactory.Options().apply {
            inSampleSize = 1
            while (bounds.outWidth / inSampleSize > width * 2 || bounds.outHeight / inSampleSize > height * 2) inSampleSize *= 2
        }
        val bitmap = BitmapFactory.decodeFile(path, options) ?: error("Could not read the input image.")
        val scaled = Bitmap.createScaledBitmap(bitmap, width, height, true)
        val destination = File(cacheDir, "prepared-image.png")
        try { destination.outputStream().use { check(scaled.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
        finally { if (scaled !== bitmap) scaled.recycle(); bitmap.recycle() }
        return destination.absolutePath
    }
    private fun notification(message: String): Notification {
        val cancel = PendingIntent.getService(this, 1, Intent(this, ImageGenerationService::class.java).setAction(CANCEL), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 2, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "generation").setSmallIcon(app.pocket.local.R.drawable.ic_pocket)
            .setContentTitle("RunLocal · local image generation").setContentText(message).setOngoing(true)
            .setContentIntent(open).addAction(Notification.Action.Builder(null, "Cancel", cancel).build()).build()
    }
    private fun signal() { sendBroadcast(Intent(UPDATE).setPackage(packageName)) }
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.writeJob(store.job().put("state", "error").put("message", "Android stopped generation after the foreground-service time limit."))
        signal(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
        android.os.Process.killProcess(android.os.Process.myPid())
    }
    override fun onDestroy() { if (wakeLock?.isHeld == true) wakeLock?.release(); worker.shutdown(); super.onDestroy() }
    companion object {
        const val CANCEL = "app.pocket.local.CANCEL_IMAGE"
        const val UPDATE = "app.pocket.local.IMAGE_UPDATE"
    }
}
