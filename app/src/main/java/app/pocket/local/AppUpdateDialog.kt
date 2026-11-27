package app.pocket.local

import android.app.Activity
import android.app.AlertDialog
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/** Adds the system install flow to the existing App updates dialog. */
internal class AppUpdateDialog(private val activity: Activity, private val beforeInstall: () -> Unit) {
    private class Attempt(val update: AppUpdate, val dialog: AlertDialog, val text: TextView) {
        val cancelled = AtomicBoolean()
        var sessionId: Int? = null
        var committed = false
        var confirmation: Intent? = null
    }

    private val installer get() = activity.packageManager.packageInstaller
    private val statusAction get() = "${activity.packageName}.INSTALL_UPDATE_RESULT"
    private var attempt: Attempt? = null
    private var awaitingPermission: AppUpdate? = null
    private var resumed = false
    private var receiverRegistered = false
    private var offer: AlertDialog? = null
    private var deferredError: String? = null
    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val installing = attempt ?: return
            if (intent.action != statusAction ||
                intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1) != installing.sessionId) return
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> requestConfirmation(installing, intent)
                PackageInstaller.STATUS_SUCCESS -> finish(installing)
                PackageInstaller.STATUS_FAILURE_ABORTED -> fail(installing, "The installation was cancelled.")
                PackageInstaller.STATUS_FAILURE_STORAGE -> fail(installing, "Free up storage on this phone and try again.")
                PackageInstaller.STATUS_FAILURE_BLOCKED -> fail(installing, "Android blocked the update. Check your install permission and device settings.")
                else -> fail(installing, "Android could not install the update. Check for updates and try again.")
            }
        }
    }

    val active get() = attempt != null || awaitingPermission != null

    fun show(update: AppUpdate): Boolean {
        if (!resumed || active || offer?.isShowing == true) return false
        val apk = update.apk
        val candidate = if (update.prerelease) "Experimental release candidate.\n\n" else ""
        val builder = AlertDialog.Builder(activity).setTitle("RunLocal ${update.versionName} is available")
        if (apk == null) {
            builder.setMessage("Check for updates again to retrieve the APK’s verification information.")
        } else {
            val size = android.text.format.Formatter.formatShortFileSize(activity, apk.bytes)
            builder.setMessage("${candidate}Download $size and install through Android. Your chats and models stay on this device.")
                .setPositiveButton("Download and install") { _, _ -> start(update) }
        }
        offer = builder.setNegativeButton("Later", null).show()
        return true
    }

    fun resume() {
        resumed = true
        awaitingPermission?.let { update ->
            awaitingPermission = null
            if (activity.packageManager.canRequestPackageInstalls()) start(update)
            else showError("Allow installs from RunLocal in Android settings to continue.")
        }
        attempt?.let(::advance)
        deferredError?.let { message ->
            deferredError = null
            showError(message)
        }
    }

    fun pause() { resumed = false }

    fun close() {
        resumed = false
        offer?.dismiss()
        offer = null
        attempt?.let(::finish)
        unregisterReceiver()
    }

    fun showProgress(): Boolean {
        val installing = attempt ?: return false
        installing.dialog.show()
        return true
    }

    private fun start(update: AppUpdate) {
        if (active || !eligible(update)) return
        if (!activity.packageManager.canRequestPackageInstalls()) {
            AlertDialog.Builder(activity).setTitle("Allow RunLocal to install updates")
                .setMessage("In Android settings, turn on Allow from this source for RunLocal. The download starts when you return.")
                .setPositiveButton("Open settings") { _, _ ->
                    awaitingPermission = update
                    runCatching {
                        activity.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}")))
                    }.onFailure {
                        awaitingPermission = null
                        showError("Open Android settings and allow RunLocal to install apps from this source.")
                    }
                }.setNegativeButton("Cancel", null).show()
            return
        }
        val padding = (24 * activity.resources.displayMetrics.density).toInt()
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding / 2, padding, 0)
        }
        val text = TextView(activity).apply {
            text = "Downloading update…"
            accessibilityLiveRegion = TextView.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply { max = 100 }
        content.addView(text)
        content.addView(progress, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val dialog = AlertDialog.Builder(activity).setTitle("Updating to ${update.versionName}").setView(content)
            .setCancelable(false).setNegativeButton("Cancel", null).create()
        val downloading = Attempt(update, dialog, text)
        attempt = downloading
        dialog.show()
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener { finish(downloading) }
        val app = activity.applicationContext
        worker.execute {
            val result = runCatching {
                stageUpdate(app, update, downloading.cancelled::get) { percent ->
                    activity.runOnUiThread {
                        if (attempt === downloading) {
                            progress.progress = percent
                            if (percent == 100) text.text = "Verifying the update…"
                        }
                    }
                }
            }
            activity.runOnUiThread {
                result.onSuccess { id ->
                    if (attempt === downloading) {
                        downloading.sessionId = id
                        text.text = "Update ready…"
                        advance(downloading)
                    } else runCatching { installer.abandonSession(id) }
                }.onFailure {
                    if (attempt === downloading) {
                        val message = if (it is IOException) "Check your connection and free storage, then try again."
                            else it.message ?: "Try downloading the update again."
                        fail(downloading, message)
                    }
                }
            }
        }
    }

    private fun eligible(update: AppUpdate) =
        update.versionCode > BuildConfig.VERSION_CODE && (!update.prerelease || UpdateNotifications(activity).includePrereleases)

    private fun advance(installing: Attempt) {
        if (!resumed) return
        val id = installing.sessionId ?: return
        installing.confirmation?.let { confirmation ->
            installing.confirmation = null
            runCatching { activity.startActivity(confirmation) }.onFailure {
                fail(installing, "Android could not open the installation prompt.", abandon = true)
            }
            return
        }
        if (installing.committed) return
        if (!eligible(installing.update)) { finish(installing); return }
        beforeInstall()
        runCatching {
            ContextCompat.registerReceiver(activity, statusReceiver, IntentFilter(statusAction), ContextCompat.RECEIVER_NOT_EXPORTED)
            receiverRegistered = true
            val callback = PendingIntent.getBroadcast(activity, id, Intent(statusAction).setPackage(activity.packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            installing.text.text = "Installing update with Android…"
            installing.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).apply {
                text = "Close"
                setOnClickListener { installing.dialog.hide() }
            }
            installer.openSession(id).use { it.commit(callback.intentSender) }
            installing.committed = true
        }.onFailure { fail(installing, "Android could not start the installation.") }
    }

    @Suppress("DEPRECATION") // The API 31–32 installer also sends this Intent.
    private fun requestConfirmation(installing: Attempt, result: Intent) {
        val approval = result.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        if (approval == null) fail(installing, "Android did not provide an installation prompt.", abandon = true)
        else {
            installing.confirmation = approval
            installing.text.text = "Confirm the update in Android’s installer."
            advance(installing)
        }
    }

    private fun unregisterReceiver() {
        if (receiverRegistered) {
            activity.unregisterReceiver(statusReceiver)
            receiverRegistered = false
        }
    }

    private fun finish(installing: Attempt, abandon: Boolean = !installing.committed) {
        attempt = null
        installing.cancelled.set(true)
        unregisterReceiver()
        if (abandon) installing.sessionId?.let { id -> runCatching { installer.abandonSession(id) } }
        installing.dialog.dismiss()
    }

    private fun fail(installing: Attempt, message: String, abandon: Boolean = !installing.committed) {
        finish(installing, abandon)
        if (resumed) showError(message) else deferredError = message
    }

    private fun showError(message: String) {
        AlertDialog.Builder(activity).setTitle("Update could not finish").setMessage(message)
            .setPositiveButton("OK", null).show()
    }

    private companion object { val worker = Executors.newSingleThreadExecutor() }
}
