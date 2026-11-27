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
import java.util.concurrent.Executors

class UpdateNotifications(private val context: Context) {
    private val prefs = context.getSharedPreferences("app-updates", Context.MODE_PRIVATE)
    val configured get() = ReleaseFeed.validRepository(BuildConfig.RELEASE_REPOSITORY)
    var enabled: Boolean
        get() = configured && prefs.getBoolean("enabled", true)
        set(value) { prefs.edit().putBoolean("enabled", value).apply(); schedule() }
    var includePrereleases: Boolean
        get() = prefs.getBoolean("includePrereleases", false)
        set(value) {
            if (value == includePrereleases) return
            // A candidate cached for one channel must never leak into stable-only checks.
            prefs.edit().putBoolean("includePrereleases", value).remove("lastAttempt")
                .remove("availableCode").remove("availableVersion").remove("availableUrl").remove("availableRepo")
                .remove("availableApkUrl").remove("availableApkSha256").remove("availableApkBytes")
                .remove("availablePrerelease").remove("availableCandidates").remove("shownVersion").remove("notified").apply()
            context.getSystemService(NotificationManager::class.java).cancel(64)
        }
    fun schedule() {
        val scheduler = context.getSystemService(JobScheduler::class.java)
        if (!enabled) { scheduler.cancel(JOB_ID); context.getSystemService(NotificationManager::class.java).cancel(64); return }
        if (prefs.getInt("notified", 0) in 1..BuildConfig.VERSION_CODE)
            context.getSystemService(NotificationManager::class.java).cancel(64)
        if (scheduler.getPendingJob(JOB_ID) == null) scheduler.schedule(JobInfo.Builder(JOB_ID, ComponentName(context, UpdateCheckService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPeriodic(DAY).setPersisted(true).build())
    }
    fun check(force: Boolean = false): AppUpdate? {
        if (!configured) error("The release repository will be configured in the official GitHub build.")
        if (!force && !enabled) return null
        val candidates = includePrereleases
        val cached = prefs.getInt("availableCode", 0).takeIf { it > BuildConfig.VERSION_CODE &&
            prefs.getString("availableRepo", "") == BuildConfig.RELEASE_REPOSITORY &&
            prefs.getBoolean("availableCandidates", false) == candidates &&
            (candidates || !prefs.getBoolean("availablePrerelease", false)) }?.let { code ->
            val version = prefs.getString("availableVersion", "").orEmpty()
            val url = prefs.getString("availableUrl", "").orEmpty()
            val apkUrl = prefs.getString("availableApkUrl", null)
            val digest = prefs.getString("availableApkSha256", null)
            val bytes = prefs.getLong("availableApkBytes", 0)
            val apk = if (apkUrl != null && digest != null && bytes > 0) UpdateApk(apkUrl, bytes, digest) else null
            AppUpdate(code, version, url, prefs.getBoolean("availablePrerelease", false), apk)
        }
        val legacyCache = cached != null && !prefs.contains("availableApkBytes")
        if (!force && !legacyCache && System.currentTimeMillis() - prefs.getLong("lastAttempt", 0) < DAY) return cached
        prefs.edit().putLong("lastAttempt", System.currentTimeMillis()).apply()
        val update = ReleaseFeed.fetch(BuildConfig.RELEASE_REPOSITORY, BuildConfig.VERSION_CODE, candidates)
        if (candidates != includePrereleases) return null // The user changed channels during the network read.
        prefs.edit().putInt("availableCode", update?.versionCode ?: 0).putString("availableVersion", update?.versionName)
            .putString("availableUrl", update?.releaseUrl).putString("availableRepo", BuildConfig.RELEASE_REPOSITORY)
            .putString("availableApkUrl", update?.apk?.url).putLong("availableApkBytes", update?.apk?.bytes ?: 0)
            .putString("availableApkSha256", update?.apk?.sha256)
            .putBoolean("availablePrerelease", update?.prerelease ?: false).putBoolean("availableCandidates", candidates).apply()
        return update
    }
    fun notify(update: AppUpdate) {
        if (!enabled || (update.prerelease && !includePrereleases) || prefs.getInt("notified", 0) >= update.versionCode) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel("app-updates", "App updates", NotificationManager.IMPORTANCE_DEFAULT))
        if (!manager.areNotificationsEnabled()) return
        val open = PendingIntent.getActivity(context, 64, Intent(context, MainActivity::class.java)
            .setAction(OPEN_UPDATES)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        manager.notify(64, Notification.Builder(context, "app-updates").setSmallIcon(R.drawable.ic_pocket)
            .setContentTitle("RunLocal ${update.versionName} is available")
            .setContentText(if (update.prerelease) "Experimental release candidate. Tap to update." else "Tap to update RunLocal.")
            .setContentIntent(open).setAutoCancel(true).build())
        prefs.edit().putInt("notified", update.versionCode).apply()
    }
    companion object {
        const val JOB_ID = 64
        const val DAY = 24 * 60 * 60 * 1000L
        const val OPEN_UPDATES = "app.pocket.local.OPEN_UPDATES"
    }
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
