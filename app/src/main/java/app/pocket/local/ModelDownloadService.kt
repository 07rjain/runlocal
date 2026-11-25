package app.pocket.local

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*
import org.json.JSONObject
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors

/** Public Hugging Face downloads only. Inference remains entirely local. */
class ModelDownloadService : Service() {
    private lateinit var store: ImageStore
    private lateinit var state: JSONObject
    private val worker = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private var started = false
    private var latestStartId = 0
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastProgress = 0L
    override fun onBind(intent: Intent?) = null
    override fun onCreate() {
        super.onCreate(); store = ImageStore(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel("model-downloads", "Model downloads", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == PAUSE) {
            synchronized(this) {
                if (store.setupRunning()) {
                    store.writeSetup(store.setup().put("state", "paused").put("message", "Download paused. Tap Resume when you’re ready."))
                    signal(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
            }
            stopSelf(); return START_NOT_STICKY
        }
        if (started) return START_NOT_STICKY
        started = true
        startForeground(43, notification("Preparing your model…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        state = store.setup()
        if (store.running()) {
            store.writeSetup(state.put("state", "error").put("message", "Wait for image generation to finish before adding a model."))
            signal(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY
        }
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "runlocal:downloads").apply { acquire(60 * 60 * 1000L) }
        worker.execute {
            val result = runCatching {
                val action = intent?.action
                if (action == RESOLVE || action == CATALOG) {
                    state = JSONObject().put("id", UUID.randomUUID().toString()).put("startedAt", System.currentTimeMillis())
                }
                if (action == RESOLVE) {
                    state.put("url", intent.getStringExtra("url").orEmpty()).put("state", "resolving").put("message", "Checking the model and finding its downloads…")
                    publish()
                    val plan = HuggingFaceResolver().resolve(state.getString("url"))
                    state.put("plan", plan.toJson()).put("message", "Model found. Checking space and memory…")
                    publish()
                } else if (action == CATALOG) {
                    val id = intent.getStringExtra(CATALOG_ID).orEmpty()
                    val plan = ModelSetupPlan.fromJson(intent.getStringExtra(CATALOG_PLAN).orEmpty())
                    ModelCatalog.validatePlan(plan)
                    state.put("catalogId", id).put("catalogKey", plan.files.single().key).put("plan", plan.toJson())
                }
                run {
                    val plan = ModelSetupPlan.fromJson(state.getString("plan"))
                    val memory = ActivityManager.MemoryInfo().also { getSystemService(ActivityManager::class.java).getMemoryInfo(it) }
                    require(plan.fitsInRam(memory.totalMem)) { "This model is too large for this phone's RAM. Try a lighter model instead." }
                    state.put("state", "downloading").put("startedAt", System.currentTimeMillis()).put("message", "Downloading ${plan.title}…")
                        .put("total", plan.totalBytes).put("bytes", 0).remove("etaSeconds")
                    publish()
                    val directory = File(filesDir, "model-downloads").apply { mkdirs() }
                    val missing = plan.files.filter { store.cached(it) == null }
                    val needed = missing.sumOf { (it.size - File(directory, it.key + ".part").length()).coerceAtLeast(0) }
                    require(StatFs(filesDir.path).availableBytes > needed + MODEL_STORAGE_RESERVE) { "Not enough free storage for this model. Free some space and tap Resume." }
                    var completed = plan.files.filter { store.cached(it) != null }.sumOf { it.size }
                    for (file in missing) {
                        val partial = File(directory, file.key + ".part")
                        val eta = DownloadEta()
                        val downloaded = ModelFileDownloader().download(file, partial,
                            checkSpace = { bytes -> require(StatFs(filesDir.path).availableBytes > bytes + MODEL_STORAGE_RESERVE) { "Storage is almost full. Free space and tap Resume." } },
                            progress = { bytes, verifying ->
                                val now = SystemClock.elapsedRealtime()
                                val remaining = if (verifying) null else eta.update(completed + bytes, plan.totalBytes, now)
                                if (now - lastProgress > 500) {
                                    lastProgress = now
                                    synchronized(this) {
                                        state.put("state", if (verifying) "verifying" else "downloading").put("bytes", completed + bytes).put("total", plan.totalBytes)
                                            .put("message", if (verifying) "Checking your download…" else "Downloading your model…")
                                        state.put("etaSeconds", remaining)
                                        publish()
                                    }
                                }
                            })
                        store.installRemote(downloaded, file)
                        completed += file.size
                    }
                    state.put("state", "ready").put("message", "Your model is added. Select it in Chat.").put("bytes", plan.totalBytes).put("total", plan.totalBytes)
                }
            }
            main.post {
                result.onFailure { error -> state.put("state", "error").put("message", error.message ?: "Setup could not finish. Check your connection and try again.") }
                publish()
                if (wakeLock?.isHeld == true) wakeLock?.release()
                stopForeground(STOP_FOREGROUND_REMOVE)
                started = false
                stopSelf(latestStartId)
            }
        }
        return START_NOT_STICKY
    }
    @Synchronized
    private fun publish() { store.writeSetup(state); getSystemService(NotificationManager::class.java).notify(43, notification(state.optString("message"))); signal() }
    private fun signal() { sendBroadcast(Intent(ImageGenerationService.UPDATE).setPackage(packageName)) }
    private fun notification(message: String): Notification {
        val pause = PendingIntent.getService(this, 3, Intent(this, ModelDownloadService::class.java).setAction(PAUSE), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val open = PendingIntent.getActivity(this, 4, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(this, "model-downloads").setSmallIcon(R.drawable.ic_pocket).setContentTitle("RunLocal · model setup").setContentText(message)
            .setOngoing(true).setContentIntent(open).addAction(Notification.Action.Builder(null, "Pause", pause).build()).build()
    }
    override fun onTimeout(startId: Int, fgsType: Int) {
        store.writeSetup(store.setup().put("state", "paused").put("message", "Android paused this download. Tap Resume to continue."))
        signal(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); android.os.Process.killProcess(android.os.Process.myPid())
    }
    override fun onDestroy() { if (wakeLock?.isHeld == true) wakeLock?.release(); worker.shutdown(); super.onDestroy() }
    companion object {
        const val RESOLVE = "app.pocket.local.RESOLVE_MODEL"
        const val DOWNLOAD = "app.pocket.local.DOWNLOAD_MODEL"
        const val CATALOG = "app.pocket.local.DOWNLOAD_CATALOG_MODEL"
        const val CATALOG_ID = "catalogId"
        const val CATALOG_PLAN = "catalogPlan"
        const val PAUSE = "app.pocket.local.PAUSE_DOWNLOAD"
    }
}
