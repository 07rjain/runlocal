package app.pocket.local

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import java.util.concurrent.Executors

class UpdateNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    val configured get() = ReleaseFeed.validRepository(BuildConfig.RELEASE_REPOSITORY)
    var enabled: Boolean
        get() = configured && prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply(); schedule() }
    fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) { scheduler.cancel(JOB_ID); context.getSystemService(NotificationManager::class.java).cancel(64); return }
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build())
    }
    fun check(force: Boolean = false): AppUpdate? {
        if (!configured) error("The release repository will be configured in the official GitHub build.")
        if (!force && !enabled) return null
        val cached = prefs.getInt("availableCode", 0).takeIf { it > BuildConfig.VERSION_CODE && prefs.getString("availableRepo", "") == BuildConfig.RELEASE_REPOSITORY }?.let { code ->
            val version = prefs.getString("availableVersion", "").orEmpty()
            val url = prefs.getString("availableUrl", "").orEmpty()
            AppUpdate(code, version, url)
        }
        if (!force && System.currentTimeMillis() - prefs.getLong("lastAttempt", 0) < DAY) return cached
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
        val update = ReleaseFeed.fetch(BuildConfig.RELEASE_REPOSITORY, BuildConfig.VERSION_CODE)
        prefs.edit().putInt("availableCode", update?.versionCode ?: 0).putString("availableVersion", update?.versionName)
            .putString("availableUrl", update?.releaseUrl).putString("availableRepo", BuildConfig.RELEASE_REPOSITORY).apply()
        return update
    }
    fun notify(update: AppUpdate) {
        if (!enabled || prefs.getInt("notified", 0) >= update.versionCode) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app-updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 64, Intent(Intent.ACTION_VIEW, Uri.parse(update.releaseUrl)), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(64, Notification.Builder(context, "app-updates").setSmallIcon(R.drawable.ic_pocket)
            .setContentTitle("RunLocal ${update.versionName} is available").setContentText("View the GitHub release to download the update.")
            .setContentIntent(open).setAutoCancel(true).build())
        prefs.edit().putInt("notified", update.versionCode).apply()
    }
    companion object { const val JOB_ID = 64; const val DAY = 24 * 60 * 60 * 1000L }
}

class UpdateCheckService : JobService() {
    private val worker = Executors.newSingleThreadExecutor()
    private var task: java.util.concurrent.Future<*>? = null
    override fun onStartJob(params: JobParameters): Boolean {
        task = worker.submit {
            runCatching { val updates = UpdateNotifications(this); updates.check()?.let { updates.notify(it) } }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean { task?.cancel(true); return false }
    override fun onDestroy() { task?.cancel(true); worker.shutdownNow(); super.onDestroy() }
}
